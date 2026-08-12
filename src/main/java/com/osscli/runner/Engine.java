package com.osscli.runner;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds and runs the matrix engine that ships beside this jar.
 *
 * <p>The engine walks a version × configuration × application matrix and forks a real JVM per cell.
 * It knows how to run things and nothing about what it is running — what it runs is a <b>pack</b>, a
 * directory somebody else owns holding a {@code pack.sh} and the applications that file names.
 *
 * <p>That split is the reason this lives here at all. Only the person maintaining a project knows
 * what a real application of it looks like, so the pack cannot be shipped; and walking a matrix is
 * the same work whatever the project, so the engine should not have to be written twice.
 */
public final class Engine {

    /** Sibling of {@code lib/oss.jar} in an installed tree, and of the sources in a checkout. */
    private static final String DIR = "runner";

    private static final String SCRIPT = "engine.sh";

    private Engine() {}

    /** Whether this platform can run the engine at all. */
    public static boolean supported() {
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The engine script, or null when it was not shipped with this build. */
    public static Path script() {
        for (Path root : candidateRoots()) {
            Path p = root.resolve(DIR).resolve(SCRIPT);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Run a verb against a pack.
     *
     * @param packDir the pack's root — the directory holding {@code pack.sh}
     * @param args the verb and everything after it, passed through untouched
     */
    public static int run(Path packDir, List<String> args) throws IOException, InterruptedException {
        if (!supported()) {
            System.err.println("error  the matrix engine is POSIX shell, and this is Windows.");
            System.err.println("       Run it under WSL. It forks Maven, Gradle and JVMs, so it was");
            System.err.println("       never going to work from cmd.exe -- saying so now beats failing");
            System.err.println("       halfway through a build with something confusing.");
            return 2;
        }
        Path engine = script();
        if (engine == null) {
            System.err.println("error  no engine found beside this install.");
            System.err.println("       Expected " + DIR + "/" + SCRIPT + " next to lib/oss.jar.");
            return 2;
        }
        if (packDir == null) {
            System.err.println("error  which pack? oss run --pack <dir> <verb> ...");
            System.err.println("       A pack is a directory with a pack.sh in it.");
            return 2;
        }
        if (!Files.isDirectory(packDir)) {
            System.err.println("error  no directory at " + packDir);
            return 2;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(engine.toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // The engine resolves everything -- apps, configs, the cache -- against the pack, not
        // against itself. Passing it by environment rather than as an argument keeps it out of the
        // passthrough, so a pack directory can never be mistaken for a verb's argument.
        pb.environment().put("OSS_PACK_DIR", packDir.toAbsolutePath().toString());
        pb.inheritIO();
        return pb.start().waitFor();
    }

    /**
     * Where the engine might be, in the two layouts that exist.
     *
     * <p>Installed, the jar is at {@code libexec/lib/oss.jar} and the engine at
     * {@code libexec/runner/}. In a checkout the jar is at {@code target/} and the engine at the
     * repository root. Both are two directories up from the jar, which is the only thing worth
     * relying on.
     */
    private static List<Path> candidateRoots() {
        List<Path> out = new ArrayList<>();
        try {
            CodeSource cs = Engine.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path jar = Path.of(cs.getLocation().toURI());
                Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
                if (dir != null) {
                    out.add(dir); // beside the jar
                    if (dir.getParent() != null) {
                        out.add(dir.getParent()); // libexec/, or the repo root from target/
                    }
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            // A jar that cannot say where it is only costs the search a candidate.
        }
        out.add(Path.of(System.getProperty("user.dir", ".")));
        return out;
    }
}
