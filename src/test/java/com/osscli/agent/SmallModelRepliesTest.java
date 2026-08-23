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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loop, driven by replies a real small model really produced.
 *
 * <p>Every string in this file was captured from {@code qwen2.5:0.5b} through Ollama on a
 * developer machine, asked "what does the review command check?" with the loop's own prompt. None
 * of it was written to make a point.
 *
 * <p>That distinction is the reason the file exists. The suite had 783 passing tests and the
 * command still failed the first time somebody ran it without a cloud key, because every existing
 * test fed the loop strings a person had written — and a person writing a test for a parser writes
 * input that parser was built to accept. A real model writes something else. It puts the tool name
 * on its own line as a bare key. It leaves the fence off entirely. It copies {@code <name>}
 * verbatim out of the instructions. It asks for the same thing five times with the same argument
 * missing.
 *
 * <p>Each test below is one of those, and each one failed before the change it is named for.
 */
class SmallModelRepliesTest {

    // ── captured verbatim, qwen2.5:0.5b via Ollama ──────────────────────────────────────────────

    /** A properly fenced block that names its tool as a bare key rather than as `tool: run`. */
    private static final String BARE_KEY = "```oss\nrun:\ncommand: \"review\"\n```";

    /** The right idea with no fence at all. */
    private static final String NO_FENCE = "read_file path: reviews folder";

    /** The instructions echoed back, placeholder and all. */
    private static final String ECHOED_TEMPLATE = "```oss\ntool: <name>\n<argument>: <value>\n```";

    /** Prose. The model has finished and is answering. */
    private static final String PROSE = "The review command checks the diff against what the project expects.";

    private static Loop loopIn(Path root, Deque<String> replies, List<String> asked) {
        return new Loop(new Workspace(root), List.of(new ReadFile(), new Recall(q -> "nothing yet")), false, 12);
    }

    private static Function<String, String> saying(Deque<String> replies, List<String> prompts) {
        return prompt -> {
            prompts.add(prompt);
            return replies.isEmpty() ? PROSE : replies.poll();
        };
    }

    // ── the parser ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a fenced block naming its tool as a bare key is still that tool")
    void bareKeyIsTheTool() {
        var action = Action.firstIn(BARE_KEY, java.util.Set.of("run", "read_file", "recall"));
        assertTrue(action.isPresent(), "a block reading `run:` names the run tool and nothing else");
        assertEquals("run", action.get().tool());
        assertEquals("review", action.get().argument("command"));
    }

    @Test
    @DisplayName("a tool name opening an unfenced line is read, with its argument")
    void unfencedLineIsRead() {
        var action = Action.firstIn(NO_FENCE, java.util.Set.of("read_file", "recall"));
        assertTrue(action.isPresent(), "`read_file path: x` is not ambiguous");
        assertEquals("read_file", action.get().tool());
        assertEquals("reviews folder", action.get().argument("path"));
    }

    @Test
    @DisplayName("leniency needs a tool that exists — it never invents one")
    void looseFormsMustNameARealTool() {
        // The same two shapes, naming something that is not registered. Both must be prose.
        assertTrue(Action.firstIn("```oss\ndelete_everything:\nnow: yes\n```", java.util.Set.of("read_file"))
                .isEmpty());
        assertTrue(Action.firstIn("deploy path: production", java.util.Set.of("read_file"))
                .isEmpty());
        // And ordinary prose that happens to contain a colon stays prose.
        assertTrue(Action.firstIn("The answer: the review command reads the diff.", java.util.Set.of("read_file"))
                .isEmpty());
    }

    @Test
    @DisplayName("strict parsing is unchanged when no tool set is given")
    void strictByDefault() {
        assertTrue(Action.firstIn(BARE_KEY).isEmpty(), "the canonical form is still the canonical form");
        assertTrue(Action.firstIn(NO_FENCE).isEmpty());
        assertTrue(Action.firstIn("```oss\ntool: read_file\npath: a.txt\n```").isPresent());
    }

    // ── the loop ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a model that cannot produce a block still answers, and says it never looked")
    void cannotFollowStillAnswers(@TempDir Path root) {
        Deque<String> replies = new ArrayDeque<>(List.of(ECHOED_TEMPLATE, ECHOED_TEMPLATE, ECHOED_TEMPLATE));
        List<String> prompts = new java.util.ArrayList<>();
        // After the corrections are exhausted the loop asks one different question, and the fake
        // answers it in prose the way the real model did.
        Loop.Transcript t = loopIn(root, replies, prompts).run("what does review check?", saying(replies, prompts));

        assertFalse(t.answer().isBlank(), "the corpus was in front of it; refusing threw that away");
        assertTrue(t.unchecked(), "an answer reached without opening a file must say so");
        assertFalse(t.couldNotFollow(), "it followed nothing, but it did answer");
    }

    @Test
    @DisplayName("the last question carries the evidence and drops the protocol")
    void theLastQuestionHasNoProtocol(@TempDir Path root) {
        Deque<String> replies = new ArrayDeque<>(List.of(ECHOED_TEMPLATE, ECHOED_TEMPLATE, ECHOED_TEMPLATE));
        List<String> prompts = new java.util.ArrayList<>();
        Loop loop = new Loop(new Workspace(root), List.of(new ReadFile()), false, 12)
                .remembering(q -> "#812 — you fixed this by ordering the locks the same way");
        loop.run("what does review check?", saying(replies, prompts));

        String last = prompts.get(prompts.size() - 1);
        assertTrue(last.contains("#812"), "the corpus is evidence and must survive into the last question");
        assertFalse(last.contains("```oss"), "showing the format a fourth time is what kept it apologising");
        assertFalse(
                last.contains("Reply with exactly"), "the correction is protocol and belongs to the turn it was for");
        assertTrue(last.contains("Do not ask for a file"), "it must be told there is nothing left to run a tool");
    }

    @Test
    @DisplayName("three failing calls in a row end the looking rather than spending twelve")
    void repeatedFailureStopsEarly(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("real.txt"), "content");
        // The real run asked for the same missing argument five times. Here it asks for a file
        // that is not there, which fails the same way and just as repeatably.
        String missing = "```oss\ntool: read_file\npath: nope-";
        Deque<String> replies = new ArrayDeque<>(List.of(
                missing + "1.txt\n```",
                missing + "2.txt\n```",
                missing + "3.txt\n```",
                missing + "4.txt\n```",
                missing + "5.txt\n```"));
        List<String> prompts = new java.util.ArrayList<>();
        Loop.Transcript t = new Loop(new Workspace(root), List.of(new ReadFile()), false, 12)
                .run("where is it?", saying(replies, prompts));

        long looks = t.steps().stream().filter(s -> s.startsWith("read_file")).count();
        assertTrue(
                looks <= Loop.MAX_CONSECUTIVE_FAILURES,
                "it must stop after " + Loop.MAX_CONSECUTIVE_FAILURES + " failures, not spend all twelve; spent "
                        + looks);
        assertFalse(t.ranOut(), "it stopped because it was failing, not because it ran out");
    }

    @Test
    @DisplayName("a run that found something says so, rather than claiming nothing was checked")
    void foundSomethingIsNotNothingChecked(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("real.txt"), "the content");
        // The exact shape a real run took: two successful recalls, then the model lost the format
        // for good. It printed "nothing was opened, read or checked" three lines under
        // `recall -> 8 match(es)`, which a reader can disprove by looking up the screen.
        Deque<String> replies = new ArrayDeque<>(List.of(
                "```oss\ntool: read_file\npath: real.txt\n```",
                "```oss\ntool: read_file\npath: nope1.txt\n```",
                "```oss\ntool: read_file\npath: nope2.txt\n```",
                "```oss\ntool: read_file\npath: nope3.txt\n```"));
        List<String> prompts = new java.util.ArrayList<>();
        Loop.Transcript t = new Loop(new Workspace(root), List.of(new ReadFile()), false, 12)
                .run("what is in it?", saying(replies, prompts));

        assertTrue(t.concluded(), "it stopped early rather than finishing normally");
        assertFalse(t.unchecked(), "it read a file successfully; saying otherwise is a caveat a reader can disprove");
        assertTrue(t.looked());
    }

    @Test
    @DisplayName("a failure between successes does not count towards the limit")
    void failuresMustBeConsecutive(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("real.txt"), "the content");
        Deque<String> replies = new ArrayDeque<>(List.of(
                "```oss\ntool: read_file\npath: missing.txt\n```",
                "```oss\ntool: read_file\npath: real.txt\n```",
                "```oss\ntool: read_file\npath: missing.txt\n```",
                "```oss\ntool: read_file\npath: real.txt\n```"));
        List<String> prompts = new java.util.ArrayList<>();
        Loop.Transcript t = new Loop(new Workspace(root), List.of(new ReadFile()), false, 12)
                .run("what is in it?", saying(replies, prompts));

        assertFalse(t.answer().isBlank(), "a run that kept finding things must finish normally");
        assertFalse(t.concluded(), "it looked, and it found things");
    }
}
