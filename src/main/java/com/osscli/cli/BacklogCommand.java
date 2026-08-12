package com.osscli.cli;

import com.osscli.runner.Engine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
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
        mixinStandardHelpOptions = true,
        description = "The whole backlog as one page: clusters, mergeable, one fix away, what to pick")
public class BacklogCommand implements Callable<Integer> {

    private static final String SCRIPT = "tools/backlog.sh";

    @Parameters(index = "0..*", description = "Arguments passed to the report, e.g. owner/name", arity = "0..*")
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
        try {
            List<String> cmd = new ArrayList<>(List.of("bash", script.toString()));
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }
}
