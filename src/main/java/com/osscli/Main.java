package com.osscli;

import com.osscli.storage.DatabaseManager;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        DatabaseManager.initializeSchema();

        int exitCode = new CommandLine(new RootCommand()).execute(args);
        System.exit(exitCode);
    }
}
