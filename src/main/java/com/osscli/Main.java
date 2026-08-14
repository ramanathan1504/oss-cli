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
package com.osscli;

import com.osscli.storage.DatabaseManager;
import com.osscli.storage.SchemaTooNewException;
import picocli.CommandLine;

public class Main {

    /**
     * Commands that must keep working against a store this build cannot open.
     *
     * <p>Refusing everything would take away the one command that explains the refusal. Somebody
     * whose {@code oss} has just started saying no needs {@code doctor} to tell them what is going
     * on and {@code --version} to see which build they are running -- and neither reads a table, so
     * neither is unsafe on an unknown schema.
     */
    private static final java.util.Set<String> SAFE_WITHOUT_A_DATABASE =
            java.util.Set.of("doctor", "--version", "-V", "--help", "-h", "help");

    public static void main(String[] args) {
        // First, and before anything can touch a logger: log4j2.xml resolves its file appender
        // through the system property this publishes, and Log4j reads its configuration once.
        AppPaths.bootstrap();

        try {
            DatabaseManager.initializeSchema();
        } catch (SchemaTooNewException e) {
            if (!diagnostic(args)) {
                refuse(e);
                System.exit(1);
            }
            // Fall through: doctor reports the mismatch itself, and reporting it is the point.
        }

        CommandLine commandLine = new CommandLine(new RootCommand());

        // `run` and `memory` are dispatchers, not parsers: everything after the verb belongs to the
        // extension. Left as ordinary subcommands, picocli claims flags out of the passthrough --
        // `oss run list --apps` printed picocli's own usage because --apps was unknown HERE
        // and so never reached the bench. Scoped to these two, because every other subcommand does
        // want its arguments parsed.
        for (String dispatcher : java.util.List.of("run", "memory")) {
            CommandLine sub = commandLine.getSubcommands().get(dispatcher);
            if (sub != null) {
                sub.setStopAtPositional(true).setUnmatchedOptionsArePositionalParams(true);
            }
        }

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    /** True when the first argument is one of the commands that stays available. */
    static boolean diagnostic(String[] args) {
        return args.length > 0 && SAFE_WITHOUT_A_DATABASE.contains(args[0]);
    }

    /** The first line of {@code --version}, so the refusal names the build the user is actually running. */
    private static String buildHeadline() {
        try {
            String[] lines = new VersionProvider().getVersion();
            return lines.length > 0 ? lines[0] : "oss";
        } catch (Exception e) {
            // The version is decoration on this message; failing to read it must not replace a
            // clear refusal with a stack trace.
            return "oss";
        }
    }

    /**
     * Says what happened, in the order somebody needs it: what is wrong, that nothing was touched,
     * and what to do about it.
     *
     * <p>Written to stderr directly rather than through the logger, because the logger writes into
     * the store's own directory and the whole point here is that this build should not be touching
     * that store.
     */
    static void refuse(SchemaTooNewException e) {
        String message = String.join(
                System.lineSeparator(),
                "",
                "  This database was written by a newer oss than this one.",
                "",
                "    database:    schema " + e.storeVersion(),
                "    this build:  schema " + e.understoodVersion() + "  (" + buildHeadline() + ")",
                "",
                "  Nothing has been read or changed. Migrations only run forwards, so an",
                "  older build cannot understand a schema written after it -- and carrying on",
                "  would mean writing rows in a shape this build merely believes in.",
                "",
                "  Upgrade:        brew upgrade oss",
                "  Or work elsewhere:  OSS_CLI_HOME=~/somewhere-else oss ...",
                "  Or look first:      oss doctor",
                "");
        System.err.println(message);
    }
}
