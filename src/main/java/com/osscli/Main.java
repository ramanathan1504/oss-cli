package com.osscli;

import com.osscli.storage.DatabaseManager;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        // First, and before anything can touch a logger: log4j2.xml resolves its file appender
        // through the system property this publishes, and Log4j reads its configuration once.
        AppPaths.bootstrap();

        DatabaseManager.initializeSchema();

        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }
}
