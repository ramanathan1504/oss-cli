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
package com.osscli.cli;

import com.osscli.bug.BugReport;
import com.osscli.bug.Crash;
import com.osscli.bug.Home;
import com.osscli.github.GitHubClient;
import com.osscli.ui.Out;
import com.osscli.util.CredentialManager;
import java.io.Console;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * File a fault in this tool, from the terminal, without a browser.
 *
 * <p>The board page shipped dead for a release. Every test passed, the service reported itself
 * healthy, and the one person who noticed had to be the person who wrote it. That is the gap this
 * closes: the distance between somebody hitting a bug and the maintainer hearing about it was a
 * browser, an account, a template and about four minutes, and almost nobody pays that.
 *
 * <p>Three things it will not do, and each is the same rule as everywhere else here:
 *
 * <ul>
 *   <li><b>It never posts without being shown.</b> The whole body, redacted, is printed and then
 *       confirmed. Not a summary of it -- the bytes. A tool that sends anything outward on its own
 *       judgement is one people stop running.
 *   <li><b>It never needs a model.</b> A model writes the title and a paragraph if one is reachable;
 *       without one the report is the stack, the build and the command, which is a better issue than
 *       most. It says which of the two it got.
 *   <li><b>It never needs a browser.</b> With a token it posts; without one it prints the report and
 *       the address to paste it at, which is the same report either way.
 * </ul>
 *
 * <p>Redaction is not best-effort here. A crash report is assembled from a command line, a stack
 * trace and a working directory, and between them those carry the home path, whatever key was passed
 * as an argument, and the name of every repository somebody follows -- so {@code Publishable} runs over
 * every field, and what it took out is stated above the confirmation.
 */
@Command(
        name = "bug",
        mixinStandardHelpOptions = true,
        description = "File a fault in oss itself as a GitHub issue, after showing you exactly what would be posted")
public class BugCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..*", description = "What went wrong, in your own words")
    private java.util.List<String> words = java.util.List.of();

    @Option(names = "--last", description = "Report the last error oss hit, rather than describing one")
    boolean last;

    @Option(names = "--print", description = "Only print the report; do not offer to post it")
    boolean printOnly;

    @Option(names = "--model", description = "Local model to draft with (default: the guidance model)")
    String model;

    @Override
    public Integer call() {
        String said = String.join(" ", words).strip();
        Optional<Crash> remembered = Crash.last();

        if (last && remembered.isEmpty()) {
            Out.none("Nothing remembered — oss has not hit an unexpected error since it last ran.");
            Out.hint("oss bug \"what happened\"", "describe one instead");
            return 0;
        }
        if (!last && said.isBlank()) {
            said = askWhatHappened();
            if (said == null) {
                // No console and nothing said: there is no report to build, and inventing one from
                // an old crash nobody mentioned would be filing on somebody's behalf.
                System.err.println("error  say what went wrong:  oss bug \"the board page is blank\"");
                System.err.println("       or report the last error oss hit:  oss bug --last");
                return 1;
            }
        }

        // A description with no crash behind it is still a bug report. The record just has no stack,
        // and everything downstream already treats an empty one as "nothing to show" rather than as
        // an error.
        Crash crash = last ? remembered.orElseThrow() : Crash.byHand(said);
        if (!last && remembered.isPresent()) {
            // Said rather than assumed. Attaching an old stack trace to a sentence about something
            // else is how a report ends up describing two faults and being actionable on neither.
            System.out.println("  (oss also remembers an error it hit: report that one with  oss bug --last)");
        }

        BugReport report = draft(crash, said);
        show(report);

        if (printOnly) {
            return 0;
        }
        return offerToPost(report);
    }

    private String askWhatHappened() {
        Console console = System.console();
        if (console == null) {
            return null;
        }
        System.out.println();
        Out.section("what went wrong");
        Out.none("one or two sentences is plenty");
        System.out.print("  > ");
        String line = console.readLine();
        return line == null || line.isBlank() ? null : line.strip();
    }

    /**
     * Ask whichever model this machine has, and carry on without one.
     *
     * <p>Through {@code Rungs.forThisMachine}, which resolves a named cloud engine and a local
     * daemon in that order, rather than a fourth copy of that decision. The two ways this has been
     * got wrong before -- refusing without a cloud key, then refusing without Ollama -- were both a
     * command answering the question itself.
     */
    private BugReport draft(Crash crash, String said) {
        Set<String> repositories = Home.syncedRepositories();
        String home = System.getProperty("user.home");
        String resolved = model == null || model.isBlank() ? com.osscli.Defaults.GUIDANCE_MODEL : model;

        var chosen = com.osscli.agent.Rungs.forThisMachine(resolved).orElse(null);
        if (chosen == null) {
            return BugReport.of(crash, said, home, repositories);
        }
        try (var live = com.osscli.ui.Live.start("  drafting the report with " + chosen.label())) {
            String answer = chosen.ask().apply(BugReport.prompt(crash, said, home, repositories));
            if (answer != null && answer.startsWith("error:")) {
                // Loud, not silent: a report that reads as hand-written when a model wrote it, or
                // the reverse, is a difference the reader is entitled to.
                System.out.println("  " + answer + " — writing the report without one.");
                return BugReport.of(crash, said, home, repositories);
            }
            return BugReport.fromModel(answer, crash, said, home, repositories);
        } catch (Exception e) {
            return BugReport.of(crash, said, home, repositories);
        }
    }

    private void show(BugReport report) {
        String rule = "  " + "─".repeat(72);
        System.out.println();
        System.out.println("  ── what would be posted " + "─".repeat(49));
        System.out.println();
        System.out.println("  title:  " + report.title());
        System.out.println();
        for (String line : report.body().split("\\R", -1)) {
            System.out.println("  " + line);
        }
        System.out.println(rule);
        System.out.println("  " + (report.drafted() ? "drafted by a model" : "written without a model")
                + " · paths, keys, addresses and repository names taken out");
        System.out.println();
    }

    private Integer offerToPost(BugReport report) {
        // findGitHubToken, never getGitHubToken: the latter throws when there is none, so the
        // branch below -- the whole no-token path -- could not be reached by the machines that
        // need it. It threw instead, and the crash reporter then offered to file a bug about it.
        String token = CredentialManager.findGitHubToken();
        if (token == null || token.isBlank()) {
            // Degrade, do not refuse. The report is the same report; only the last step needs a
            // token, and pasting it is a step somebody can take right now.
            System.out.println("  No GitHub token, so this cannot be filed from here.");
            System.out.println("  Paste it at:  " + Home.newIssueUrl());
            return 0;
        }
        Console console = System.console();
        if (console == null) {
            // A pipe cannot consent. Printing and stopping is the only honest end to a run with
            // nobody at the other end of it.
            System.out.println("  Not posted: nothing here can confirm it. Run this at a terminal, or paste it at:");
            System.out.println("  " + Home.newIssueUrl());
            return 0;
        }

        GitHubClient github = new GitHubClient(token);
        Optional<Long> existing = Home.alreadyFiled(github, report.signature());
        if (existing.isPresent()) {
            // Not filed twice. A second identical issue costs the maintainer the time this command
            // was meant to save them.
            System.out.println("  This is already filed: " + Home.issueUrl(existing.get()));
            System.out.println("  Add what you saw there rather than opening a second one.");
            return 0;
        }

        System.out.print("  Post this to " + Home.slug() + "? [y/N] ");
        String answer = console.readLine();
        if (answer == null || !answer.trim().matches("(?i)y|yes")) {
            System.out.println("  Not posted. Nothing left this machine.");
            return 0;
        }
        try (var live = com.osscli.ui.Live.start("  filing it")) {
            long number = github.createIssue(Home.owner(), Home.repo(), report.title(), report.body(), Home.LABELS);
            live.close();
            System.out.println("  ✓ filed: " + Home.issueUrl(number));
            return 0;
        } catch (Exception e) {
            System.err.println("error  could not file it: " + e.getMessage());
            // The report is not lost because the network was. It is above, and this says where it
            // goes -- a failure that also destroys the work is two failures.
            System.err.println("       the report is above; paste it at:  " + Home.newIssueUrl());
            return 1;
        }
    }
}
