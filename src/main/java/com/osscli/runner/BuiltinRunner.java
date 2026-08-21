/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.osscli.runner;

import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import com.osscli.memory.BuiltinMemory.Check;
import com.osscli.profile.Toolchain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A runner that is always there.
 *
 * <p>{@code oss memory} has been built in since a fresh install could not keep a note without
 * cloning a second repository first. {@code oss run} was the other half of that promise and never
 * got it: with no extension attached and no pack written, every verb ended at
 * <em>"error no pack in …"</em> — the tool refusing to do anything at all until the user had
 * authored a file whose format they had not read yet.
 *
 * <p>That is backwards for the same reason it was backwards for memory. So the floor is this: the
 * core reads what the directory already declares — {@code pom.xml}, {@code build.gradle},
 * {@code package.json}, {@code go.mod}, {@code Cargo.toml}, a {@code Makefile} — and can say what
 * this project is, run its own build and its own tests, check that the pieces are reachable, and
 * write the starter pack that unlocks the matrix engine.
 *
 * <p><b>What it deliberately does not do.</b> It does not walk a version × config × app matrix.
 * That needs a pack, because only the person maintaining a project knows what a real application of
 * it looks like, and no amount of detection invents that. The built-in gets you to the point where
 * writing one is a five-line edit of a file the tool generated, rather than a blank page.
 *
 * <p>An attached runner extension still wins, exactly as an archive wins over the built-in memory.
 * Its absence now costs the matrix, not the whole capability.
 */
public final class BuiltinRunner {

    /**
     * What the core can run for any project, with nothing attached and no pack.
     *
     * <p>Public because it has to be shown, not only tested against — {@code oss run} with no verb
     * lists it, and {@code Surface} records it, since a passthrough verb is exactly as scriptable
     * as a flag and picocli cannot see either of them.
     *
     * <p>No name here collides with a verb of the matrix engine ({@code list}, {@code run},
     * {@code matrix}, {@code coverage}, {@code repro}, {@code pr}, {@code review}, {@code hub},
     * {@code clean}). That is not a coincidence: the dispatch rule is "a built-in verb is handled
     * here, everything else goes to the pack", and a collision would make that rule ambiguous in
     * the one direction the user cannot see.
     */
    public static final List<String> VERBS = List.of("detect", "init", "build", "test", "doctor");

    private BuiltinRunner() {}

    /** Dispatch a verb. Returns a process exit code. */
    public static int run(String verb, List<String> args) {
        try {
            switch (verb == null ? "" : verb) {
                case "detect":
                    return detect(where(args));
                case "init":
                    return init(where(args));
                case "build":
                    return execute("build", args);
                case "test":
                    return execute("test", args);
                case "doctor":
                    return doctor(where(args));
                default:
                    System.err.println("error  the built-in runner has no verb \"" + verb + "\"");
                    System.err.println("       it knows: " + String.join(", ", VERBS));
                    System.err.println("       Anything beyond that is a pack: oss run --pack <dir> list");
                    return 2;
            }
        } catch (IOException e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("error  interrupted");
            return 1;
        }
    }

    /** The verbs the core can answer, so a caller can ask before dispatching elsewhere. */
    public static boolean supports(String verb) {
        return VERBS.contains(verb);
    }

    /**
     * The directory to work on: the first non-flag argument, or where the user is standing.
     *
     * <p>Flags are skipped rather than read, because everything after the verb belongs to the
     * command being run. {@code oss run test -Dtest=Foo} must not treat {@code -Dtest=Foo} as a
     * directory and then report that it does not exist.
     */
    private static Path where(List<String> args) {
        for (String a : args) {
            if (!a.startsWith("-")) {
                Path p = Path.of(a);
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        }
        return Path.of(System.getProperty("user.dir", "."));
    }

    // -------------------------------------------------------------------- detect ---

    /** What this directory is, and what could therefore be run in it. */
    private static int detect(Path dir) throws IOException {
        System.out.println();
        System.out.println("  " + dir.toAbsolutePath());
        System.out.println("  ─────────────────────────────────────────────────────────────");

        List<Project> found = Project.detectAll(dir);
        if (found.isEmpty()) {
            System.out.println("  No build system declared here.");
            System.out.println();
            System.out.println("  Looked for: pom.xml, build.gradle(.kts), package.json,");
            System.out.println("              pyproject.toml, go.mod, Cargo.toml, Makefile");
            System.out.println();
            System.out.println("  This is not a failure — it is a directory the core cannot build.");
            System.out.println("  A pack describes what to run when the project itself does not:");
            System.out.println("    oss run init          write one from what is here");
            System.out.println("    oss run --pack <dir> list");
            return 0;
        }

        for (int i = 0; i < found.size(); i++) {
            Project p = found.get(i);
            // The first is the one everything uses. Saying so beats letting a reader guess which of
            // two detections `oss run test` is about to act on.
            System.out.printf(
                    "  %-8s %-18s %s%n",
                    p.tool().lower(), p.evidence(), i == 0 ? "← build and test use this" : "also present");
            System.out.println("           " + describe(p.buildCommand(), "build"));
            System.out.println("           " + describe(p.testCommand(), "test"));
            System.out.println("           "
                    + (p.reachable()
                            ? "reachable: " + p.launcher()
                            : "NOT INSTALLED: " + p.launcher() + " is not on the PATH"));
        }

        Toolchain.Finding declared = declaredToolchain(dir);
        if (declared != null) {
            System.out.println();
            System.out.printf("  toolchain  %s  (%s)%n", declared.describe(), declared.source());
        }

        System.out.println();
        Optional<PackFile> pack = PackFile.find(dir);
        System.out.println(
                pack.isPresent()
                        ? "  pack       yes — the matrix engine can run here: oss run list"
                        : "  pack       none — oss run init writes a starter one");
        return 0;
    }

    private static String describe(List<String> command, String what) {
        // Padded so the two lines line up under each other. `build:` and `test:` are different
        // lengths, and a ragged left edge in a three-line report reads as a formatting bug.
        return String.format(
                "%-7s %s", what + ":", command.isEmpty() ? "not declared by this project" : String.join(" ", command));
    }

    // ---------------------------------------------------------------------- init ---

    /**
     * Write a starter pack from what is already here.
     *
     * <p>Written as {@code pack.md} rather than {@code pack.json} on purpose. The format allows
     * either, and the markdown form is the one that can explain each field beside it — which is the
     * whole reason it exists and, until now, the form nothing in this repository actually produced.
     *
     * <p>It never overwrites. A pack is somebody's description of their own applications; replacing
     * one from a template would throw away the only part the tool could not have written.
     */
    private static int init(Path dir) throws IOException {
        Optional<PackFile> existing = PackFile.find(dir);
        if (existing.isPresent() || Files.isRegularFile(dir.resolve("pack.sh"))) {
            System.err.println("error  there is already a pack in " + dir.toAbsolutePath());
            System.err.println("       Refusing to overwrite it — that file describes your applications,");
            System.err.println("       and a template could not put back what it replaced.");
            return 2;
        }

        String name = dir.toAbsolutePath().getFileName().toString();
        List<Project> found = Project.detectAll(dir);
        String tool = found.isEmpty() ? "unknown" : found.get(0).tool().lower();
        String evidence = found.isEmpty() ? null : found.get(0).evidence();
        String repository = originRepository(dir);
        List<String> apps = appsUnder(dir);

        Path file = dir.resolve("pack.md");
        Files.writeString(file, starter(name, tool, evidence, repository, apps), StandardCharsets.UTF_8);

        System.out.println("  wrote " + file);
        System.out.println();
        System.out.printf("  name         %s%n", name);
        System.out.printf("  build        %s%n", tool);
        System.out.printf("  repository   %s%n", repository == null ? "(add it — useWhen.repository)" : repository);
        System.out.printf(
                "  apps         %s%n",
                apps.isEmpty() ? "(none found under apps/ — list yours)" : String.join(", ", apps));
        System.out.println();
        System.out.println("  Edit the versions and the apps — those are yours and could not be detected.");
        System.out.println("  Then: oss run list");
        return 0;
    }

    /**
     * The starter pack, with prose.
     *
     * <p>Every value that could be read from the directory is filled in; every value that is a
     * judgement about the project is left as an obvious placeholder with a sentence saying what it
     * means. A generated file that silently guesses versions would be worse than a blank one — it
     * would be run before it was read.
     */
    private static String starter(String name, String tool, String evidence, String repository, List<String> apps) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(name).append(" — pack\n\n");
        md.append("What `oss run` executes for this project. Written by `oss run init` from what was\n");
        md.append("already in this directory; the parts it could not know are marked below.\n\n");
        md.append("- **versions** — the releases you want the matrix walked across. Nothing can detect\n");
        md.append("  these: they are the versions *you* care about comparing, not the ones on disk.\n");
        md.append("- **apps** — the real applications that exercise the project. One directory each\n");
        md.append("  under `appsDir`.\n");
        md.append("- **useWhen** — when this pack applies, so the tool can find it instead of being\n");
        md.append("  told. A pack that claims nothing is never picked automatically.\n\n");
        md.append("```json\n{\n");
        md.append("  \"name\": \"").append(name).append("\",\n");
        md.append("  \"description\": \"")
                .append(name)
                .append(" across a version x config x app matrix (")
                .append(tool)
                .append(")\",\n");
        md.append("  \"useWhen\": {\n");
        md.append("    \"repository\": \"")
                .append(repository == null ? "owner/name" : repository)
                .append("\",\n");
        md.append("    \"files\": [\"")
                .append(evidence == null ? "pom.xml" : evidence)
                .append("\"]\n");
        md.append("  },\n");
        md.append("  \"versions\": [\"1.0.0\"],\n");
        md.append("  \"defaultVersion\": \"1.0.0\",\n");
        md.append("  \"apps\": [");
        md.append(
                apps.isEmpty()
                        ? "\"" + name + "\""
                        : String.join(
                                ", ", apps.stream().map(a -> "\"" + a + "\"").toList()));
        md.append("],\n");
        md.append("  \"appsDir\": \"apps\",\n");
        md.append("  \"configsDir\": \"configs\",\n");
        md.append("  \"modulePath\": \"apps/{app}\"\n");
        md.append("}\n```\n");
        return md.toString();
    }

    /** Directory names under {@code apps/}, which is the layout the engine expects. */
    private static List<String> appsUnder(Path dir) {
        Path apps = dir.resolve("apps");
        if (!Files.isDirectory(apps)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> children = Files.list(apps)) {
            return children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * {@code owner/name} from the checkout's origin remote, or null.
     *
     * <p>Read from {@code .git/config} rather than by running git: this is one regex against a file
     * that is already there, and it works in a checkout whose git is not installed.
     */
    static String originRepository(Path dir) {
        Path config = dir.resolve(".git").resolve("config");
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try {
            String text = Files.readString(config, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "url\\s*=\\s*(?:https?://[^/]+/|git@[^:]+:)([^/\\s]+)/([^/\\s]+?)(?:\\.git)?\\s*$",
                            java.util.regex.Pattern.MULTILINE)
                    .matcher(text);
            return m.find() ? m.group(1) + "/" + m.group(2) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------- build and test ---

    /**
     * Run the project's own build or test command.
     *
     * <p>Its own, not one this invents. The command printed before it starts is the whole contract:
     * anyone can read it, run it by hand and get the same result, which is what keeps this from
     * being a black box that "does a build".
     */
    private static int execute(String what, List<String> args) throws IOException, InterruptedException {
        Path dir = where(args);
        Optional<Project> detected = Project.detect(dir);
        if (detected.isEmpty()) {
            System.err.println("error  nothing to " + what + " in " + dir.toAbsolutePath());
            System.err.println("       No pom.xml, build.gradle, package.json, pyproject.toml, go.mod,");
            System.err.println("       Cargo.toml or Makefile here. oss run detect says what was looked for.");
            return 2;
        }
        Project project = detected.get();
        List<String> command = "build".equals(what) ? project.buildCommand() : project.testCommand();
        if (command.isEmpty()) {
            System.err.printf(
                    "error  this %s project declares no %s command%n",
                    project.tool().lower(), what);
            System.err.println("       Read from " + project.evidence() + ". Nothing is assumed on its behalf:");
            System.err.println("       a guessed command that fails looks like a broken project.");
            return 2;
        }
        if (!project.reachable()) {
            System.err.println("error  " + project.launcher() + " is not installed, or not on this PATH");
            System.err.printf(
                    "       %s is what a %s project builds with.%n",
                    project.launcher(), project.tool().lower());
            return 2;
        }

        List<String> full = new ArrayList<>(command);
        // Everything after the verb that is not the directory belongs to the build tool --
        // `oss run test -Dtest=Foo`, `oss run build --offline`. Passed through untouched.
        for (String a : args) {
            if (a.startsWith("-")) {
                full.add(a);
            }
        }

        System.out.println("  " + String.join(" ", full));
        System.out.println("  in " + project.root().toAbsolutePath());
        System.out.println();
        long started = System.nanoTime();
        Process p = new ProcessBuilder(full)
                .directory(project.root().toFile())
                .inheritIO()
                .start();
        int code = p.waitFor();
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        System.out.println();
        System.out.printf(
                "  %s in %ds%n", code == 0 ? what + " passed" : what + " FAILED (exit " + code + ")", seconds);
        return code;
    }

    // -------------------------------------------------------------------- doctor ---

    /**
     * Whether running anything here would actually work, asked before it is tried.
     *
     * <p>The runner's half of {@code oss memory doctor}: reachability of the build tool, validity of
     * the pack, presence of the engine, and whether the JDK in front of you is the one the project
     * declares. A fresh install with no pack is a warning and exits 0 — a health check that fails
     * because you have not written a pack yet turns "nothing to do" into a red command.
     */
    private static int doctor(Path dir) {
        System.out.println();
        System.out.println("  oss run doctor");
        System.out.println("  ─────────────────────────────────────────────────────────────");
        List<Check> checks = health(dir);
        for (Check c : checks) {
            System.out.printf("  [%s] %s — %s%n", c.status().label(), c.name(), c.detail());
            if (!c.advice().isBlank()) {
                System.out.println("           " + c.advice());
            }
        }
        System.out.println("  ─────────────────────────────────────────────────────────────");
        long bad = checks.stream().filter(c -> c.status() == Check.Status.FAIL).count();
        System.out.println(bad == 0 ? "  Nothing is broken." : "  " + bad + " thing(s) need attention.");
        return 0;
    }

    /**
     * The checks, as data.
     *
     * <p>Package-private and returning the list so a test asserts on the verdicts rather than on the
     * shape of printed text — the same split {@code BuiltinMemory.health} uses, and the reason its
     * checks can be tested without capturing stdout.
     */
    static List<Check> health(Path dir) {
        List<Check> out = new ArrayList<>();

        List<Project> found = Project.detectAll(dir);
        if (found.isEmpty()) {
            out.add(new Check(
                    "project",
                    Check.Status.WARN,
                    "no build system declared in " + dir.toAbsolutePath(),
                    "oss run detect lists what was looked for"));
        } else {
            Project p = found.get(0);
            out.add(new Check("project", Check.Status.OK, p.tool().lower() + " — " + p.evidence(), ""));
            out.add(
                    p.reachable()
                            ? new Check("build tool", Check.Status.OK, p.launcher(), "")
                            : new Check(
                                    "build tool",
                                    Check.Status.FAIL,
                                    p.launcher() + " is not on the PATH",
                                    "install it, or run in a checkout that ships a wrapper"));
        }

        Toolchain.Finding declared = declaredToolchain(dir);
        if (declared != null && "java".equals(declared.tool())) {
            String running = System.getProperty("java.specification.version", "");
            boolean match = running.equals(declared.version());
            out.add(new Check(
                    "toolchain",
                    match ? Check.Status.OK : Check.Status.WARN,
                    "declares java " + declared.version() + " (" + declared.source() + "), running java " + running,
                    match ? "" : "a build can still pass here and fail on a matching JDK"));
        }

        try {
            Optional<PackFile> pack = PackFile.find(dir);
            out.add(
                    pack.isPresent()
                            ? new Check("pack", Check.Status.OK, "readable — the matrix engine can run here", "")
                            : new Check(
                                    "pack",
                                    Check.Status.WARN,
                                    "none here",
                                    "oss run init writes a starter one; the built-in verbs work without it"));
        } catch (IOException e) {
            // A pack that will not parse is the one thing here that is genuinely broken: every
            // matrix verb will fail on it, and today it only says so mid-run.
            out.add(new Check("pack", Check.Status.FAIL, e.getMessage(), "fix the file, or delete it"));
        }

        Path engine = Engine.script();
        if (!Engine.supported()) {
            out.add(new Check(
                    "engine",
                    Check.Status.WARN,
                    "the matrix engine is POSIX shell and this is Windows",
                    "run it under WSL; the built-in verbs above do not need it"));
        } else {
            out.add(
                    engine != null
                            ? new Check("engine", Check.Status.OK, engine.toString(), "")
                            : new Check(
                                    "engine",
                                    Check.Status.WARN,
                                    "not shipped beside this install",
                                    "packs cannot be walked; the built-in verbs still work"));
        }

        List<Extension> attached = ExtensionRegistry.ofKind(Extension.Kind.RUNNER);
        out.add(
                attached.isEmpty()
                        ? new Check("extension", Check.Status.OK, "none attached — the built-in answers", "")
                        : new Check(
                                "extension",
                                Check.Status.OK,
                                attached.get(0).getName() + " takes over the verbs it declares",
                                ""));
        return out;
    }

    /** The Java version this project declares, from whichever build file declares it. */
    private static Toolchain.Finding declaredToolchain(Path dir) {
        for (String file : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path p = dir.resolve(file);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                Toolchain.Finding f = Toolchain.fromBuildFile(file, Files.readString(p, StandardCharsets.UTF_8));
                if (f != null) {
                    return f;
                }
            } catch (IOException e) {
                // An unreadable build file costs the toolchain line, not the command.
            }
        }
        return null;
    }
}
