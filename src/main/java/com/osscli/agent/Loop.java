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
    private final int maxSteps;

    public Loop(Workspace workspace, List<Tool> tools, boolean allowWrites) {
        this(workspace, tools, allowWrites, MAX_STEPS);
    }

    /**
     * @param maxSteps how many looks before it must answer; below one there is no loop at all, so
     *     anything smaller is treated as one rather than as an instruction to do nothing
     */
    public Loop(Workspace workspace, List<Tool> tools, boolean allowWrites, int maxSteps) {
        this.workspace = workspace;
        this.allowWrites = allowWrites;
        this.maxSteps = Math.max(1, maxSteps);
        for (Tool t : tools) {
            this.tools.put(t.name(), t);
        }
    }

    /**
     * How many malformed attempts are worth correcting before calling it.
     *
     * <p>Two, because the first is a mistake and the third is a model that cannot do this. A
     * qwen2.5:0.5b asked to follow the format answered
     * {@code read_file:path:/path/to/your/project/root/root/src/main/cpp/tool/oss.py} -- an attempt
     * at a tool call, in no format at all -- and the loop handed it back as the answer. Nonsense
     * presented confidently is the one output worse than a refusal.
     */
    static final int MAX_MALFORMED = 2;

    /**
     * Told as it happens, not collected and shown at the end.
     *
     * <p>A loop turn is a model call and a tool: seconds each, a minute or more together, and the
     * first version printed nothing until all of it was over. This repository's own rule is that
     * anything slower than a second reports what it is doing, and {@code hub} and {@code followup}
     * both learned it the same way -- a silent terminal is indistinguishable from a hung one.
     *
     * <p>A {@link java.util.function.Consumer} rather than the status line itself, so the loop owes
     * nothing to {@code ui} and every test can watch the same events without a terminal.
     */
    private java.util.function.Consumer<String> onStep = step -> {};

    public Loop watching(java.util.function.Consumer<String> onStep) {
        this.onStep = onStep == null ? step -> {} : onStep;
        return this;
    }

    /**
     * What this machine already knows about the question, put in front of the model unasked.
     *
     * <p>{@code recall} exists and the model may call it — but <em>may</em> is the problem. A model
     * that does not think to look answers from nothing, and the whole reason this loop lives inside
     * oss rather than beside it is that the answer to "have I already solved this" is on the same
     * disk. Asked about a Kafka appender that will not start, the useful reply begins <em>"you
     * fixed this last time by changing this in the config — try that first"</em>, and it cannot
     * begin that way if the corpus was never opened.
     *
     * <p>So every question starts with what a search for it returns. The tool stays, for the
     * follow-up searches only the model knows it needs.
     */
    private java.util.function.Function<String, String> memory = question -> "";

    public Loop remembering(java.util.function.Function<String, String> memory) {
        this.memory = memory == null ? question -> "" : memory;
        return this;
    }

    /** How the reader writes, so an answer sounds like them rather than like a manual. */
    private String voice = "";

    public Loop inTheVoice(String voice) {
        this.voice = voice == null ? "" : voice;
        return this;
    }

    /** What happened, in order, and the answer if there was one. */
    public record Transcript(String answer, List<String> steps, boolean ranOut, boolean couldNotFollow) {

        /** The ordinary case: an answer, no confusion. */
        public Transcript(String answer, List<String> steps, boolean ranOut) {
            this(answer, steps, ranOut, false);
        }
    }

    /**
     * Run until the model stops asking for things.
     *
     * @param question what the user typed
     * @param ask the rung that answers — prompt in, text out
     */
    public Transcript run(String question, Function<String, String> ask) {
        return run(question, ask, "");
    }

    /**
     * As above, continuing from what was said before.
     *
     * @param earlier the previous exchange, already rendered — an empty string for a fresh start
     */
    public Transcript run(String question, Function<String, String> ask, String earlier) {
        List<String> steps = new ArrayList<>();
        int malformed = 0;
        Map<String, String> alreadySeen = new LinkedHashMap<>();
        StringBuilder conversation = new StringBuilder();
        if (earlier != null && !earlier.isBlank()) {
            // Seeded, not concatenated onto the question: it belongs in the same place this run's
            // own steps go, so the model reads one history rather than a question with a preamble.
            conversation.append(earlier);
        }

        for (int step = 0; step < maxSteps; step++) {
            onStep.accept("thinking (" + (step + 1) + " of " + maxSteps + ")");
            String reply = ask.apply(prompt(question, conversation.toString()));
            Optional<Action> action = Action.firstIn(reply);
            if (action.isEmpty()) {
                if (looksLikeAnAttempt(reply)) {
                    // It tried to call a tool and could not spell it. Correct it, at the cost of a
                    // step, rather than printing the attempt as though it were an answer.
                    if (++malformed > MAX_MALFORMED) {
                        return new Transcript("", steps, false, true);
                    }
                    steps.add("(reply was not a tool block — asked again)");
                    conversation
                            .append("\n> that was not a block. Reply with exactly this and nothing else:\n")
                            .append("```oss\ntool: <name>\n<argument>: <value>\n```\n");
                    continue;
                }
                if (malformed > 0 && mentionsATool(reply)) {
                    // It has already failed the format once, and is still talking about tools
                    // rather than answering -- this one echoed the usage line back verbatim. A
                    // model in that state is not finished, and printing its reply as the answer is
                    // how nonsense gets presented confidently.
                    return new Transcript("", steps, false, true);
                }
                // No action asked for: the model is answering rather than working.
                return new Transcript(reply == null ? "" : reply.strip(), steps, false);
            }

            Action a = action.get();
            onStep.accept(a.tool() + " " + a.argument("path") + a.argument("query") + a.argument("verb"));
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

    /**
     * Whether a reply was reaching for a tool and missed the format.
     *
     * <p>Deliberately narrow: it must name a tool this loop actually has, and pair it with a colon.
     * Prose that merely mentions {@code read_file} while explaining an answer does not match,
     * because the whole point is to tell a confused model from a finished one.
     */
    private boolean looksLikeAnAttempt(String reply) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        String lower = reply.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("```oss")) {
            return true; // a fence it failed to close or fill
        }
        if (lower.contains("tool:")) {
            return true;
        }
        // A tool name followed immediately by punctuation is a call being attempted; a tool name
        // followed by a word is prose about one. qwen2.5:0.5b produced both shapes on this machine
        // within two turns -- `read_file:path:/...` and `read_file"path": "..."` -- so matching one
        // spelling at a time is chasing, not a rule.
        for (String tool : tools.keySet()) {
            int at = lower.indexOf(tool);
            while (at >= 0) {
                int next = at + tool.length();
                if (next < lower.length() && ":\"'({[=".indexOf(lower.charAt(next)) >= 0) {
                    return true;
                }
                at = lower.indexOf(tool, next);
            }
        }
        return false;
    }

    /** Whether the reply names any tool at all, however it spells it. */
    private boolean mentionsATool(String reply) {
        String lower = reply == null ? "" : reply.toLowerCase(java.util.Locale.ROOT);
        return tools.keySet().stream().anyMatch(lower::contains);
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
        // Only where it matters. A quoting rule stated for every argument invites a model to quote
        // paths too, and a path with quotes in it is a path that does not exist.
        if (tools.containsKey("edit")) {
            b.append("Quote a value to keep its exact spacing: find: \"    int x;\"\n");
        }
        b.append("When you can answer, reply in prose with no block at all.\n\n");
        b.append("Question: ").append(question).append('\n');

        // Before the model decides anything, what this machine already holds. Failures swallow to
        // an empty block: an unreadable corpus should cost the answer some depth, never the answer.
        String known;
        try {
            known = memory.apply(question);
        } catch (RuntimeException e) {
            known = "";
        }
        if (known != null && !known.isBlank()) {
            b.append("\nWhat this machine already holds about this:\n");
            b.append(known.strip()).append('\n');
            // The order matters and is stated, because the reverse is what a model does by default:
            // answer from its own knowledge and mention the user's history as a footnote, if at all.
            b.append("\nIf one of these already solved it, say so and point at it by number before\n");
            b.append("proposing anything new. Only when none of them applies should you answer from\n");
            b.append("scratch — and say that is what you are doing.\n");
        }
        if (!voice.isBlank()) {
            b.append('\n').append(voice.strip()).append('\n');
        }
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
