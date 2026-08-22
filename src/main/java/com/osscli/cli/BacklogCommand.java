package com.osscli.cli;

import com.osscli.runner.Engine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The whole backlog at once, as a page you can read.
 *
 * <p>{@code oss triage} answers "what is going on with issue 4234". This answers the question
 * before that one — <em>which of the four hundred open things is worth opening at all</em> — by
 * clustering pull requests and issues that reference each other and sorting them into what can be
 * merged now, what is one fix away, and what to pick up next.
 *
 * <p>It reads and writes a local page. There is no verb here that posts anything anywhere, and
 * every GitHub call it makes is a list or a view.
 */
@Command(
        name = "backlog",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "The whole backlog as one page: clusters, mergeable, one fix away, what to pick")
public class BacklogCommand implements Callable<Integer> {

    private static final String SCRIPT = "tools/backlog.sh";

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository in 'owner/name' format")
    String repository;

    @Option(names = "--no-ai", description = "Skip the model-written enrichment")
    boolean noAi;

    @Option(names = "--dry-run", description = "Reuse the cached fetch rather than calling GitHub again")
    boolean dryRun;

    @Parameters(index = "0..*", description = "The repository, as owner/name", arity = "0..*")
    List<String> args = List.of();

    @Override
    public Integer call() {
        if (!Engine.supported()) {
            System.err.println("error  this report is POSIX shell, and this is Windows. Run it under WSL.");
            return 2;
        }
        Path script = Engine.shipped(SCRIPT);
        if (script == null) {
            System.err.println("error  no " + SCRIPT + " beside this install.");
            return 2;
        }
        // The report needs a repository, and bare `oss backlog` used to hand it none -- so the
        // shell script printed its own usage, which names a positional OWNER/REPO this command
        // does not document and mentions env tunables no reader of `oss backlog --help` has heard
        // of. Every other command falls back to default.repository when -r is omitted; this one
        // now does too, rather than leaking the layer underneath it.
        // Anything option-shaped that reached the positional list is a flag this command does not
        // have. Passing it down makes the shell script answer, and it answers with ITS interface --
        // a positional OWNER/REPO and env tunables no reader of `oss backlog --help` has heard of.
        // That leak was fixed for bare `oss backlog` and not for this door, which is the one people
        // actually walk through: every other repository command takes -r, so -r is what gets typed.
        for (String arg : args) {
            if (arg.startsWith("-")) {
                System.err.println("error  \"" + arg + "\" is not an option of oss backlog.");
                System.err.println("       oss backlog -r owner/name [--no-ai] [--dry-run]");
                return 2;
            }
        }

        List<String> effective = new ArrayList<>(args);
        if (repository != null && !repository.isBlank()) {
            effective.add(0, repository.strip());
        }
        if (noAi) {
            effective.add("--no-ai");
        }
        if (dryRun) {
            effective.add("--dry-run");
        }
        if (effective.isEmpty()) {
            String fallback = defaultRepository();
            if (fallback == null) {
                System.err.println("error  which repository? oss backlog owner/name");
                System.err.println("       or set a default: oss setup  (default.repository)");
                return 2;
            }
            effective.add(fallback);
        }

        try {
            List<String> cmd = new ArrayList<>(List.of("bash", script.toString()));
            cmd.addAll(effective);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    /** The repository to report on when none was named. Null when nothing is configured. */
    private static String defaultRepository() {
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("default.repository");
            return configured == null || configured.isBlank() ? null : configured.strip();
        } catch (Exception e) {
            return null;
        }
    }
}
