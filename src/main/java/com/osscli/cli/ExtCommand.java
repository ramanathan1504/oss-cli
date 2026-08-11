package com.osscli.cli;

import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import com.osscli.ext.ExtensionRunner;
import com.osscli.safety.UpstreamGuard;
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
 * oss-cli ext add ~/apache/log4j2-workout    # register whatever that repo declares
 * oss-cli ext list                           # what is wired up, and is it still reachable
 * oss-cli bench review 4234                  # dispatch to the bench extension
 * oss-cli kb file notes.md                   # dispatch to the archive
 * }</pre>
 */
@Command(
        name = "ext",
        mixinStandardHelpOptions = true,
        description = "Register and inspect bench/kb extensions",
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
                System.out.println("  oss-cli ext add <repo>     a repo with an oss-ext.json at its root");
                System.out.println("  kinds: bench (executes something real) · kb (remembers)");
                return 0;
            }
            System.out.printf("%-14s %-6s %-9s %s%n", "NAME", "KIND", "STATE", "VERBS");
            for (Extension e : all) {
                boolean ok = ExtensionRunner.isReachable(e);
                System.out.printf(
                        "%-14s %-6s %-9s %s%n",
                        e.getName(),
                        e.kind().lower(),
                        ok ? "ok" : "MISSING",
                        String.join(", ", e.getVerbs().keySet()));
                if (!ok) {
                    // Naming the path it expected is the difference between "something is wrong"
                    // and a one-line fix; a moved checkout is the common cause.
                    System.out.println("               expected: " + e.execPath());
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
     * <p>{@code oss-cli bench <verb> …} reads better than {@code oss-cli ext run --kind bench …} and
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

        @Override
        public Integer call() {
            try {
                Extension ext = ExtensionRegistry.resolve(kind(), name);
                // Gate before starting the process, not inside it. Once the child is running the
                // post has already left; the only useful place to stop is here.
                if (ext.writesOutward(verb)) {
                    String approved = UpstreamGuard.normaliseRepo(approveUpstream);
                    if (!UpstreamGuard.allow(ext.getName() + " " + verb, ext.writeTarget(), approved)) {
                        return 2;
                    }
                }
                return ExtensionRunner.run(ext, verb, passthrough);
            } catch (RuntimeException e) {
                System.err.println("error  " + e.getMessage());
                return 1;
            }
        }
    }

    // stopAtPositional is what makes this a dispatcher rather than a parser. Without it picocli
    // claims any flag it recognises out of the passthrough -- `oss-cli bench list --apps` printed
    // this command's own usage, because --apps was unknown HERE and never reached the bench. After
    // the verb, nothing is ours.
    @Command(
            name = "bench",
            mixinStandardHelpOptions = true,
            description = "Run something real through a registered bench extension")
    public static class BenchDispatch extends Dispatch {
        @Override
        Extension.Kind kind() {
            return Extension.Kind.BENCH;
        }
    }

    @Command(
            name = "kb",
            mixinStandardHelpOptions = true,
            description = "File, index or search through a registered knowledge-base extension")
    public static class KbDispatch extends Dispatch {
        @Override
        Extension.Kind kind() {
            return Extension.Kind.KB;
        }
    }
}
