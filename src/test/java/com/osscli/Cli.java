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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine;

/**
 * Runs a command the way a user does, and hands back what they would have seen.
 *
 * <p>The tests that found today's bugs all had the same shape: something was asserted about the
 * source, and the source was right while the program was wrong. A format string with three
 * placeholders and two arguments reads perfectly. So does a call to {@code Files.readString} on a
 * directory that happens to contain a binary. Both shipped, and both failed on the first real use.
 *
 * <p>This drives the actual entry point — the same {@link RootCommand}, the same picocli parsing,
 * the same dispatcher wiring — so a command is exercised as typed rather than as read. It stops
 * short of {@code Main.main} only because that ends in {@code System.exit}, which would take the
 * test JVM with it; everything before that line is reproduced here, and the two are checked against
 * each other by {@link EndToEndCommandTest}.
 *
 * <p>Safe by construction: the build points {@code OSS_CLI_HOME} at {@code target/test-home}, so a
 * command that writes writes there and never near the real store.
 */
final class Cli {

    private Cli() {}

    /** What a command printed and returned. */
    record Result(int exitCode, String out, String err) {
        /** Everything the user saw, in one string, since a CLI's two streams are one screen. */
        String all() {
            return out + err;
        }

        boolean ok() {
            return exitCode == 0;
        }

        /** True when the output names {@code needle}, wherever it was printed. */
        boolean says(String needle) {
            return all().contains(needle);
        }
    }

    /**
     * Runs {@code args} through the real command tree.
     *
     * <p>Streams are swapped rather than captured by a logger appender: the console appender writes
     * to whatever {@code System.out} is at the time, so this catches logger output and direct
     * printing alike — and a CLI uses both.
     */
    static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));

            // Main does these two before it builds anything, and a command that runs without
            // them is a different program: the probe that skipped them had eleven commands
            // leaking `org.sqlite.SQLiteException: no such table` instead of doing their job.
            AppPaths.bootstrap();
            try {
                com.osscli.storage.DatabaseManager.initializeSchema();
            } catch (RuntimeException e) {
                // Main tolerates this for the diagnostic commands and refuses otherwise; the
                // harness lets the command itself decide, which is what is being tested.
            }

            CommandLine commandLine = new CommandLine(new RootCommand());
            // Mirrors Main exactly. Without it `oss run list --apps` is parsed HERE and the
            // extension never sees --apps -- which is a real bug this harness must be able to
            // reproduce, not one it quietly avoids by configuring itself differently.
            for (String dispatcher : java.util.List.of("run", "memory", "backlog")) {
                CommandLine sub = commandLine.getSubcommands().get(dispatcher);
                if (sub != null) {
                    sub.setStopAtPositional(true).setUnmatchedOptionsArePositionalParams(true);
                }
            }

            // Main strips a pasted `#` comment before parsing. Skipping it here would make this a
            // different program than the one that ships -- and the first version of this harness
            // did skip it, so the end-to-end paste test failed against a fix that was working.
            int code = commandLine.execute(Main.withoutPastedComment(args));
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
