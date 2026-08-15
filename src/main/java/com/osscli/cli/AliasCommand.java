package com.osscli.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Call this whatever you want.
 *
 * <p>A short command name is convenient and it is also a land grab: three letters on {@code $PATH}
 * is exactly the length that collides with something already installed, and the collision surfaces
 * as the wrong program running, not as an error. Rather than argue about which name is safe, this
 * lets each person pick their own -- {@code buddy}, {@code hey}, initials, anything.
 *
 * <pre>{@code
 * oss alias buddy      # now `buddy review 4234` works
 * oss alias --list
 * oss alias --remove buddy
 * }</pre>
 *
 * <p>The shim delegates to the <em>launcher</em>, never to a versioned jar. Pointing it at
 * {@code Cellar/oss-cli/1.6.0/libexec/oss-cli.jar} would work perfectly until the next
 * {@code brew upgrade}, after which the alias silently keeps running the old build while the real
 * command runs the new one -- two versions on one machine, disagreeing, with nothing saying so.
 */
@Command(
        name = "alias",
        mixinStandardHelpOptions = true,
        description = "Give this command your own name (buddy, hey, anything)")
public class AliasCommand implements Callable<Integer> {

    /** Where a personal shim goes. Conventional, per-user, and needs no privileges. */
    private static final Path BIN = Path.of(System.getProperty("user.home"), ".local", "bin");

    /** Marks shims we wrote, so --list and --remove cannot touch somebody else's script. */
    private static final String MARKER = "# created by `oss alias` — safe to delete";

    /**
     * Names the shell resolves before PATH is ever consulted. Reserved words are parsed as syntax
     * and builtins are dispatched internally, so a shim carrying one of these names can never run —
     * {@code oss alias if} would report success and then {@code if --help} hangs the prompt waiting
     * for a {@code then}. The PATH-collision check below cannot catch these, because most of them
     * have no file on PATH to collide with. Not overridable with {@code --force}: force can shadow
     * a file, it cannot change how a shell parses.
     */
    private static final Set<String> SHELL_WORDS = Set.of(
            // reserved words (bash and zsh)
            "if",
            "then",
            "else",
            "elif",
            "fi",
            "case",
            "esac",
            "for",
            "select",
            "while",
            "until",
            "do",
            "done",
            "in",
            "function",
            "time",
            "coproc",
            "repeat",
            "foreach",
            "end",
            // builtins that live only inside the shell
            "cd",
            "alias",
            "unalias",
            "source",
            "eval",
            "exec",
            "exit",
            "export",
            "set",
            "unset",
            "shift",
            "trap",
            "return",
            "break",
            "continue",
            "local",
            "declare",
            "typeset",
            "readonly",
            "let",
            "pushd",
            "popd",
            "dirs",
            "disown",
            "suspend",
            "logout",
            "builtin",
            "command",
            "enable",
            "shopt",
            "complete",
            "compgen",
            "bind",
            "history",
            "fc",
            "jobs",
            "bg",
            "fg",
            "wait",
            "read",
            "getopts",
            "hash",
            "type",
            "ulimit",
            "umask",
            "times",
            "help",
            "whence",
            "where",
            "rehash",
            "setopt",
            "unsetopt",
            "autoload",
            "bindkey",
            "zle",
            "zmodload",
            "print");

    @Parameters(index = "0", arity = "0..1", description = "The name you want to type")
    String name;

    @Option(names = "--list", description = "Show the names already created")
    boolean list;

    @Option(names = "--remove", paramLabel = "NAME", description = "Remove a name")
    String remove;

    @Option(names = "--force", description = "Create it even if the name is already taken")
    boolean force;

    @Override
    public Integer call() {
        try {
            if (list) {
                return doList();
            }
            if (remove != null) {
                return doRemove(remove);
            }
            if (name == null || name.isBlank()) {
                System.err.println("error  give a name: oss alias buddy");
                return 1;
            }
            return create(name.trim());
        } catch (IOException e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    private Integer create(String alias) throws IOException {
        if (!alias.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            System.err.println("error  \"" + alias + "\" is not a plain command name (letters, digits, . _ -)");
            return 1;
        }

        // Lower-cased so "If" is refused too: the keyword check in a shell is case-sensitive, but
        // the default macOS filesystem is not, and the pair is exactly the confusion this avoids.
        if (SHELL_WORDS.contains(alias.toLowerCase(Locale.ROOT))) {
            System.err.println("error  \"" + alias + "\" is a shell keyword or builtin — the shell resolves it"
                    + " before PATH is consulted, so this name could never reach oss");
            System.err.println("       Pick another name. --force cannot help here.");
            return 1;
        }

        // The collision this whole command exists for. Checked BEFORE writing, because a shim that
        // shadows an existing tool is discovered as that tool mysteriously misbehaving.
        Path clash = onPath(alias);
        Path target = BIN.resolve(alias);
        if (clash != null && !clash.equals(target) && !force) {
            System.err.println("error  \"" + alias + "\" already exists on your PATH: " + clash);
            System.err.println("       Pick another name, or --force to shadow it (it will stop being reachable).");
            return 1;
        }

        Path launcher = findLauncher();
        if (launcher == null) {
            System.err.println("error  could not find the installed launcher on PATH");
            System.err.println("       An alias delegates to it, so that a brew upgrade updates both at once.");
            return 1;
        }

        Files.createDirectories(BIN);
        String shim = "#!/bin/sh\n"
                + MARKER + "\n"
                + "# Delegates to the launcher rather than to a versioned jar, so an upgrade moves\n"
                + "# this name with it instead of leaving it on an old build.\n"
                + "exec \"" + launcher + "\" \"$@\"\n";
        Files.writeString(target, shim);
        target.toFile().setExecutable(true, false);

        System.out.println("  ✓ " + alias + " → " + launcher);
        if (onPath(alias) == null) {
            // Created, but unreachable -- worth saying now rather than leaving someone to wonder
            // why a command they just made does not exist.
            System.out.println();
            System.out.println("  " + BIN + " is not on your PATH yet. Add it:");
            System.out.println("    echo 'export PATH=\"$HOME/.local/bin:$PATH\"' >> ~/.zshrc && exec zsh");
        } else {
            System.out.println("    try: " + alias + " --help");
        }
        return 0;
    }

    private Integer doList() throws IOException {
        List<String> ours = ours();
        if (ours.isEmpty()) {
            System.out.println("No names created yet.  oss alias buddy");
            return 0;
        }
        for (String n : ours) {
            System.out.println("  " + n + "  →  " + BIN.resolve(n));
        }
        return 0;
    }

    private Integer doRemove(String alias) throws IOException {
        Path p = BIN.resolve(alias);
        // Only remove what we wrote. Without the marker check this would happily delete an
        // unrelated script of the same name that somebody else put there.
        if (!carriesMarker(p)) {
            System.err.println("error  no alias named \"" + alias + "\" created by oss");
            return 1;
        }
        Files.delete(p);
        System.out.println("  removed " + alias);
        return 0;
    }

    private List<String> ours() throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(BIN)) {
            return out;
        }
        try (var s = Files.list(BIN)) {
            for (Path p : s.toList()) {
                if (carriesMarker(p)) {
                    out.add(p.getFileName().toString());
                }
            }
        }
        return out;
    }

    /**
     * Whether this file is a shim we wrote.
     *
     * <p>{@code ~/.local/bin} is where people keep binaries, and {@code Files.readString} throws
     * {@code MalformedInputException} on the first byte that is not UTF-8. That took out the whole
     * listing: {@code oss alias --list} answered {@code error  Input length = 1} because an
     * unrelated executable happened to share the directory. Nothing to do with aliases, no way to
     * tell from the message, and it would happen on almost any machine.
     *
     * <p>Read as bytes and decode leniently: a shim is a short shell script, so anything that
     * cannot be decoded is not one. Any read failure is a no, never an exception -- a directory
     * this command merely scans must not be able to fail the command.
     */
    private static boolean carriesMarker(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 64 * 1024) {
                return false;
            }
            return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8).contains(MARKER);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The installed launcher: a real command on PATH, not this JVM and not the jar. */
    private Path findLauncher() {
        for (String candidate : new String[] {"oss", "oss-cli"}) {
            Path p = onPath(candidate);
            if (p != null && !oursQuietly().contains(candidate)) {
                return p;
            }
        }
        return null;
    }

    private List<String> oursQuietly() {
        try {
            return ours();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path onPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(":")) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, cmd);
            if (Files.isExecutable(p) && Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
