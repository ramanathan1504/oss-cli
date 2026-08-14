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
 * oss ext add ~/apache/log4j2-workout    # register whatever that repo declares
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

    @Command(name = "add", description = "Register the extension declared by a repository")
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

    @Command(name = "list", description = "Show every registered extension")
    static class ListExt implements Callable<Integer> {

        @Override
        public Integer call() {
            List<Extension> all = ExtensionRegistry.all();
            if (all.isEmpty()) {
                System.out.println("No extensions registered.");
                System.out.println();
                System.out.println("  oss ext add <repo>     a repo with an oss-ext.json at its root");
                System.out.println("  kinds: runner (executes something real) · memory (remembers)");
                return 0;
            }
            System.out.printf("%-14s %-6s %-9s %s%n", "NAME", "KIND", "STATE", "VERBS");
            for (Extension e : all) {
                boolean ok = ExtensionRunner.isReachable(e);
                boolean stale = ExtensionRegistry.isStale(e);
                System.out.printf(
                        "%-14s %-6s %-9s %s%n",
                        e.getName(),
                        e.kind().lower(),
                        !ok ? "MISSING" : stale ? "STALE" : "ok",
                        String.join(", ", e.getVerbs().keySet()));
                if (!ok) {
                    // Naming the path it expected is the difference between "something is wrong"
                    // and a one-line fix; a moved checkout is the common cause.
                    System.out.println("               expected: " + e.execPath());
                }
                if (stale) {
                    System.out.println("               " + Extension.MANIFEST
                            + " changed on disk since it was registered — oss ext refresh " + e.getName());
                }
            }
            return 0;
        }
    }

    @Command(name = "remove", description = "Unregister an extension")
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

    @Command(name = "refresh", description = "Re-read a registered extension's manifest from disk")
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

        @Parameters(index = "0", description = "Verb to dispatch, e.g. run, matrix, review, file, index, search")
        String verb;

        @Parameters(index = "1..*", description = "Arguments passed through untouched")
        List<String> passthrough = List.of();

        abstract Extension.Kind kind();

        /**
         * What to do when no extension of this kind is registered.
         *
         * <p>Null means "there is nothing to fall back to", which is the honest answer for a runner:
         * nothing in the core can execute somebody's project for them. A memory can fall back,
         * because a folder of markdown is a real answer.
         */
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

        @Override
        Extension.Kind kind() {
            return Extension.Kind.RUNNER;
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
         * Keep a copy locally even when an archive takes the original.
         *
         * <p>Without this the compounding only half works. Filing a note with an archive attached
         * sent it out of reach, so `oss pick` scored against reviews alone and every note you wrote
         * made the suggestions no better -- which is the opposite of the promise.
         *
         * <p>The archive is still where the note LIVES: classified, linked, searchable in a year.
         * This is a working copy for the corpus, and it costs a few kilobytes.
         */
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
            List<String> paths = new java.util.ArrayList<>();
            for (String a : args) {
                if (!a.startsWith("-") && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(a))) {
                    paths.add(a);
                }
            }
            if (!paths.isEmpty()) {
                BuiltinMemory.run("file", paths);
            }
        }
    }
}
