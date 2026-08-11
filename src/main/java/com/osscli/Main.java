package com.osscli;

import com.osscli.storage.DatabaseManager;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        // First, and before anything can touch a logger: log4j2.xml resolves its file appender
        // through the system property this publishes, and Log4j reads its configuration once.
        AppPaths.bootstrap();

        DatabaseManager.initializeSchema();

        CommandLine commandLine = new CommandLine(new RootCommand());

        // `bench` and `kb` are dispatchers, not parsers: everything after the verb belongs to the
        // extension. Left as ordinary subcommands, picocli claims flags out of the passthrough --
        // `oss-cli bench list --apps` printed picocli's own usage because --apps was unknown HERE
        // and so never reached the bench. Scoped to these two, because every other subcommand does
        // want its arguments parsed.
        for (String dispatcher : java.util.List.of("bench", "kb")) {
            CommandLine sub = commandLine.getSubcommands().get(dispatcher);
            if (sub != null) {
                sub.setStopAtPositional(true).setUnmatchedOptionsArePositionalParams(true);
            }
        }

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
