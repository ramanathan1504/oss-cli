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

import com.osscli.cli.*;
import picocli.CommandLine.Command;

@Command(
        // The command is `oss`. It has to say so too: usage text and error messages
        // that name a command nobody typed send people looking for a binary that is
        // no longer installed.
        name = "oss",
        mixinStandardHelpOptions = true,
        // The list above is the dozen that carry the daily work. The other twenty-three still run
        // exactly as they did; this line is how somebody finds them again, and without it hiding a
        // command would be removing it with extra steps.
        footer = {"", "  23 more commands are available and unchanged:  oss --help-all", ""},
        versionProvider = VersionProvider.class,
        subcommands = {
            SyncCommand.class,
            AskCommand.class,
            SkillCommand.class,
            CriticalCommand.class,
            AnalyzeCommand.class,
            HiddenCriticalCommand.class,
            DuplicatesCommand.class,
            SearchCommand.class,
            PrsCommand.class,
            ReviewCommand.class,
            ProfileCommand.class,
            OnboardCommand.class,
            ReportCommand.class,
            TrendCommand.class,
            TriageCommand.class,
            SetupCommand.class,
            GuideCommand.class,
            ChatCommand.class,
            HistoryCommand.class,
            PromptCommand.class,
            InspectCommand.class,
            BackupCommand.class,
            RestoreCommand.class,
            DoctorCommand.class,
            BugCommand.class,
            // OSS-CLI knows; a bench runs and a kb remembers. Registered as three entries rather
            // than one so `oss run …` and `oss memory …` read as first-class capabilities.
            AliasCommand.class,
            FollowupCommand.class,
            HubCommand.class,
            BacklogCommand.class,
            PickCommand.class,
            ModelCommand.class,
            IssueCommand.class,
            PrCommand.class,
            ExtCommand.class,
            com.osscli.serve.ServeCommand.class,
            ExtCommand.BenchDispatch.class,
            ExtCommand.KbDispatch.class,
            // The engine you are willing to let answer, in front of the command it applies to.
            // Registered as four names rather than one --engine flag because what a reader types
            // is then the whole answer to "did a model see this, and whose".
            EngineCommand.Llm.class,
            EngineCommand.Claude.class,
            EngineCommand.Gemini.class,
            EngineCommand.Codex.class,
            EngineCommand.Junie.class
        })
public class RootCommand implements java.util.concurrent.Callable<Integer> {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    /**
     * {@code oss} with no command, and {@code oss --help-all}.
     *
     * <p>Answered here as well as in {@code Main} because the two entry points must not disagree:
     * {@code Main} handles it before dispatch so it survives a store this build refuses to open,
     * exactly like {@code --help}, and this handles it when the tree is driven directly -- which is
     * how every test runs it. A flag that works from a terminal and not from a test is a flag with
     * no test.
     *
     * <p>Bare {@code oss} still prints usage and exits 2, unchanged: it is a usage error, and
     * scripts that check for it are right to.
     */
    @Override
    public Integer call() {
        if (helpAll) {
            return printEveryCommand(spec.commandLine());
        }
        spec.commandLine().usage(System.out);
        return 2;
    }

    /**
     * Every command, including the ones {@code oss --help} no longer lists.
     *
     * <p>Thirty-eight entries in one flat list is not a menu, it is an inventory. A reader looking
     * for "how do I see what is waiting on me" had to know that {@code hub} was the answer and that
     * {@code critical}, {@code prs}, {@code followup} and {@code backlog} were not. So the help now
     * shows the dozen that carry the daily work, and the rest are hidden.
     *
     * <p><b>Hidden, never removed.</b> Every one of them still runs, still takes the same flags,
     * still prints its own usage. A script written last year does not care what this command's help
     * looks like, and breaking one to tidy a screen would be charging somebody else for a decision
     * they did not make. This flag is the way back, and the footer of {@code --help} names it —
     * a hidden command nobody can find again is a removed command with extra steps.
     */
    @picocli.CommandLine.Option(
            names = {"--help-all"},
            description = "List every command, including the ones --help does not show")
    boolean helpAll;

    /** Prints the full inventory, marking which are hidden from the short help. */
    public static int printEveryCommand(picocli.CommandLine cli) {
        java.util.Map<String, picocli.CommandLine> subs = cli.getSubcommands();
        java.util.Set<picocli.CommandLine> seen = new java.util.LinkedHashSet<>(subs.values());
        java.util.List<String> shown = new java.util.ArrayList<>();
        java.util.List<String> hidden = new java.util.ArrayList<>();
        for (picocli.CommandLine sub : seen) {
            picocli.CommandLine.Model.CommandSpec spec = sub.getCommandSpec();
            String[] description = spec.usageMessage().description();
            String line = String.format("  %-18s %s", spec.name(), description.length > 0 ? description[0] : "");
            (spec.usageMessage().hidden() ? hidden : shown).add(line);
        }
        System.out.println();
        System.out.println("  EVERY DAY");
        shown.forEach(System.out::println);
        if (!hidden.isEmpty()) {
            System.out.println();
            System.out.println("  ALSO AVAILABLE  (not shown by oss --help; all of these still work)");
            hidden.forEach(System.out::println);
        }
        System.out.println();
        System.out.printf("  %d command(s) in total.%n%n", shown.size() + hidden.size());
        return 0;
    }
}
