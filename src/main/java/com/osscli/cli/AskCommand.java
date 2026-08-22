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

    @Option(names = "--model", description = "Local model to use when no engine was named")
    private String model;

    @Option(names = "--steps", description = "How many looks it may take before it must answer")
    private int steps = Loop.MAX_STEPS;

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

        var chosen = Rungs.forThisMachine(resolved).orElse(null);
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

        List<Tool> tools = List.of(new ReadFile(), new Recall(AskCommand::searchThisMachine), new RunVerb());
        // Said before it starts, not after: the rung that answers is the answer to "whose model saw
        // my code", and finding that out afterwards is finding it out too late.
        System.out.println("  " + chosen.label() + " · " + workspace.root() + (allowRun ? "" : " · read-only"));
        System.out.println();

        Loop.Transcript transcript = new Loop(workspace, tools, allowRun).run(asked, chosen.ask());

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
            System.out.println("  Stopped after " + transcript.steps().size() + " looks without an answer.");
            System.out.println("  Ask something narrower, or raise the ceiling with --steps.");
            return 1;
        }
        System.out.println(transcript.answer());
        return 0;
    }

    /**
     * What this machine already knows, as one string.
     *
     * <p>Static and defensive: a corpus that cannot be read is a sentence the loop reads and works
     * around, never an exception that ends somebody's question.
     */
    private static String searchThisMachine(String query) {
        try {
            // The same index `oss search` uses, over the issues already synced. Building it per
            // call is what `search` does too; sharing the construction would mean caching a corpus
            // across a process that runs one command and exits.
            java.util.Map<String, com.osscli.model.Issue> issues = new java.util.LinkedHashMap<>();
            for (String repository : com.osscli.storage.SqliteStorage.loadMonitoredRepositories()) {
                for (com.osscli.model.Issue issue : com.osscli.storage.SqliteStorage.loadIssues(repository)) {
                    issues.put(repository + "#" + issue.number(), issue);
                }
            }
            if (issues.isEmpty()) {
                return "nothing is synced on this machine yet — oss sync --all";
            }
            com.osscli.retrieval.TextIndex index = new com.osscli.retrieval.TextIndex();
            issues.forEach((key, issue) -> index.add(key, issue.title(), issue.body()));
            index.build();

            List<com.osscli.retrieval.TextIndex.Hit> hits = index.search(query, 8);
            if (hits.isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder(hits.size() + " of " + issues.size() + " indexed items match:\n");
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
