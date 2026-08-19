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

        args = withoutPastedComment(args);

        try {
            DatabaseManager.initializeSchema();
        } catch (SchemaTooNewException e) {
            if (!diagnostic(args)) {
                refuse(e);
                System.exit(1);
            }
            // Fall through: doctor reports the mismatch itself, and reporting it is the point.
        }

        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * The command tree, configured.
     *
     * <p>One factory because there are three callers -- this entry point, the engine prefixes that
     * re-dispatch what follows them, and the test harness -- and the settings below are not
     * decoration: a harness that built its own {@code CommandLine} would be testing a different
     * program than the one that ships, which is exactly how a passing suite once covered a bug in
     * argument handling.
     *
     * <p>The dispatchers are not parsers: everything after the verb belongs to somebody else. Left
     * as ordinary subcommands, picocli claims flags out of the passthrough -- {@code oss run list
     * --apps} printed picocli's own usage because {@code --apps} was unknown HERE and so never
     * reached the bench. {@code backlog} joined them for the same reason, found the same way. The
     * engine prefixes are on the list for a third reason: {@code oss claude review 4249 --refresh}
     * must hand {@code --refresh} to {@code review}, not answer about it here.
     */
    public static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new RootCommand());
        java.util.List<String> dispatchers = new java.util.ArrayList<>(java.util.List.of("run", "memory", "backlog"));
        dispatchers.addAll(com.osscli.llm.Ai.prefixes());
        for (String dispatcher : dispatchers) {
            CommandLine sub = commandLine.getSubcommands().get(dispatcher);
            if (sub != null) {
                sub.setStopAtPositional(true).setUnmatchedOptionsArePositionalParams(true);
            }
        }
        return commandLine;
    }

    /** True when the first argument is one of the commands that stays available. */
    static boolean diagnostic(String[] args) {
        return args.length > 0 && SAFE_WITHOUT_A_DATABASE.contains(args[0]);
    }

    /**
     * Drops everything from a bare {@code #} onward, so a command pasted out of the documentation
     * runs.
     *
     * <p>Documentation writes examples with the explanation on the same line, which is the near
     * universal convention:
     *
     * <pre>
     * oss followup                 # every recorded PR, one line each
     * </pre>
     *
     * <p><b>zsh does not strip that.</b> {@code interactive_comments} is off in an interactive zsh,
     * so the shell hands {@code #}, {@code every}, {@code recorded} … straight through as arguments
     * and the answer to a pasted line is {@code '#' is not an int} followed by twenty lines of
     * usage. bash users never see it, which is exactly why it survived: the docs are correct, the
     * tool is correct, and the combination is broken on the default macOS shell.
     *
     * <p>Fixed here rather than by rewriting sixty-one documentation lines, because this also covers
     * every example written after today, and every blog post and README that quotes one.
     *
     * <p>Only a token that is <em>exactly</em> {@code #} counts. {@code #4240} is left alone —
     * somebody typing {@code oss pr #4240} means the number, and silently discarding it would trade
     * a clear error for a confusing one.
     */
    static String[] withoutPastedComment(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("#".equals(args[i])) {
                return java.util.Arrays.copyOfRange(args, 0, i);
            }
        }
        return args;
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
