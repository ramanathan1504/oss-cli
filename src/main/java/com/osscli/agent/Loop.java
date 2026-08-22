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
package com.osscli.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Ask, act, look, ask again — until there is an answer or the budget runs out.
 *
 * <p>The model is a function from prompt to text and nothing more, which is what lets this be
 * tested against scripted replies rather than against a model somebody has to have installed. It is
 * also what keeps the loop honest about the ladder: whatever rung answered — a key, a provider's
 * tool, Ollama, the built-in — the loop is the same code, because the contract is text.
 *
 * <p>Three limits, and each one exists because its absence is a known failure mode:
 *
 * <ul>
 *   <li><b>Steps.</b> A model that cannot make progress will ask for the same file forever. The cap
 *       turns that into a stated stop rather than a bill.
 *   <li><b>Observation size.</b> Every result is trimmed before it goes back. The prompt is a
 *       budget, not an accumulator — the lesson {@code MemoryContext} was written for.
 *   <li><b>Writes.</b> A tool declaring {@link Tool#writes()} does not run unless the caller
 *       allowed it. The default is no, and refusing is reported to the model as an observation so
 *       it can choose something else instead of stopping.
 * </ul>
 *
 * <p>Repeats are answered from the transcript rather than re-run. Asking for the same file twice is
 * not a failure worth stopping for, but paying for it twice is waste the loop can see and avoid.
 */
public final class Loop {

    /** Enough to read a few files and run the tests; few enough that a stuck model stops. */
    public static final int MAX_STEPS = 12;

    /** Per result, before it goes back into the prompt. */
    static final int MAX_OBSERVATION = 4_000;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Workspace workspace;
    private final boolean allowWrites;

    public Loop(Workspace workspace, List<Tool> tools, boolean allowWrites) {
        this.workspace = workspace;
        this.allowWrites = allowWrites;
        for (Tool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    /** What happened, in order, and the answer if there was one. */
    public record Transcript(String answer, List<String> steps, boolean ranOut) {}

    /**
     * Run until the model stops asking for things.
     *
     * @param question what the user typed
     * @param ask the rung that answers — prompt in, text out
     */
    public Transcript run(String question, Function<String, String> ask) {
        List<String> steps = new ArrayList<>();
        Map<String, String> alreadySeen = new LinkedHashMap<>();
        StringBuilder conversation = new StringBuilder();

        for (int step = 0; step < MAX_STEPS; step++) {
            String reply = ask.apply(prompt(question, conversation.toString()));
            Optional<Action> action = Action.firstIn(reply);
            if (action.isEmpty()) {
                // No action asked for: the model is answering rather than working.
                return new Transcript(reply == null ? "" : reply.strip(), steps, false);
            }

            Action a = action.get();
            String key = a.tool() + " " + a.arguments();
            String observation;
            if (alreadySeen.containsKey(key)) {
                // Same request as before. Answered from the transcript, and told so -- repeating it
                // means the model missed the answer, not that the answer changed.
                observation = alreadySeen.get(key) + "\n(unchanged since you last asked)";
            } else {
                observation = perform(a);
                alreadySeen.put(key, observation);
            }

            steps.add(a.tool() + " → " + firstLine(observation));
            conversation
                    .append("\n> you asked:\n")
                    .append(a.raw())
                    .append("\n> result:\n")
                    .append(trim(observation))
                    .append('\n');
        }
        // Out of steps is not an answer, and must not be dressed as one.
        return new Transcript("", steps, true);
    }

    private String perform(Action action) {
        Tool tool = tools.get(action.tool());
        if (tool == null) {
            return "error: there is no tool called \"" + action.tool() + "\". Available: "
                    + String.join(", ", tools.keySet());
        }
        if (tool.writes() && !allowWrites) {
            // An observation rather than a stop: the model can pick something else, and the user
            // gets a loop that says what it would have done instead of one that dies.
            return "refused: \"" + tool.name() + "\" changes something, and this run is read-only.";
        }
        try {
            return tool.run(action, workspace);
        } catch (RuntimeException e) {
            // A tool that throws anyway is a bug in that tool, and it costs one step rather than
            // the run.
            return "error: " + tool.name() + " failed — " + e;
        }
    }

    /** What the model is told it can do, and how to say so. */
    String prompt(String question, String conversation) {
        StringBuilder b = new StringBuilder();
        b.append("You are answering a question about this project, on the user's own machine.\n\n");
        b.append("To look at something, reply with exactly one block and nothing after it:\n\n");
        b.append("```oss\ntool: <name>\n<argument>: <value>\n```\n\n");
        b.append("Available:\n");
        for (Tool t : tools.values()) {
            b.append("  ")
                    .append(t.usage())
                    .append(t.writes() && !allowWrites ? "   (refused: read-only run)" : "")
                    .append('\n');
        }
        b.append("\nSearch what this machine already knows before reasoning from nothing.\n");
        b.append("When you can answer, reply in prose with no block at all.\n\n");
        b.append("Question: ").append(question).append('\n');
        if (!conversation.isBlank()) {
            b.append("\nWhat you have done so far:\n").append(conversation);
        }
        return b.toString();
    }

    private static String trim(String observation) {
        if (observation.length() <= MAX_OBSERVATION) {
            return observation;
        }
        return observation.substring(0, MAX_OBSERVATION) + "\n… trimmed to " + MAX_OBSERVATION + " characters";
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.length() > 90 ? line.substring(0, 89) + "…" : line;
    }
}
