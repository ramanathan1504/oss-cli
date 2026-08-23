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
package com.osscli.bug;

import picocli.CommandLine;

/**
 * What happens when a command dies of something nobody wrote a message for.
 *
 * <p>Until now: a stack trace, and that was the end of it. The person who saw it is the only person
 * who will ever know, because the distance between them and an issue is a browser, an account and a
 * template -- and the fault they hit is by definition the one nobody had thought about. The board
 * page shipped dead for a release on exactly that arithmetic.
 *
 * <p>So the trace still prints, unchanged, because it is what they need in front of them. Then the
 * crash is written down, and if somebody is at a terminal they are asked once. Written down either
 * way: answering no has to cost nothing, or the question is a toll rather than an offer, and
 * {@code oss bug --last} picks it up whenever they get round to it.
 *
 * <p><b>Not every failure is a defect.</b> A pulled cable and a rejected key are this program
 * working correctly against a world that is not cooperating, and asking to file those would teach
 * people to say no to the question -- after which the one that mattered gets a no as well. Those
 * are recognised and passed through with the trace and no offer.
 */
public final class Reporter implements CommandLine.IExecutionExceptionHandler {

    /** Asked once per run. A command that fails twice is one failure as far as anyone reading is concerned. */
    private static boolean asked;

    @Override
    public int handleExecutionException(Exception ex, CommandLine command, CommandLine.ParseResult parseResult) {
        // Unchanged, and first: whoever is looking at this needs the trace more than they need the
        // offer, and a question printed above the error is a question asked before the error was
        // read.
        ex.printStackTrace();

        Crash crash = Crash.of(commandLine(parseResult), ex);
        if (theWorldIsNotCooperating(ex)) {
            return exitCode(command, ex);
        }
        crash.remember();
        offer(crash);
        return exitCode(command, ex);
    }

    /** What was typed, as far as the parser understood it -- which is what a report has to name. */
    static String commandLine(CommandLine.ParseResult parseResult) {
        StringBuilder b = new StringBuilder("oss");
        CommandLine.ParseResult r = parseResult;
        while (r != null) {
            if (r.commandSpec() != null && r.commandSpec().parent() != null) {
                b.append(' ').append(r.commandSpec().name());
            }
            r = r.hasSubcommand() ? r.subcommand() : null;
        }
        return b.toString();
    }

    /**
     * Whether this is the network, a credential or a rate limit rather than a defect.
     *
     * <p>The whole cause chain, because every one of these arrives wrapped: a
     * {@code ConnectException} reaches here inside an {@code IOException} inside whatever the
     * command threw.
     */
    static boolean theWorldIsNotCooperating(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof javax.net.ssl.SSLException
                    || t instanceof com.osscli.llm.ApiFailure.Permanent) {
                return true;
            }
        }
        return false;
    }

    private void offer(Crash crash) {
        if (asked || System.console() == null) {
            // No console is not a reason to lose the report -- it is already remembered, and the
            // line below says how to reach it without a prompt nobody could have answered.
            System.err.println();
            System.err.println("  oss hit an error it did not expect. File it:  oss bug --last");
            return;
        }
        asked = true;
        System.err.println();
        System.err.println("  oss hit an error it did not expect.");
        System.err.print("  File it as an issue on " + Home.slug() + "? [y/N] ");
        String answer = System.console().readLine();
        if (answer == null || !answer.trim().matches("(?i)y|yes")) {
            System.err.println("  Not filed. It is remembered — change your mind with:  oss bug --last");
            return;
        }
        // Straight into the command rather than a second implementation of it. Everything that
        // matters here -- the redaction, the preview, the duplicate check, the second confirmation
        // before anything is posted -- lives there, and a copy of it would be a copy that drifts.
        new CommandLine(new com.osscli.cli.BugCommand()).execute("--last");
    }

    private int exitCode(CommandLine command, Exception ex) {
        return command.getExitCodeExceptionMapper() != null
                ? command.getExitCodeExceptionMapper().getExitCode(ex)
                : command.getCommandSpec().exitCodeOnExecutionException();
    }

    /** Reset between tests, because "asked once" is per run and a suite is one run. */
    static void forget() {
        asked = false;
    }
}
