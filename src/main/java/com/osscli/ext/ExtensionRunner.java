package com.osscli.ext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a registered extension as a child process.
 *
 * <p>Three things here are deliberate, and each one was a plausible bug avoided:
 *
 * <ul>
 *   <li><b>The working directory is the extension's root, not the caller's.</b> A bench resolves its
 *       own configs, clones and caches relative to itself. Inheriting the caller's cwd makes it work
 *       from one directory and fail from every other, which reads as a broken bench.
 *   <li><b>Output is inherited, not captured.</b> A matrix sweep runs for hours and its value is the
 *       progress it prints. Buffering that to return a String at the end would turn a live run into
 *       a silence, and could exhaust memory on a long one.
 *   <li><b>No shell.</b> The command is exec'd as an argument vector, so a repro name containing a
 *       space or a semicolon is an argument rather than a second command.
 * </ul>
 */
public class ExtensionRunner {

    private ExtensionRunner() {}

    /**
     * Dispatch a portable verb to an extension and wait for it.
     *
     * @param ext the registered extension
     * @param portableVerb what OSS-CLI calls the operation, e.g. {@code run}, {@code file}
     * @param passthrough arguments handed to the tool untouched, after its own verb
     * @return the child's exit status, so the caller can propagate it
     * @throws IllegalArgumentException when the extension does not declare that verb
     */
    public static int run(Extension ext, String portableVerb, List<String> passthrough) {
        String native_ = ext.resolveVerb(portableVerb);
        if (native_ == null) {
            throw new IllegalArgumentException("\"" + ext.getName() + "\" does not offer the verb \"" + portableVerb
                    + "\" -- it declares: " + String.join(", ", ext.getVerbs().keySet()));
        }

        List<String> command = new ArrayList<>();
        command.add(ext.execPath().toString());
        // A verb may map to several tokens ("pr --diff"), so split rather than assuming one word.
        for (String token : native_.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                command.add(token);
            }
        }
        if (passthrough != null) {
            command.addAll(passthrough);
        }

        Path cwd = ext.rootPath();
        try {
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .inheritIO()
                    .start();
            return process.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("could not start " + ext.execPath() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it; a sweep is exactly the thing someone
            // ctrl-c's, and the caller needs to see that it was interrupted.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + ext.getName(), e);
        }
    }

    /**
     * Best-effort dispatch for the compounding loop -- never fails the caller.
     *
     * <p>Used when an AI-assisted command files its own output into a {@code kb} extension. The
     * knowledge capture is a side effect of doing the work; if the archive is unreachable, the work
     * itself still succeeded and must still be reported. So this reports the problem and returns
     * false rather than turning a completed review into a failed command.
     */
    public static boolean tryRun(Extension ext, String portableVerb, List<String> passthrough) {
        try {
            return run(ext, portableVerb, passthrough) == 0;
        } catch (RuntimeException e) {
            System.err.println("  ! " + ext.getName() + " " + portableVerb + " failed: " + e.getMessage());
            System.err.println("    The work above is unaffected; only the archive step was skipped.");
            return false;
        }
    }

    /** Whether the extension's executable is still where the registry says it is. */
    public static boolean isReachable(Extension ext) {
        return java.nio.file.Files.isExecutable(ext.execPath());
    }
}
