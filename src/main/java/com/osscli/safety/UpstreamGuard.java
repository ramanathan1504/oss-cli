package com.osscli.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.AppPaths;
import java.io.Console;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * A passphrase between an outward-facing write and the thing it writes to.
 *
 * <p>Reading a public repository is free to get wrong. Writing to one is not: a comment, a review or
 * an issue reaches a mailing list and everybody watching the thread the instant it is posted, and
 * deleting it afterwards does not reach the archive or the mail already sent. There is no undo, only
 * a correction with an audience.
 *
 * <p>OSS-CLI itself cannot write -- every request its GitHub client builds is a {@code GET}. The
 * exposure is the extensions, which shell out to real tools that can post. So the gate sits at the
 * dispatch boundary: an extension declares which of its verbs write outward, and none of those runs
 * until a passphrase is entered at the terminal.
 *
 * <p>Design notes worth keeping:
 *
 * <ul>
 *   <li><b>Stored as a PBKDF2 hash, never the passphrase.</b> The file sits in a home directory that
 *       gets backed up, synced and occasionally pasted into a bug report.
 *   <li><b>No console means refuse, never assume.</b> Cron, CI and a piped stdin cannot answer a
 *       prompt. Treating "cannot ask" as "go ahead" would make the guard vanish in exactly the
 *       unattended contexts where nobody is watching what gets posted.
 *   <li><b>Unarmed means refuse too, with the command to arm it.</b> Defaulting to "allow when no
 *       passphrase is set" makes the protection depend on remembering to switch it on.
 * </ul>
 */
public class UpstreamGuard {

    /** {@code ~/.oss-cli/upstream-guard.json}; moves with OSS_CLI_HOME like everything else. */
    public static final Path GUARD_FILE = AppPaths.BASE_DIR.resolve("upstream-guard.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    /**
     * Escape hatch for automation that has already been authorised out of band.
     *
     * <p>Named to be embarrassing to type, and reported on every use, because a variable that
     * silently disables a safety gate is worse than no gate: it removes the protection while leaving
     * the reassurance.
     */
    public static final String BYPASS_ENV = "OSS_CLI_I_ACCEPT_UPSTREAM_WRITES";

    private UpstreamGuard() {}

    /** Whether a passphrase has been set on this machine. */
    public static boolean isArmed() {
        return Files.isRegularFile(GUARD_FILE);
    }

    /** Set or replace the passphrase. Prompts twice and never echoes. */
    public static boolean arm() {
        Console console = System.console();
        if (console == null) {
            System.err.println("error  no terminal — set the upstream passphrase interactively");
            return false;
        }
        char[] first = console.readPassword("Set upstream-write passphrase: ");
        char[] again = console.readPassword("Repeat it: ");
        try {
            if (first == null || first.length == 0) {
                System.err.println("error  empty passphrase; nothing changed");
                return false;
            }
            if (!Arrays.equals(first, again)) {
                System.err.println("error  they do not match; nothing changed");
                return false;
            }
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            Map<String, Object> stored = new HashMap<>();
            stored.put("algorithm", ALGORITHM);
            stored.put("iterations", ITERATIONS);
            stored.put("salt", Base64.getEncoder().encodeToString(salt));
            stored.put("hash", Base64.getEncoder().encodeToString(hash(first, salt)));
            write(stored);
            System.out.println("  upstream-write guard armed — " + GUARD_FILE);
            return true;
        } finally {
            // The prompt returns a char[] precisely so it can be cleared; a String could not be.
            Arrays.fill(first, '\0');
            if (again != null) {
                Arrays.fill(again, '\0');
            }
        }
    }

    /** Remove the passphrase. Writes become impossible rather than unguarded. */
    public static boolean disarm() {
        try {
            return Files.deleteIfExists(GUARD_FILE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove " + GUARD_FILE, e);
        }
    }

    /**
     * Gate one outward-facing action.
     *
     * @param what human description, shown before the prompt, e.g. {@code "log4j followup --comment"}
     * @param target where it would land, e.g. {@code "apache/logging-log4j2"}
     * @return true only when the operator typed the right passphrase at a real terminal
     */
    public static boolean confirm(String what, String target) {
        if (System.getenv(BYPASS_ENV) != null) {
            System.err.println("  ! " + BYPASS_ENV + " is set — upstream write NOT gated");
            System.err.println("    " + what + " -> " + target);
            return true;
        }

        System.out.println();
        System.out.println("  [33mThis writes to " + target + ".[0m");
        System.out.println("  Action: " + what);
        System.out.println("  A post is not undoable: it reaches watchers and the mailing list at once.");
        System.out.println();

        if (!isArmed()) {
            System.err.println("error  refused — no upstream passphrase is set on this machine.");
            System.err.println("       Set one first:  oss-cli setup --upstream-guard");
            return false;
        }
        Console console = System.console();
        if (console == null) {
            System.err.println("error  refused — no terminal to confirm at.");
            System.err.println("       An upstream write is never performed unattended.");
            return false;
        }

        char[] entered = console.readPassword("  Passphrase to proceed (or ctrl-c): ");
        try {
            if (entered == null || entered.length == 0) {
                System.err.println("error  refused — nothing entered.");
                return false;
            }
            if (verify(entered)) {
                System.out.println("  confirmed.");
                return true;
            }
            System.err.println("error  refused — passphrase did not match.");
            return false;
        } finally {
            if (entered != null) {
                Arrays.fill(entered, '\0');
            }
        }
    }

    private static boolean verify(char[] candidate) {
        try {
            Map<?, ?> stored = MAPPER.readValue(Files.readString(GUARD_FILE), Map.class);
            byte[] salt = Base64.getDecoder().decode((String) stored.get("salt"));
            byte[] expected = Base64.getDecoder().decode((String) stored.get("hash"));
            int iterations = ((Number) stored.get("iterations")).intValue();
            byte[] actual = hash(candidate, salt, iterations);
            // Constant-time: a length-independent early return would leak how much was right.
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + GUARD_FILE, e);
        }
    }

    private static byte[] hash(char[] passphrase, byte[] salt) {
        return hash(passphrase, salt, ITERATIONS);
    }

    private static byte[] hash(char[] passphrase, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("could not derive key: " + e.getMessage(), e);
        }
    }

    private static void write(Map<String, Object> stored) {
        try {
            Files.createDirectories(GUARD_FILE.getParent());
            Path tmp = GUARD_FILE.resolveSibling(GUARD_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(stored));
            Files.move(tmp, GUARD_FILE, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(
                        GUARD_FILE, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; the hash is useless without the passphrase regardless.
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + GUARD_FILE, e);
        }
    }
}
