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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a directory builds with, read from the files that are already there.
 *
 * <p>The core cannot execute somebody's application — only they know what a real run of it looks
 * like, which is why a pack exists. But every project already declares how it builds and tests
 * itself, in a file with a well-known name, and reading that is the same work for every project.
 * So it belongs here rather than in a pack somebody has to write first.
 *
 * <p>Detection is by file, never by guess. A directory with no {@code pom.xml} is not "probably
 * Maven"; it is a directory this cannot help with, and saying so is the useful answer.
 *
 * <p>The wrapper a checkout ships beats whatever is on the PATH, for the reason
 * {@code Verifier} learned it: {@code mvnw} pins a Maven version and {@code mvn} is whatever the
 * machine happens to have. On Windows the extensionless wrapper does not exist, so looking only for
 * {@code mvnw} silently falls through to a different build than the project pinned.
 */
public final class Project {

    /** The build systems this can read. Order is the order they are looked for. */
    public enum Tool {
        MAVEN("maven"),
        GRADLE("gradle"),
        NODE("node"),
        PYTHON("python"),
        GO("go"),
        RUST("rust"),
        MAKE("make");

        private final String lower;

        Tool(String lower) {
            this.lower = lower;
        }

        public String lower() {
            return lower;
        }
    }

    private final Tool tool;
    private final Path root;
    private final String evidence;
    private final String launcher;
    private final List<String> buildArgs;
    private final List<String> testArgs;

    private Project(
            Tool tool, Path root, String evidence, String launcher, List<String> buildArgs, List<String> testArgs) {
        this.tool = tool;
        this.root = root;
        this.evidence = evidence;
        this.launcher = launcher;
        this.buildArgs = List.copyOf(buildArgs);
        this.testArgs = List.copyOf(testArgs);
    }

    public Tool tool() {
        return tool;
    }

    public Path root() {
        return root;
    }

    /** The file that identified this project, which is what makes the detection arguable. */
    public String evidence() {
        return evidence;
    }

    /** The executable: the checkout's own wrapper when it ships one, otherwise the PATH's. */
    public String launcher() {
        return launcher;
    }

    /** Empty when this ecosystem has no build step worth naming, which is not a failure. */
    public List<String> buildCommand() {
        return command(buildArgs);
    }

    /** Empty when the project declares no test command — {@code npm test} with no test script. */
    public List<String> testCommand() {
        return command(testArgs);
    }

    private List<String> command(List<String> args) {
        if (args.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(launcher);
        out.addAll(args);
        return List.copyOf(out);
    }

    /**
     * Whether the launcher can actually be started.
     *
     * <p>Checked without spawning anything: a wrapper is a file in the checkout, and everything else
     * is a PATH lookup. "maven is not installed" said before the run beats a stack trace from
     * {@link ProcessBuilder} half a second later.
     */
    public boolean reachable() {
        if (launcher.contains("/") || launcher.contains("\\")) {
            return Files.isExecutable(Path.of(launcher));
        }
        return onPath(launcher) != null;
    }

    /** Where an executable resolves on this machine's PATH, or null. */
    public static Path onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        // The suffixes matter only on Windows, where `pytest` and `gradle` are on the PATH as
        // pytest.exe and gradle.bat. Looking for the bare name there reports every tool as missing.
        List<String> suffixes = windows() ? List.of("", ".exe", ".cmd", ".bat") : List.of("");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String suffix : suffixes) {
                Path candidate = Path.of(dir).resolve(executable + suffix);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The first build system this directory declares, or empty when it declares none. */
    public static Optional<Project> detect(Path dir) {
        List<Project> all = detectAll(dir);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Every build system this directory declares, in precedence order.
     *
     * <p>Plural because real repositories carry more than one — a Maven project with a Makefile of
     * convenience targets, a Go service with a {@code package.json} for its docs site. Reporting
     * only the winner makes the tool look like it missed the other, so {@code detect} shows them
     * all and everything that runs uses the first.
     */
    public static List<Project> detectAll(Path dir) {
        List<Project> found = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return found;
        }
        boolean windows = windows();

        if (Files.isRegularFile(dir.resolve("pom.xml"))) {
            found.add(new Project(
                    Tool.MAVEN,
                    dir,
                    "pom.xml",
                    wrapperOr(
                            dir,
                            windows ? List.of("mvnw.cmd", "mvnw.bat") : List.of("mvnw"),
                            windows ? "mvn.cmd" : "mvn"),
                    List.of("-DskipTests", "package"),
                    List.of("test")));
        }
        for (String gradle : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            if (Files.isRegularFile(dir.resolve(gradle))) {
                found.add(new Project(
                        Tool.GRADLE,
                        dir,
                        gradle,
                        wrapperOr(dir, windows ? List.of("gradlew.bat") : List.of("gradlew"), "gradle"),
                        List.of("build", "-x", "test"),
                        List.of("test")));
                break;
            }
        }
        if (Files.isRegularFile(dir.resolve("package.json"))) {
            // Read the scripts rather than assume them. `npm run build` in a package with no build
            // script exits 1 with "Missing script", which reads as a broken project rather than as
            // a project that simply does not build that way.
            List<String> scripts = npmScripts(dir.resolve("package.json"));
            found.add(new Project(
                    Tool.NODE,
                    dir,
                    "package.json",
                    windows ? "npm.cmd" : "npm",
                    scripts.contains("build") ? List.of("run", "build") : List.of(),
                    scripts.contains("test") ? List.of("test") : List.of()));
        }
        for (String python : List.of("pyproject.toml", "tox.ini", "setup.py", "setup.cfg")) {
            if (Files.isRegularFile(dir.resolve(python))) {
                // No build step: a Python project's "build" is a wheel nobody asked for here, and
                // running one would be the tool acting beyond what was typed.
                found.add(new Project(Tool.PYTHON, dir, python, "pytest", List.of(), List.of("-q")));
                break;
            }
        }
        if (Files.isRegularFile(dir.resolve("go.mod"))) {
            found.add(new Project(Tool.GO, dir, "go.mod", "go", List.of("build", "./..."), List.of("test", "./...")));
        }
        if (Files.isRegularFile(dir.resolve("Cargo.toml"))) {
            found.add(new Project(Tool.RUST, dir, "Cargo.toml", "cargo", List.of("build"), List.of("test")));
        }
        for (String make : List.of("Makefile", "makefile", "GNUmakefile")) {
            Path file = dir.resolve(make);
            if (Files.isRegularFile(file)) {
                List<String> targets = makeTargets(file);
                found.add(new Project(
                        Tool.MAKE,
                        dir,
                        make,
                        "make",
                        targets.contains("build") ? List.of("build") : List.of(),
                        targets.contains("test") ? List.of("test") : List.of()));
                break;
            }
        }
        return found;
    }

    /**
     * The wrapper this checkout ships, or the name to look for on the PATH.
     *
     * <p>Shared with {@code Verifier}, which had this logic first and privately. Two copies of
     * "which Maven does this project mean" is exactly the kind of second copy that has cost this
     * repository a bug before: one of them gets the Windows spelling and the other does not.
     */
    public static String wrapperOr(Path dir, List<String> wrapperNames, String fallback) {
        for (String name : wrapperNames) {
            Path wrapper = dir.resolve(name);
            if (Files.isExecutable(wrapper)) {
                return wrapper.toString();
            }
        }
        return fallback;
    }

    /** Maven, the way the rest of the tool asks for it. */
    public static String maven(Path dir) {
        boolean windows = windows();
        return wrapperOr(dir, windows ? List.of("mvnw.cmd", "mvnw.bat") : List.of("mvnw"), windows ? "mvn.cmd" : "mvn");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Script names in a {@code package.json}, or nothing when it cannot be read. */
    private static List<String> npmScripts(Path packageJson) {
        try {
            JsonNode node = new ObjectMapper().readTree(Files.readString(packageJson, StandardCharsets.UTF_8));
            JsonNode scripts = node.path("scripts");
            List<String> names = new ArrayList<>();
            scripts.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException | RuntimeException e) {
            // A package.json that will not parse is still a Node project; it just cannot say what
            // it runs. Reporting no scripts is honest, and beats refusing to detect it at all.
            return List.of();
        }
    }

    /** Phony-or-not target names declared at the start of a line in a Makefile. */
    private static List<String> makeTargets(Path makefile) {
        List<String> targets = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(makefile, StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z0-9_.-]+)\\s*:(?!=)")
                        .matcher(line);
                if (m.find()) {
                    targets.add(m.group(1));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable is the same as undeclared here: nothing is offered rather than guessed.
        }
        return targets;
    }
}
