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
package com.osscli.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Reviewing by running, rather than by reading.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every layer above this one reads: the diff, the project's rules, the reviewer's own notes, and
 * then a model's opinion of all three. That is worth having and it has a ceiling, and the ceiling is
 * not the model. Asked about a pull request whose build was broken, a local model answered "tests
 * that verify public setter validation" — a true sentence about the diff, and not the thing that
 * mattered, because <em>nobody had compiled anything</em>. No model in that position could have said
 * the build fails. The fact did not exist in the text it was given.
 *
 * <p>So this does the two things a reviewer does by hand and nothing else can substitute for:
 *
 * <ol>
 *   <li><b>Build it.</b> A change that does not compile is not a change to have opinions about.
 *   <li><b>Revert the fix and re-run the tests.</b> A test that passes with the production change
 *       taken out is proving nothing, and finding that is worth more than any summary. It is also
 *       invisible to reading: the test is present, it is well written, it passes, and it covers
 *       nothing.
 * </ol>
 *
 * <h2>What it will not do</h2>
 *
 * <p>It never touches the working tree you are sitting in. Everything happens in a {@code git
 * worktree} under the system temporary directory, which is removed afterwards — the reviewer's
 * uncommitted work is not a thing to be careful with, it is a thing not to go near.
 *
 * <p>It reports what happened rather than what it means. "This test passed with the fix reverted" is
 * a fact; whether that makes the pull request unmergeable is the reviewer's call.
 */
public final class Verifier {

    /** Long enough for a cold Maven build of a large module, short enough to not hang a review. */
    private static final long BUILD_TIMEOUT_MINUTES = 20;

    private static final long TEST_TIMEOUT_MINUTES = 15;

    /**
     * Checks a full build would run that are noise here, and one that is not optional.
     *
     * <p>Formatting, licence headers and API baselines are all real gates and none of them answer
     * "does this compile and do the tests mean anything". Skipping them keeps a verification to
     * minutes; CI is where they belong.
     */
    private static final List<String> SKIPS = List.of(
            "-Dspotless.skip=true",
            "-Drat.skip=true",
            "-Dbnd.baseline.skip=true",
            "-Denforcer.skip=true",
            "-Dcyclonedx.skip=true",
            "-Dmaven.javadoc.skip=true");

    private Verifier() {}

    /** One thing that was done, and how it went. */
    public record Step(String what, Outcome outcome, String detail) {}

    public enum Outcome {
        PASSED,
        FAILED,
        SKIPPED
    }

    /**
     * What a test proved, which is not the same as whether it passed.
     *
     * <p>{@link #PROVEN} is the answer a reviewer wants: it passes with the change and fails without
     * it, so it is testing the change. {@link #PROVES_NOTHING} is the finding — it passes either
     * way, so it would have passed before the bug was fixed.
     */
    public enum Verdict {
        PROVEN,
        PROVES_NOTHING,
        FAILED_WITH_THE_FIX,
        NOT_RUN
    }

    public record TestResult(String testClass, Verdict verdict, String detail) {}

    /** Everything that happened, in the order it happened. */
    public record Report(
            boolean ran, String why, List<Step> steps, List<TestResult> tests, List<String> modules, Path worktree) {

        /** The finding, if there is one: tests that would have passed before the change. */
        public List<TestResult> provesNothing() {
            return tests.stream()
                    .filter(t -> t.verdict() == Verdict.PROVES_NOTHING)
                    .toList();
        }

        public boolean anythingProven() {
            return tests.stream().anyMatch(t -> t.verdict() == Verdict.PROVEN);
        }
    }

    /** A report for the case where verification could not be attempted at all. */
    private static Report cannot(String why) {
        return new Report(false, why, List.of(), List.of(), List.of(), null);
    }

    /**
     * Build the change, run its tests, then take the change out and run them again.
     *
     * @param clone a checkout of the repository — not modified; a worktree is made from it
     * @param headSha the pull request's head commit
     * @param baseSha the commit to revert to, or null to work it out from {@code baseRef}
     * @param baseRef the branch the pull request targets, e.g. {@code 2.x}
     * @param changedFiles every path the pull request touches
     * @param progress where to report each step as it happens, since this takes minutes
     */
    public static Report verify(
            Path clone,
            String headSha,
            String baseSha,
            String baseRef,
            List<String> changedFiles,
            Consumer<String> progress) {
        // What the change is, before what this machine has. A pull request that adds no test cannot
        // be verified anywhere, so answering "pass --clone" sends somebody to fetch a repository for
        // a check that could never have run -- the environment is only worth complaining about once
        // the change is the kind of thing that could be checked.
        List<String> mainSources =
                changedFiles.stream().filter(Verifier::isMainSource).toList();
        List<String> testClasses = testClassesOf(changedFiles);
        if (mainSources.isEmpty()) {
            return cannot("this change touches no production source, so there is nothing to revert");
        }
        if (testClasses.isEmpty()) {
            return cannot("this change adds no test, so there is nothing to prove");
        }
        if (clone == null || !Files.isDirectory(clone.resolve(".git"))) {
            return cannot("no local clone to build in — pass --clone <path>");
        }
        List<String> modules = modulesOf(changedFiles);

        Path worktree;
        try {
            worktree = Files.createTempDirectory("oss-verify-");
        } catch (IOException e) {
            return cannot("could not make a working directory: " + e.getMessage());
        }

        List<Step> steps = new ArrayList<>();
        List<TestResult> tests = new ArrayList<>();
        try {
            // What the change is measured against. The named branch has moved on since the pull
            // request was opened, so reverting to it would revert unrelated work as well and the
            // second run would be measuring somebody else's commits. The merge base is the state
            // these files were actually branched from.
            String against = baseSha != null && !baseSha.isBlank() ? baseSha : mergeBase(clone, headSha, baseRef);
            if (against == null) {
                return new Report(false, "could not work out what to revert to", steps, tests, modules, null);
            }
            progress.accept("preparing an isolated checkout of " + shortSha(headSha));
            if (!run(
                    clone,
                    steps,
                    "worktree",
                    BUILD_TIMEOUT_MINUTES,
                    "git",
                    "worktree",
                    "add",
                    "--detach",
                    worktree.toString(),
                    headSha)) {
                return new Report(false, "could not check out " + shortSha(headSha), steps, tests, modules, null);
            }

            progress.accept("building " + (modules.isEmpty() ? "the whole project" : String.join(", ", modules)));
            if (!maven(worktree, steps, "build", BUILD_TIMEOUT_MINUTES, install(modules))) {
                // A change that does not compile is not a change to have opinions about, and this
                // is the one outcome worth stopping on: everything below assumes a build.
                return new Report(true, "the change does not build", steps, tests, modules, worktree);
            }

            progress.accept("running " + String.join(", ", testClasses));
            boolean greenWithFix = maven(
                    worktree, steps, "tests with the change", TEST_TIMEOUT_MINUTES, testCommand(modules, testClasses));

            if (!greenWithFix) {
                for (String t : testClasses) {
                    tests.add(new TestResult(t, Verdict.FAILED_WITH_THE_FIX, "the branch is not green as it stands"));
                }
                return new Report(true, "the tests do not pass on the branch itself", steps, tests, modules, worktree);
            }

            progress.accept("reverting " + mainSources.size() + " production file(s) and running them again");
            // A file the change ADDS does not exist at the merge base, so `git checkout <base> --`
            // fails on it -- and fails the whole revert with it, which is what any pull request
            // introducing a new class looks like. Taking the change out means DELETING those and
            // restoring only the ones that were there before.
            RevertPlan plan = revertPlan(mainSources, path -> existedAt(worktree, against, path));
            List<String> restore = plan.restore();
            List<String> added = plan.delete();
            try {
                for (String path : added) {
                    Files.deleteIfExists(worktree.resolve(path));
                }
            } catch (IOException e) {
                steps.add(new Step("revert the change", Outcome.FAILED, String.valueOf(e.getMessage())));
                return new Report(true, "could not revert the production change", steps, tests, modules, worktree);
            }
            if (restore.isEmpty()) {
                steps.add(new Step("revert the change", Outcome.PASSED, "deleted " + added.size() + " added file(s)"));
            } else {
                List<String> checkout = new ArrayList<>(List.of("git", "checkout", against, "--"));
                checkout.addAll(restore);
                if (!run(
                        worktree, steps, "revert the change", BUILD_TIMEOUT_MINUTES, checkout.toArray(new String[0]))) {
                    return new Report(true, "could not revert the production change", steps, tests, modules, worktree);
                }
            }
            // Rebuilt, because the tests resolve the module from the local repository rather than
            // from the reactor. Without this the second run tests the first run's classes and every
            // test looks proven.
            //
            // The result is checked. It used to be discarded, and that is the worst bug this class
            // has had: when the revert removes a type the tests name, the rebuild fails, the test
            // run then fails for that reason alone, and every class was reported PROVEN -- the
            // strongest thing this tool says, said on a compile error rather than on evidence.
            if (!maven(worktree, steps, "rebuild without the change", BUILD_TIMEOUT_MINUTES, install(modules))) {
                tests.addAll(cannotBuildWithout(testClasses, !added.isEmpty()));
                return new Report(
                        true,
                        "the project does not build with the change reverted, so nothing was proven",
                        steps,
                        tests,
                        modules,
                        worktree);
            }
            boolean greenWithout = maven(
                    worktree,
                    steps,
                    "tests without the change",
                    TEST_TIMEOUT_MINUTES,
                    testCommand(modules, testClasses));

            for (String t : testClasses) {
                tests.add(
                        greenWithout
                                ? new TestResult(
                                        t, Verdict.PROVES_NOTHING, "passes with the production change reverted")
                                : new TestResult(t, Verdict.PROVEN, "fails when the production change is reverted"));
            }
            return new Report(true, null, steps, tests, modules, worktree);
        } finally {
            cleanUp(clone, worktree);
        }
    }

    /**
     * The commit a pull request branched from.
     *
     * <p>{@code git merge-base} rather than the branch tip: {@code 2.x} today contains work merged
     * after this pull request was opened, and reverting the changed files to it would revert that
     * work too — so the second run would fail for reasons that have nothing to do with the change
     * being reviewed, and every test would look proven.
     */
    /** Which production files to restore from the base, and which to delete outright. */
    record RevertPlan(List<String> restore, List<String> delete) {}

    /**
     * How to take a change out, file by file.
     *
     * <p>An edited file is restored from the merge base; an added one has to be deleted, because it
     * has nothing to be restored to. Reverting the whole set with one {@code git checkout} fails on
     * the added ones and so reverts none of them.
     */
    static RevertPlan revertPlan(List<String> mainSources, java.util.function.Predicate<String> existedAtBase) {
        List<String> restore = new ArrayList<>();
        List<String> delete = new ArrayList<>();
        for (String path : mainSources) {
            (existedAtBase.test(path) ? restore : delete).add(path);
        }
        return new RevertPlan(List.copyOf(restore), List.copyOf(delete));
    }

    /**
     * What to report when the project will not build once the change is taken out.
     *
     * <p>NOT_RUN, never PROVEN. A test that could not be compiled did not fail because it detected
     * the change; it did not run at all, and the two look identical in an exit code.
     */
    static List<TestResult> cannotBuildWithout(List<String> testClasses, boolean anyAdded) {
        String why = anyAdded
                ? "the change adds code this test names, so it cannot be built without it"
                : "the project does not build with the change reverted";
        return testClasses.stream()
                .map(t -> new TestResult(t, Verdict.NOT_RUN, why))
                .toList();
    }

    /** Removes the isolated repository, which is ours and lives beside the worktree. */
    private static void deleteTree(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A file left behind in a temp directory is not worth failing a review over.
                }
            });
        } catch (IOException ignored) {
            // Same: the directory is under the system temp directory either way.
        }
    }

    /**
     * Whether {@code path} existed at {@code commit}.
     *
     * <p>Separates a file the change edited from one it added, which are undone in opposite ways.
     */
    private static boolean existedAt(Path worktree, String commit, String path) {
        try {
            Process p = new ProcessBuilder("git", "cat-file", "-e", commit + ":" + path)
                    .directory(worktree.toFile())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(1, TimeUnit.MINUTES) && p.exitValue() == 0;
        } catch (IOException e) {
            // Unknown means "treat it as edited", which is the old behaviour: the checkout then
            // fails loudly rather than this quietly deleting a file it was unsure about.
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static String mergeBase(Path clone, String headSha, String baseRef) {
        for (String ref : List.of("origin/" + baseRef, baseRef)) {
            try {
                Process p = new ProcessBuilder("git", "merge-base", headSha, ref)
                        .directory(clone.toFile())
                        .redirectErrorStream(false)
                        .start();
                String out =
                        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                if (p.waitFor(1, TimeUnit.MINUTES) && p.exitValue() == 0 && !out.isBlank()) {
                    return out;
                }
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Remove the worktree, whatever happened. A temp directory left behind is a gigabyte per review. */
    private static void cleanUp(Path clone, Path worktree) {
        deleteTree(isolatedRepo(worktree));
        try {
            new ProcessBuilder("git", "worktree", "remove", "--force", worktree.toString())
                    .directory(clone.toFile())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(2, TimeUnit.MINUTES);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ what to build and run ---

    /** {@code log4j-core/src/main/java/…} is production; anything under a test source root is not. */
    public static boolean isMainSource(String path) {
        return rooted(path).contains("/src/main/") && path.endsWith(".java");
    }

    /**
     * The path with a leading slash, so a source root matches whether or not a module precedes it.
     *
     * <p>These tests were written as {@code contains("/src/main/")}, which needs a segment in front
     * of it. That holds for the multi-module repository this was built against and for no
     * single-module one, where GitHub returns {@code src/main/java/…}: {@code --verify} then found
     * no production source, reported "nothing to revert" and skipped itself. Silently, and on the
     * simpler layout — so the failure was invisible exactly where the tool is easiest to try.
     */
    private static String rooted(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * The test classes a change adds or edits, by name.
     *
     * <p>Names rather than paths because that is what a build tool takes, and only the classes the
     * change actually touched: running a module's whole suite to verify one fix turns a
     * verification into a full build, and the question here is narrow.
     */
    static List<String> testClassesOf(List<String> changedFiles) {
        Set<String> out = new LinkedHashSet<>();
        for (String path : changedFiles) {
            if (rooted(path).contains("/src/test/") && path.endsWith("Test.java")) {
                String file = path.substring(path.lastIndexOf('/') + 1);
                out.add(file.substring(0, file.length() - ".java".length()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The Maven modules a change touches, as directory names.
     *
     * <p>The first path segment, for the multi-module layout this is aimed at. A change at the root
     * yields nothing and the caller builds everything, which is slower and still correct.
     */
    static List<String> modulesOf(List<String> changedFiles) {
        Set<String> out = new LinkedHashSet<>();
        for (String path : changedFiles) {
            int slash = path.indexOf('/');
            if (slash > 0 && (path.contains("/src/main/") || path.contains("/src/test/"))) {
                out.add(path.substring(0, slash));
            }
        }
        return List.copyOf(out);
    }

    private static String[] install(List<String> modules) {
        List<String> cmd =
                new ArrayList<>(List.of("-q", "-pl", String.join(",", modules), "-am", "install", "-DskipTests"));
        cmd.addAll(SKIPS);
        return cmd.toArray(new String[0]);
    }

    private static String[] testCommand(List<String> modules, List<String> testClasses) {
        List<String> cmd = new ArrayList<>(List.of(
                "-pl",
                String.join(",", modules),
                "test",
                "-Dtest=" + String.join(",", testClasses),
                "-DfailIfNoTests=false",
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        cmd.addAll(SKIPS);
        return cmd.toArray(new String[0]);
    }

    // ------------------------------------------------------------------------------ running it ---

    private static boolean maven(Path dir, List<Step> steps, String what, long minutes, String[] args) {
        return run(dir, steps, what, minutes, mavenCommand(dir, args));
    }

    /** The build tool this checkout ships, preferred over whatever is on the PATH. */
    private static String[] mavenCommand(Path dir, String[] args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(wrapperOrMaven(dir));
        // The verification INSTALLS the modules it builds, twice -- once with the change and once
        // with it reverted. Into the user's own ~/.m2 that is a quiet disaster: the second install
        // is the reverted build, so their local repository ends up holding a SNAPSHOT that is
        // neither the pull request nor its base, under a version number that means something else.
        // Any later local build resolving that version silently gets it.
        //
        // A bare -Dmaven.repo.local would isolate the writes and re-download the world. The split
        // local repository keeps the user's cache as a read-only tail, so resolution still hits it
        // and nothing is written back.
        cmd.add("-Dmaven.repo.local=" + isolatedRepo(dir));
        cmd.add("-Dmaven.repo.local.tail=" + Path.of(System.getProperty("user.home"), ".m2", "repository"));
        cmd.addAll(List.of(args));
        return cmd.toArray(new String[0]);
    }

    /**
     * The wrapper this checkout ships, or Maven from the PATH.
     *
     * <p>{@code mvnw.cmd} on Windows: there is no extensionless wrapper there, so looking only for
     * {@code mvnw} silently falls through to whatever {@code mvn} the PATH offers -- a different
     * Maven from the one the project pins.
     */
    private static String wrapperOrMaven(Path dir) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        for (String name : windows ? List.of("mvnw.cmd", "mvnw.bat") : List.of("mvnw")) {
            Path wrapper = dir.resolve(name);
            if (Files.isExecutable(wrapper)) {
                return wrapper.toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    /** Where this verification's installs go, beside the throwaway worktree and removed with it. */
    static Path isolatedRepo(Path worktree) {
        return Path.of(worktree.toString() + "-m2");
    }

    /** What a command did: whether it succeeded, and everything it printed. */
    private record Ran(boolean ok, String output) {}

    private static boolean run(Path dir, List<Step> steps, String what, long minutes, String... command) {
        return ran(dir, steps, what, minutes, command).ok();
    }

    private static Ran ran(Path dir, List<Step> steps, String what, long minutes, String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                steps.add(new Step(what, Outcome.FAILED, "gave up after " + minutes + " minutes"));
                return new Ran(false, output);
            }
            boolean ok = p.exitValue() == 0;
            steps.add(new Step(what, ok ? Outcome.PASSED : Outcome.FAILED, ok ? "" : lastMeaningfulLine(output)));
            return new Ran(ok, output);
        } catch (IOException e) {
            steps.add(new Step(what, Outcome.FAILED, e.getMessage()));
            return new Ran(false, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            steps.add(new Step(what, Outcome.FAILED, "interrupted"));
            return new Ran(false, "");
        }
    }

    /**
     * Whether one named test class passed, according to the run's own report.
     *
     * <p>Per class, not per command. The first version decided from the exit code of a single Maven
     * invocation covering every test class at once, so three classes shared one verdict: if any one
     * of them failed, all three were reported as proven. Worse, a class that did not run at all --
     * because its module was not in the reactor -- was reported as proven too, on the strength of
     * another class's failure.
     *
     * <p>Surefire prints one summary line per class, and that is what is read.
     */
    static Verdict verdictFor(String testClass, String outputWithoutTheChange) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)[^\\n]*?-- in \\S*\\b"
                                + java.util.regex.Pattern.quote(testClass) + "\\b")
                .matcher(outputWithoutTheChange);
        if (!m.find()) {
            // Never reported means never executed. Silence is not evidence either way, and calling
            // it proven would be the most flattering possible reading of nothing happening.
            return Verdict.NOT_RUN;
        }
        int failures = Integer.parseInt(m.group(2));
        int errors = Integer.parseInt(m.group(3));
        return failures + errors > 0 ? Verdict.PROVEN : Verdict.PROVES_NOTHING;
    }

    /**
     * The line worth showing from a failed build.
     *
     * <p>Maven's last line is usually "For more information about the errors…", which is true of
     * every failure and says nothing about this one. The first line that names an error or a failed
     * test is the one a person would have looked for.
     */
    static String lastMeaningfulLine(String output) {
        String best = "";
        for (String line : output.split("\n")) {
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("tests run:") && lower.contains("failures:")) {
                best = trimmed;
            } else if (best.isEmpty() && (lower.startsWith("[error]") || lower.contains("build failure"))) {
                best = trimmed;
            }
        }
        return best;
    }

    private static String shortSha(String sha) {
        return sha == null ? "(unknown)" : sha.substring(0, Math.min(8, sha.length()));
    }
}
