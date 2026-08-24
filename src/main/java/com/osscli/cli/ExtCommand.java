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
package com.osscli.cli;

import com.osscli.ext.Attachments;
import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import com.osscli.ext.ExtensionRunner;
import com.osscli.memory.BuiltinMemory;
import com.osscli.safety.UpstreamGuard;
import com.osscli.ui.NextSteps;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Register and call the capabilities OSS-CLI does not have itself.
 *
 * <p>OSS-CLI knows: it reads any repository through the GitHub API, without a clone, in any
 * language. That boundary is deliberate and is why it generalises. The cost is two questions it
 * cannot answer alone -- <em>does this actually run?</em> and <em>have I worked this out before?</em>
 *
 * <p>An extension answers one of those. A {@code bench} executes something real; a {@code kb}
 * remembers. Both are declared by an {@code oss-ext.json} at the root of any repository and called
 * as child processes, so an extension can be written in anything and does not have to be built
 * against OSS-CLI at all.
 *
 * <pre>{@code
 * oss ext add ~/projects/your-bench      # register whatever that repo declares
 * oss ext list                           # what is wired up, and is it still reachable
 * oss run review 4234                  # dispatch to the bench extension
 * oss memory file notes.md                   # dispatch to the archive
 * }</pre>
 */
@Command(
        name = "ext",
        mixinStandardHelpOptions = true,
        description = "Attach and inspect runners and memories",
        subcommands = {ExtCommand.Add.class, ExtCommand.ListExt.class, ExtCommand.Remove.class, ExtCommand.Refresh.class
        })
public class ExtCommand implements Callable<Integer> {

    /** Bare {@code ext} lists, because that is what someone typing it blind wants to see. */
    @Override
    public Integer call() {
        return new ListExt().call();
    }

    @Command(
            name = "add",
            mixinStandardHelpOptions = true,
            description = "Register the extension declared by a repository")
    static class Add implements Callable<Integer> {

        @Parameters(index = "0", description = "Repository root containing oss-ext.json")
        Path path;

        @Override
        public Integer call() {
            try {
                Extension ext = ExtensionRegistry.readManifest(path);
                boolean replaced = ExtensionRegistry.add(ext);
                System.out.printf(
                        "  %s %s (%s) — %d verb%s: %s%n",
                        replaced ? "updated" : "registered",
                        ext.getName(),
                        ext.kind().lower(),
                        ext.getVerbs().size(),
                        ext.getVerbs().size() == 1 ? "" : "s",
                        String.join(", ", ext.getVerbs().keySet()));
                System.out.println("  root: " + ext.getRoot());
                // Attaching is the one moment someone has no idea what they just gained.
                NextSteps.suggest(NextSteps.After.ATTACH, null);
                return 0;
            } catch (RuntimeException e) {
                System.err.println("error  " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "Show every registered extension")
    static class ListExt implements Callable<Integer> {

        @Override
        public Integer call() {
            List<Extension> all = ExtensionRegistry.all();
            // What is on by default, before the list of what somebody added. "No extensions
            // registered" read as "this machine has none of this capability", which is the opposite
            // of true: both kinds answer built in and an attachment takes over the verbs it
            // declares. Saying only the second half is what made attaching look mandatory.
            builtIns();
            if (all.isEmpty()) {
                System.out.println("  Nothing attached — the built-ins above are answering.");
                System.out.println();
                System.out.println("  oss ext add <repo>     a repo with an oss-ext.json at its root");
                System.out.println("  kinds: runner (executes something real) · memory (remembers)");
                System.out.println("  \"supports\": \"owner/name\" in that manifest files it under one");
                System.out.println("                         repository you follow, instead of all of them");
                return 0;
            }

            // Grouped by subject rather than listed flat. A flat table said three runners were
            // equally applicable to fourteen repositories, which is true of none of them.
            System.out.printf("%-16s %-7s %-9s %s%n", "  NAME", "KIND", "STATE", "VERBS");
            int bare = 0;
            for (Attachments.Pack pack : Attachments.tree()) {
                if (!pack.supported()) {
                    bare++;
                    continue;
                }
                System.out.println(pack.name() + (pack.followed() ? "" : "   (not a repository you follow)"));
                for (Extension e : pack.supporters()) {
                    row(e);
                }
            }
            List<Extension> everywhere = Attachments.unattached();
            if (!everywhere.isEmpty()) {
                System.out.println("every subject   (no \"supports\" declared)");
                for (Extension e : everywhere) {
                    row(e);
                }
            }
            if (bare > 0) {
                // Counted, not listed. Hiding them would read as "these are all you follow"; naming
                // fourteen repositories with nothing attached would bury the ones that have something.
                System.out.println();
                System.out.println("  " + bare + " more repository(ies) followed, nothing attached.");
            }
            return 0;
        }

        /**
         * The capabilities that answer with nothing attached.
         *
         * <p>Both kinds are built in and on by default; an attachment takes over the verbs it
         * declares and nothing else. Printing only the attachments made the built-ins invisible,
         * and an invisible default reads as a missing feature -- the same reason {@code oss run}
         * with no pack used to look broken rather than answer.
         */
        private static void builtIns() {
            System.out.println("  BUILT IN, ON BY DEFAULT");
            System.out.printf("    %-10s %s%n", "memory", "file, search, index — an attached archive takes over");
            System.out.printf(
                    "    %-10s %s%n", "runner", "detect, init, build, test, doctor — an attached pack takes over");
            System.out.println();
        }

        /** One extension, with the state only a live check can know. */
        private static void row(Extension e) {
            boolean ok = ExtensionRunner.isReachable(e);
            boolean stale = ExtensionRegistry.isStale(e);
            System.out.printf(
                    "  └─ %-13s %-7s %-9s %s%n",
                    e.getName(),
                    e.kind().lower(),
                    !ok ? "MISSING" : stale ? "STALE" : "ok",
                    String.join(", ", e.getVerbs().keySet()));
            if (!ok) {
                // Naming the path it expected is the difference between "something is wrong"
                // and a one-line fix; a moved checkout is the common cause.
                System.out.println("     expected: " + e.execPath());
            }
            if (stale) {
                System.out.println("     " + Extension.MANIFEST
                        + " changed on disk since it was registered — oss ext refresh " + e.getName());
            }
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Unregister an extension")
    static class Remove implements Callable<Integer> {

        @Parameters(index = "0", description = "Extension name")
        String name;

        @Override
        public Integer call() {
            if (ExtensionRegistry.remove(name)) {
                System.out.println("  removed " + name);
                return 0;
            }
            System.err.println("error  no extension named \"" + name + "\"");
            return 1;
        }
    }

    @Command(
            name = "refresh",
            mixinStandardHelpOptions = true,
            description = "Re-read a registered extension's manifest from disk")
    static class Refresh implements Callable<Integer> {

        @Parameters(index = "0", description = "Extension name")
        String name;

        @Override
        public Integer call() {
            try {
                Extension ext = ExtensionRegistry.refresh(name);
                System.out.printf(
                        "  refreshed %s — %s%n",
                        ext.getName(), String.join(", ", ext.getVerbs().keySet()));
                return 0;
            } catch (RuntimeException e) {
                System.err.println("error  " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * Shared implementation of the two dispatching aliases.
     *
     * <p>{@code oss run <verb> …} reads better than {@code oss ext run --kind bench …} and
     * is what the motto asks for -- OSS-CLI mingling with a bench and a knowledge base as first
     * class capabilities rather than as a generic escape hatch.
     */
    abstract static class Dispatch implements Callable<Integer> {

        @Option(names = "--name", description = "Which extension, when more than one of this kind is registered")
        String name;

        // Declared here, on the dispatcher, so it is consumed before the passthrough and can never
        // be forwarded to the extension by accident -- an approval that reached the child as an
        // ordinary argument would be an approval the child could act on unsupervised.
        @Option(
                names = UpstreamGuard.APPROVE_FLAG,
                paramLabel = "owner/name",
                description = "Permit ONE outward write to exactly this repository. Still confirmed at the terminal.")
        String approveUpstream;

        // Optional, because the answer to "what can I type here" has to come from the tool
        // rather than from a person who already knows. Missing it used to print picocli's
        // "Missing required parameter: '<verb>'" over a usage block naming EXAMPLE verbs --
        // `run, matrix, review, file, index, search` -- none of which are read from the
        // extension that is actually attached. Someone typing `oss memory` blind was told to
        // try verbs their archive may not declare, and never told about the ones it does.
        @Parameters(index = "0", arity = "0..1", description = "Verb to dispatch. Omit to list what is available.")
        String verb;

        @Parameters(index = "1..*", description = "Arguments passed through untouched")
        List<String> passthrough = List.of();

        abstract Extension.Kind kind();

        /** The name a reader would type, for the examples printed by {@link #discover()}. */
        abstract String label();

        /** What the core itself can do for this kind when nothing is attached. */
        List<String> builtinVerbs() {
            return List.of();
        }

        /** Anything else worth saying when nothing of this kind is registered. */
        void whenNothingAttached() {}

        /**
         * What can be typed here, read from what is actually attached.
         *
         * <p>Deliberately not usage text. Usage describes this command's own grammar; the useful
         * answer is the verb list of a child process registered on this machine, which picocli
         * cannot know. Exit 0 -- asking what is available is a fair question, not a mistake.
         */
        Integer discover() {
            List<Extension> attached = ExtensionRegistry.ofKind(kind());
            if (attached.isEmpty()) {
                if (!builtinVerbs().isEmpty()) {
                    System.out.println("  built-in " + kind().lower() + ", nothing attached:");
                    for (String v : builtinVerbs()) {
                        System.out.println("    oss " + label() + " " + v);
                    }
                    System.out.println();
                }
                whenNothingAttached();
                System.out.println("  oss ext add <repo>     a repo with an oss-ext.json at its root");
                return 0;
            }
            for (Extension e : attached) {
                System.out.printf("  %s (%s) — %s%n", e.getName(), e.kind().lower(), e.getDescription());
                System.out.println("  " + e.getRoot());
                if (ExtensionRegistry.isStale(e)) {
                    // The dispatcher refuses every verb while stale, so saying it here saves
                    // finding that out one command later.
                    System.out.println(
                            "  stale — " + Extension.MANIFEST + " changed on disk: oss ext refresh " + e.getName());
                }
                System.out.println();
                for (String v : e.getVerbs().keySet()) {
                    System.out.println(
                            "    oss " + label() + " " + v + (e.writesOutward(v) ? "   (writes outward)" : ""));
                }
                System.out.println();
            }
            for (String v : builtinVerbs()) {
                // Listed after, and labelled: these keep answering when the archive is
                // unreachable, which is exactly when someone is looking for them.
                System.out.println("    oss " + label() + " " + v + "   (built in, always available)");
            }
            return 0;
        }

        /**
         * What to do when no extension of this kind is registered.
         *
         * <p>Null means "there is nothing to fall back to", which is the honest answer for a runner:
         * nothing in the core can execute somebody's project for them. A memory can fall back,
         * because a folder of markdown is a real answer.
         */
        /**
         * What to do when an extension is attached but does not declare this verb.
         *
         * <p>Distinct from {@link #fallback}, which is for no extension at all. Null means the
         * built-in cannot help either, and the caller should say so.
         */
        Integer whenExtensionCannot(String verb, List<String> args) {
            return null;
        }

        Integer fallback(String verb, List<String> args) {
            return null;
        }

        /** True when this invocation names the built-in path explicitly. */
        boolean preferFallback() {
            return false;
        }

        /** Anything the core should record even when an extension handles the verb. */
        void alsoLocally(String verb, List<String> args) {}

        @Override
        public Integer call() {
            try {
                if (verb == null) {
                    return discover();
                }
                // An explicit choice wins over a registered extension: someone who typed
                // --pack said which one they meant, and silently dispatching elsewhere because
                // an extension happens to be attached would be the wrong kind of helpful.
                if (preferFallback()
                        || (name == null && ExtensionRegistry.ofKind(kind()).isEmpty())) {
                    Integer handled = fallback(verb, passthrough);
                    if (handled != null) {
                        return handled;
                    }
                }
                Extension ext = ExtensionRegistry.resolve(kind(), name);

                // A stale snapshot silently broke an approval once: writesTo was corrected on
                // disk while the registry kept the old value, so an approval naming the right
                // repository could never match, and the verb was simply unusable.
                //
                // Refuse ALL dispatch while stale, not just verbs the snapshot calls writes. The
                // snapshot is exactly what cannot be trusted here, and it can under-report: a first
                // attempt at this warned-and-ran when the stored copy said "writes: []" while the
                // file on disk had begun declaring that same verb an outward write. Deciding
                // safety from the stale copy is the bug, not a smaller version of it. One command
                // clears it, and refusing is the only answer that cannot be wrong in the dangerous
                // direction.
                if (ExtensionRegistry.isStale(ext)) {
                    System.err.println("error  refused — " + ext.getName() + "'s " + Extension.MANIFEST
                            + " changed on disk since it was registered.");
                    System.err.println("       What it declares — including which verbs write "
                            + "outward — may no longer be what is recorded.");
                    System.err.println("       oss ext refresh " + ext.getName());
                    return 2;
                }

                // Gate before starting the process, not inside it. Once the child is running the
                // post has already left; the only useful place to stop is here.
                if (ext.writesOutward(verb)) {
                    String approved = UpstreamGuard.normaliseRepo(approveUpstream);
                    if (!UpstreamGuard.allow(ext.getName() + " " + verb, ext.writeTarget(), approved)) {
                        return 2;
                    }
                }
                // An attached extension that does not declare this verb should cost the verb's
                // richer form, not the verb. `oss memory file` prints "oss memory search" as its
                // own next step, and with devon attached that suggestion was refused -- the tool
                // advertising a command it then rejects. The built-in still holds the local
                // working copies, so it can answer; it just answers about less.
                if (ext.resolveVerb(verb) == null) {
                    Integer handled = whenExtensionCannot(verb, passthrough);
                    if (handled != null) {
                        return handled;
                    }
                    System.err.println(
                            "error  \"" + ext.getName() + "\" does not offer \"" + verb + "\" -- it declares: "
                                    + String.join(", ", ext.getVerbs().keySet()));
                    return 1;
                }

                int code = ExtensionRunner.run(ext, verb, passthrough);
                if (code == 0) {
                    alsoLocally(verb, passthrough);
                }
                return code;
            } catch (RuntimeException e) {
                System.err.println("error  " + e.getMessage());
                return 1;
            }
        }
    }

    // stopAtPositional is what makes this a dispatcher rather than a parser. Without it picocli
    // claims any flag it recognises out of the passthrough -- `oss run list --apps` printed
    // this command's own usage, because --apps was unknown HERE and never reached the bench. After
    // the verb, nothing is ours.
    // Renamed, with the old spelling kept as an alias. Anything already in a script, a note or
    // muscle memory keeps working; renaming a verb is not worth breaking someone's Tuesday.
    @Command(
            name = "run",
            aliases = {"bench"},
            mixinStandardHelpOptions = true,
            description = "Run something real through an attached runner")
    public static class BenchDispatch extends Dispatch {

        @Option(names = "--pack", description = "The pack to run: a directory containing pack.sh")
        java.nio.file.Path pack;

        // Declared here rather than on Dispatch: a memory verb is not about a pull request, and an
        // option that means nothing on `oss memory` should not appear in its help.
        @Option(names = "--pr", description = "Record this run against a pull request, so review and the board see it")
        Integer pr;

        @Option(
                names = "--repo",
                paramLabel = "owner/name",
                description = "Which repository the --pr belongs to (default: the one you follow, if only one)")
        String repo;

        /**
         * Run the verb, then remember what it found and which code it found it on.
         *
         * <p>The result outlives the terminal, which is the whole point: {@code review} consults it
         * before any model, so a question the runner has already answered costs nothing to answer
         * again. Recorded whatever the exit code — a failing build is the more useful of the two
         * results, and a ledger that kept only successes would be an advertisement.
         *
         * <p>Recording never changes the exit code. The verb's result is the command's result; a
         * ledger that could turn a green run red by failing to write itself would be a ledger
         * nobody could trust the absence of.
         */
        @Override
        public Integer call() {
            Integer code = super.call();
            if (pr == null || verb == null) {
                return code;
            }
            try {
                com.osscli.bench.BenchRecorder.record(repo, pr, verb, code == null ? 1 : code, chosenRunner());
            } catch (RuntimeException e) {
                System.err.println("  (the run stands; it was not recorded: " + e.getMessage() + ")");
            }
            return code;
        }

        /** The extension that answered, or the built-in engine. */
        private String chosenRunner() {
            if (pack != null) {
                return "pack:" + pack.getFileName();
            }
            try {
                java.util.List<Extension> attached = ExtensionRegistry.ofKind(kind());
                return attached.isEmpty() ? "built-in" : attached.get(0).getName();
            } catch (RuntimeException e) {
                return "built-in";
            }
        }

        @Override
        Extension.Kind kind() {
            return Extension.Kind.RUNNER;
        }

        @Override
        String label() {
            return "run";
        }

        /**
         * An empty registry is not the end of the road here.
         *
         * <p>The common case turned out not to be a repository that drives itself, but a
         * description of applications and versions — a pack — walked by the engine that ships
         * inside. Saying only "attach an extension" would hide the route most people want.
         */
        @Override
        List<String> builtinVerbs() {
            return com.osscli.runner.BuiltinRunner.VERBS;
        }

        @Override
        void whenNothingAttached() {
            System.out.println("  and the built-in engine runs a pack directly:");
            System.out.println("    oss run --pack <dir> list");
            System.out.println();
        }

        @Override
        boolean preferFallback() {
            return pack != null;
        }

        /**
         * Run the built-in engine against a pack.
         *
         * <p>A runner extension is still how you attach a repository that drives itself. This is for
         * the other case, which turned out to be the common one: the thing you want to run is not a
         * program at all, it is a description of your applications and versions. The engine that
         * walks them is the same work for every project, so it ships here.
         */
        @Override
        Integer fallback(String verb, List<String> args) {
            // A built-in verb is answered here, whether or not a pack is present -- these are the
            // verbs the core can do for ANY project, and none of them is a verb of the engine, so
            // nothing is being shadowed. `--pack` is not an exception: it names where to look, and
            // `oss run --pack <dir> doctor` is a fair question about that directory.
            if (com.osscli.runner.BuiltinRunner.supports(verb)) {
                java.util.List<String> withPack = new java.util.ArrayList<>();
                if (pack != null) {
                    withPack.add(pack.toString());
                }
                withPack.addAll(args);
                return com.osscli.runner.BuiltinRunner.run(verb, withPack);
            }
            java.util.List<String> all = new java.util.ArrayList<>();
            all.add(verb);
            all.addAll(args);
            try {
                return com.osscli.runner.Engine.run(pack, all);
            } catch (Exception e) {
                System.err.println("error  " + e.getMessage());
                return 1;
            }
        }

        /**
         * The attached runner does not do this one, but the core might.
         *
         * <p>The same rule the archive already follows: attaching a bench should cost the richer
         * form of a verb, never the verb. A bench that declares {@code matrix} and {@code repro}
         * has no reason to take {@code oss run doctor} away from a user who has one attached.
         */
        @Override
        Integer whenExtensionCannot(String verb, List<String> args) {
            if (!com.osscli.runner.BuiltinRunner.supports(verb)) {
                return null;
            }
            System.out.println("  the attached runner does not do \"" + verb + "\" — answering from the core instead");
            return com.osscli.runner.BuiltinRunner.run(verb, args);
        }
    }

    @Command(
            name = "memory",
            aliases = {"kb"},
            mixinStandardHelpOptions = true,
            description = "File, index or search through your memory (built in; an extension takes over)")
    public static class KbDispatch extends Dispatch {
        @Override
        Extension.Kind kind() {
            return Extension.Kind.MEMORY;
        }

        @Override
        String label() {
            return "memory";
        }

        @Override
        List<String> builtinVerbs() {
            return BuiltinMemory.VERBS;
        }

        /**
         * Fall back to the built-in memory when no archive is attached.
         *
         * <p>Requiring an extension before anything could be remembered made the most useful thing
         * here the one thing a new install could not do. An attached archive still wins — it is a
         * richer place to put a note than a folder of markdown — but its absence now costs features
         * rather than the whole capability.
         */
        @Override
        Integer fallback(String verb, List<String> args) {
            System.out.println("  built-in memory (no memory extension attached)");
            return BuiltinMemory.run(verb, args);
        }

        /**
         * The archive does not do this one, but the built-in store might.
         *
         * <p>`oss memory file` ends by printing `oss memory search "<terms>"`, and with an archive
         * attached that suggestion was refused -- the tool advertising a command it then rejects.
         * The built-in holds the local working copies, so it can answer; it simply answers about
         * fewer notes, and says which store it searched.
         */
        @Override
        Integer whenExtensionCannot(String verb, List<String> args) {
            if (!BuiltinMemory.supports(verb)) {
                return null;
            }
            System.out.println(
                    "  the attached archive does not do \"" + verb + "\" — searching the local working copies instead");
            return BuiltinMemory.run(verb, args);
        }

        /**
         * Keep a copy locally even when an archive takes the original.
         *
         * <p>Without this the compounding only half works. Filing a note with an archive attached
         * sent it out of reach, so `oss pick` scored against reviews alone and every note you wrote
         * made the suggestions no better -- which is the opposite of the promise.
         *
         * <p>The archive is still where the note LIVES: classified, linked, searchable in a year.
         * This is a working copy for the corpus, and it costs a few kilobytes.
         */
        /**
         * The arguments that are actually files.
         *
         * <p>Named and package-private so a test can call THIS, rather than restate the rule and
         * then agree with itself. A test that owns its own copy of a rule passes whatever the
         * program does.
         */
        static List<String> pathsAmong(List<String> args) {
            List<String> paths = new java.util.ArrayList<>();
            for (String a : args) {
                if (!a.startsWith("-") && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(a))) {
                    paths.add(a);
                }
            }
            return paths;
        }

        @Override
        void alsoLocally(String verb, List<String> args) {
            if (!"file".equals(verb) || args.isEmpty()) {
                return;
            }
            // Only the paths. The argument list on the way through carries the extension's own
            // options -- `--topic Tooling --apply` and whatever else it defines -- and the built-in
            // store reads every argument as a path, so each one came back as
            // "skipped (not a file)  --topic". Three lines of apparent failure printed after a
            // filing that had entirely succeeded, on every invocation that used a flag.
            //
            // Which options take a value is the extension's business and cannot be known here, so
            // this does not try to parse them: an argument is a path if it is one.
            List<String> paths = pathsAmong(args);
            if (!paths.isEmpty()) {
                BuiltinMemory.run("file", paths);
            }
        }
    }
}
