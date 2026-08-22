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

import com.osscli.agent.Loop;
import com.osscli.agent.ReadFile;
import com.osscli.agent.Recall;
import com.osscli.agent.RunVerb;
import com.osscli.agent.Rungs;
import com.osscli.agent.Tool;
import com.osscli.agent.Workspace;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * A question about the project you are standing in, answered by looking.
 *
 * <p>Every other command here answers from what was synced. This one can go and look: read a file,
 * search what this machine already knows, run the project's own tests — and decide what to do next
 * from what it found. It is the shape the other command-line agents have, with the difference that
 * matters on this machine already loaded: fifteen thousand issues, the threads they came from, and
 * the notes their owner wrote.
 *
 * <p><b>Read-only unless told otherwise.</b> {@code --allow-run} is the whole of the permission
 * model and it is deliberately one flag rather than a prompt per step: a question that is answered
 * by running the tests should be asked that way once, not negotiated twelve times. Nothing in the
 * built-in tool set writes to a file at all.
 */
@Command(
        name = "ask",
        mixinStandardHelpOptions = true,
        description = "Ask about this project, and let it look — reads files, your corpus, and the build")
public class AskCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "1..*", description = "What you want to know")
    private List<String> question;

    @Option(
            names = "--allow-run",
            description = "Let it run this project's own build or tests (never an arbitrary command)")
    private boolean allowRun;

    @Option(
            names = "--allow-edit",
            description = "Let it propose edits — each one is shown as a diff and confirmed by you")
    private boolean allowEdit;

    @Option(names = "--model", description = "Local model to use when no engine was named")
    private String model;

    @Option(names = "--steps", description = "How many looks it may take before it must answer")
    private int steps = Loop.MAX_STEPS;

    @Option(names = "--resume", description = "Continue the last ask in this directory")
    private boolean resume;

    @Override
    public Integer call() {
        String asked = String.join(" ", question);
        Workspace workspace = new Workspace(Path.of("").toAbsolutePath());

        // The same model the rest of the tool uses, not a name invented here. Hardcoding one made
        // this refuse on a machine with Ollama running and four models pulled -- none of them the
        // one the default named.
        String resolved = model;
        if (resolved == null || resolved.isBlank()) {
            try {
                resolved = com.osscli.storage.SqliteStorage.loadConfig("ollama.model.guidance");
            } catch (Exception e) {
                resolved = null;
            }
            if (resolved == null || resolved.isBlank()) {
                resolved = com.osscli.Defaults.GUIDANCE_MODEL;
            }
        }

        // Named engine wins outright -- `oss claude ask` is the choice already made, and asking
        // again would be ceremony. Otherwise: one rung is taken, several are offered, none refuses.
        var chosen = com.osscli.llm.Ai.mayEscalate()
                ? Rungs.forThisMachine(resolved).orElse(null)
                : pick(Rungs.available(resolved));
        if (chosen == null) {
            // Refused with both fixes named, rather than a loop that turns without producing
            // anything. The built-in model ranks and retrieves; it does not write sentences, and
            // deciding what to look at next is writing a sentence.
            System.err.println("  Nothing on this machine can drive a loop yet.");
            System.err.println();
            System.err.println("  The built-in model searches and ranks; it does not write. This needs one of:");
            System.err.println("    ollama serve            then  oss ask \"…\"");
            System.err.println("    oss claude ask \"…\"      or gemini, codex, junie — whichever you have");
            System.err.println();
            System.err.println("  Everything else still works without either:  oss search, oss hub, oss triage");
            return 1;
        }

        List<Tool> tools = new java.util.ArrayList<>(List.of(
                new ReadFile(),
                new Recall(AskCommand::searchThisMachine),
                new com.osscli.agent.AskOss(AskCommand::askOss),
                new RunVerb()));
        if (allowEdit) {
            // Only offered when it was asked for. A tool the model can see is a tool it will
            // propose, and proposing an edit on a read-only run wastes a step explaining why not.
            tools.add(new com.osscli.agent.EditFile(com.osscli.agent.Confirm.atTerminal(), System.out));
        }
        // Said before it starts, not after: the rung that answers is the answer to "whose model saw
        // my code", and finding that out afterwards is finding it out too late.
        // What it may do, said before it does anything. "read-only" is the important word and it
        // is the default, so it is the one that has to be visible without being looked for.
        String permission =
                allowEdit ? " · may edit, with your confirmation" : allowRun ? " · may run the build" : " · read-only";
        System.out.println("  " + chosen.label() + " · " + workspace.root() + permission);
        System.out.println();

        // The same status line hub and followup use, for the same reason: a loop turn is a model
        // call plus a tool, and the first version of this printed nothing until all of them were
        // over. A silent terminal is indistinguishable from a hung one.
        // Kept in chat_session and chat_turn -- the tables that already exist for exactly this,
        // durable the moment a turn is said rather than written out at the end. A second table for
        // "ask sessions" would be the same shape, a schema bump, and a release.
        long session = openSession(workspace, chosen.label());
        String earlier = resume ? earlierTurns(workspace) : "";
        if (resume && earlier.isBlank()) {
            System.out.println("  nothing to resume in this directory — starting fresh");
        }

        Loop.Transcript transcript;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("looking")) {
            transcript = new Loop(workspace, tools, allowRun || allowEdit, steps)
                    .watching(live::step)
                    .run(asked, chosen.ask(), earlier);
            live.done(transcript.steps().size() + (transcript.steps().size() == 1 ? " look" : " looks"));
        }

        for (String step : transcript.steps()) {
            System.out.println("  · " + step);
        }
        if (!transcript.steps().isEmpty()) {
            System.out.println();
        }
        if (transcript.couldNotFollow()) {
            // Named as what it is. A model too small to emit the format is not a broken oss and not
            // an unanswerable question, and telling somebody which of the three they have is the
            // whole job of this message.
            System.out.println("  " + chosen.label() + " could not produce a tool call in the required format.");
            System.out.println("  That is a limit of the model, not of the question.");
            System.out.println();
            System.out.println("  Try a larger local model, or name an engine:");
            System.out.println("    oss claude ask \"" + asked + "\"");
            return 1;
        }
        if (transcript.ranOut()) {
            int looks = transcript.steps().size();
            System.out.println("  Stopped after " + looks + (looks == 1 ? " look" : " looks") + " without an answer.");
            System.out.println("  Ask something narrower, or raise the ceiling with --steps.");
            return 1;
        }
        System.out.println(transcript.answer());
        remember(session, asked, transcript.answer());
        return 0;
    }

    /**
     * What this machine already knows, as one string.
     *
     * <p>Static and defensive: a corpus that cannot be read is a sentence the loop reads and works
     * around, never an exception that ends somebody's question.
     */
    /**
     * One rung, chosen the way the user would choose it.
     *
     * <p>The shape they asked for, and the one {@code Picker} already implements: several
     * available means ask, exactly one means take it and say so, none means fall back. What makes
     * this safe rather than merely convenient is the last clause — in a pipe, a script or cron
     * there is nobody to ask, and {@code Picker.canAsk()} is false there, so the local rung is used
     * and nothing reaches outward on a decision nobody made.
     */
    private static Rungs.Chosen pick(List<Rungs.Chosen> available) {
        if (available.isEmpty()) {
            return null;
        }
        if (available.size() == 1) {
            return available.get(0);
        }
        if (!com.osscli.ui.Picker.canAsk()) {
            // No terminal. The local rung if there is one, and never an external engine: silence is
            // not permission to send somebody's code to somebody else's computer.
            return available.stream()
                    .filter(c -> c.label().startsWith("local "))
                    .findFirst()
                    .orElse(null);
        }
        return com.osscli.ui.Picker.choose(
                "Which should answer?",
                available,
                Rungs.Chosen::label,
                c -> List.of(
                        c.label().startsWith("local ")
                                ? "Runs on this machine. Nothing you ask leaves it."
                                : "Sends this question, and what it reads, to that provider."));
    }

    /**
     * A session for this directory, or zero when the store cannot take one.
     *
     * <p>The workspace stands in for the repository and the issue number is zero: an ask is about a
     * place on disk rather than about one issue. Reusing the columns rather than adding a table
     * keeps this out of the migration chain entirely, which matters because a schema bump is a
     * release and an older binary refusing the store.
     */
    private static long openSession(Workspace workspace, String provider) {
        try {
            return com.osscli.storage.ChatSessionStore.open(workspace.root().toString(), 0, "oss ask", provider, null);
        } catch (Exception e) {
            // A conversation nobody can resume is worse than no conversation only if it is silent
            // about it; the answer itself is unaffected, so this does not stop the command.
            return 0;
        }
    }

    /** Both halves of this exchange, durable as soon as they exist. */
    private static void remember(long session, String question, String answer) {
        if (session == 0) {
            return;
        }
        try {
            com.osscli.storage.ChatSessionStore.append(session, com.osscli.model.ChatTurn.Role.USER, question);
            com.osscli.storage.ChatSessionStore.append(session, com.osscli.model.ChatTurn.Role.LOCAL, answer);
            com.osscli.storage.ChatSessionStore.end(session);
        } catch (Exception ignored) {
            // Same reason as above.
        }
    }

    /** What was said in this directory last time, rendered the way the loop renders its own steps. */
    private static String earlierTurns(Workspace workspace) {
        try {
            var recent =
                    com.osscli.storage.ChatSessionStore.recent(workspace.root().toString(), 0L, 2);
            StringBuilder b = new StringBuilder();
            for (var session : recent) {
                for (var turn : com.osscli.storage.ChatSessionStore.turns(session.id())) {
                    b.append("\n> ")
                            .append(
                                    turn.role() == com.osscli.model.ChatTurn.Role.USER
                                            ? "you asked earlier:"
                                            : "you answered:")
                            .append('\n')
                            .append(turn.content())
                            .append('\n');
                }
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Run one of oss's own read-only commands in this process and capture what it printed.
     *
     * <p>In-process rather than by spawning another {@code oss}: the store is already open here,
     * and a second process would pay the schema check and the connection again per question. The
     * output stream is swapped for the duration, which is safe because the loop is single-threaded
     * by construction and the live status line writes to stderr.
     */
    private static String askOss(List<String> argv, Integer timeoutSeconds) {
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream problems = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            // stderr too, or the inner command's own status line prints straight through this one:
            // asking `hub` printed seventeen "3 of 17 — apache/logging-log4j2#4240" lines into the
            // middle of the transcript, which is the nested command narrating itself to a reader
            // who asked a different question.
            System.setErr(new java.io.PrintStream(problems, true, java.nio.charset.StandardCharsets.UTF_8));
            com.osscli.Main.commandLine().execute(argv.toArray(new String[0]));
        } catch (Exception e) {
            return "error: oss " + String.join(" ", argv) + " failed — " + e.getMessage();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        String answer = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        String failed =
                problems.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
        // Kept, not discarded: a command that printed nothing and complained on stderr has told the
        // model exactly what it needs to know, and swallowing it would leave an unexplained blank.
        return answer.isBlank() && !failed.isEmpty() ? failed : answer;
    }

    /**
     * Built once per process, not once per question.
     *
     * <p>A loop asks {@code recall} more than once, and each call was reloading every issue from
     * every followed repository and rebuilding the index over all of them -- 15,938 rows on the
     * machine this was written for, three times in one answer. The process runs a single command
     * and exits, so once is exactly the right number of times.
     */
    private static com.osscli.retrieval.TextIndex index;

    private static int indexed;

    private static String searchThisMachine(String query) {
        try {
            // The same index `oss search` uses, over the issues already synced. Building it per
            // call is what `search` does too; sharing the construction would mean caching a corpus
            // across a process that runs one command and exits.
            if (index == null) {
                java.util.Map<String, com.osscli.model.Issue> issues = new java.util.LinkedHashMap<>();
                for (String repository : com.osscli.storage.SqliteStorage.loadMonitoredRepositories()) {
                    for (com.osscli.model.Issue issue : com.osscli.storage.SqliteStorage.loadIssues(repository)) {
                        issues.put(repository + "#" + issue.number(), issue);
                    }
                }
                if (issues.isEmpty()) {
                    return "nothing is synced on this machine yet — oss sync --all";
                }
                com.osscli.retrieval.TextIndex built = new com.osscli.retrieval.TextIndex();
                issues.forEach((key, issue) -> built.add(key, issue.title(), issue.body()));
                built.build();
                indexed = issues.size();
                index = built;
            }

            List<com.osscli.retrieval.TextIndex.Hit> hits = index.search(query, 8);
            if (hits.isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder(hits.size() + " of " + indexed + " indexed items match:\n");
            for (com.osscli.retrieval.TextIndex.Hit hit : hits) {
                b.append("  ").append(hit.id()).append("  ").append(hit.title()).append('\n');
            }
            return b.toString();
        } catch (Exception e) {
            // A sentence the loop reads and works around, never an exception that ends the question.
            return "the local corpus could not be searched: " + e.getMessage();
        }
    }
}
