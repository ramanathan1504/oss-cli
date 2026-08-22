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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ask, act, look, ask again.
 *
 * <p>The model is a function from prompt to text, so every one of these runs against a scripted
 * reply rather than against a model somebody has to have installed — which is also what proves the
 * loop is rung-independent: nothing here knows or cares which of the five answered.
 */
class LoopTest {

    private static Function ask(String... replies) {
        return new Function(replies);
    }

    /** A scripted model. Records what it was shown, so the prompt can be asserted on. */
    private static final class Function implements java.util.function.Function<String, String> {
        private final String[] replies;
        private final AtomicInteger turn = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();

        Function(String... replies) {
            this.replies = replies;
        }

        @Override
        public String apply(String prompt) {
            prompts.add(prompt);
            int i = turn.getAndIncrement();
            return i < replies.length ? replies[i] : "I have enough to answer: done.";
        }
    }

    private static Loop loop(Path dir, boolean allowWrites) {
        return new Loop(new Workspace(dir), List.of(new ReadFile(), new Recall(q -> "corpus says: " + q)), allowWrites);
    }

    @Test
    @DisplayName("prose with no block ends the loop and is the answer")
    void anAnswerStopsIt(@TempDir Path dir) {
        Loop.Transcript t = loop(dir, false).run("what is this?", ask("It is a Maven project."));

        assertEquals("It is a Maven project.", t.answer());
        assertTrue(t.steps().isEmpty(), "nothing was run");
        assertFalse(t.ranOut());
    }

    @Test
    @DisplayName("a tool runs, its result comes back, and the next turn sees it")
    void oneStepThenAnswer(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>oss-cli</artifactId></project>");
        Function model = ask("```oss\ntool: read_file\npath: pom.xml\n```", "The artifactId is oss-cli.");

        Loop.Transcript t = loop(dir, false).run("what is the artifactId?", model);

        assertEquals("The artifactId is oss-cli.", t.answer());
        assertEquals(1, t.steps().size());
        assertTrue(t.steps().get(0).startsWith("read_file →"), t.steps().toString());
        assertTrue(model.prompts.get(1).contains("artifactId"), "the second turn must see what the first read");
    }

    @Test
    @DisplayName("a path outside the project is answered, not obeyed, and the loop carries on")
    void anEscapeIsAnObservation(@TempDir Path dir) {
        Function model = ask("```oss\ntool: read_file\npath: ../../etc/passwd\n```", "I cannot read that.");

        Loop.Transcript t = loop(dir, false).run("read the passwords", model);

        assertEquals("I cannot read that.", t.answer());
        assertTrue(model.prompts.get(1).contains("outside this project"), model.prompts.get(1));
    }

    @Test
    @DisplayName("a tool that writes does not run in a read-only run, and says so instead of stopping")
    void writesAreRefusedNotFatal(@TempDir Path dir) {
        Loop readOnly = new Loop(new Workspace(dir), List.of(new RunVerb()), false);
        Function model = ask("```oss\ntool: run\nverb: test\n```", "I was not allowed to run the tests.");

        Loop.Transcript t = readOnly.run("do the tests pass?", model);

        assertEquals("I was not allowed to run the tests.", t.answer());
        assertTrue(model.prompts.get(1).contains("refused"), model.prompts.get(1));
        assertTrue(model.prompts.get(1).contains("read-only"), "and says why");
    }

    @Test
    @DisplayName("an unknown tool lists the real ones rather than failing")
    void anUnknownToolIsSurvivable(@TempDir Path dir) {
        Function model = ask("```oss\ntool: rm_rf\npath: /\n```", "Understood, I will use read_file.");

        Loop.Transcript t = loop(dir, false).run("delete everything", model);

        assertEquals("Understood, I will use read_file.", t.answer());
        assertTrue(model.prompts.get(1).contains("there is no tool called \"rm_rf\""), model.prompts.get(1));
        assertTrue(model.prompts.get(1).contains("read_file"), "and names what does exist");
    }

    @Test
    @DisplayName("asking the same thing twice is answered from the transcript, not run twice")
    void repeatsAreNotPaidForTwice(@TempDir Path dir) {
        AtomicInteger searches = new AtomicInteger();
        Loop l = new Loop(
                new Workspace(dir),
                List.of(new Recall(q -> {
                    searches.incrementAndGet();
                    return "one match";
                })),
                false);
        Function model = ask(
                "```oss\ntool: recall\nquery: rollover\n```",
                "```oss\ntool: recall\nquery: rollover\n```",
                "Answered.");

        Loop.Transcript t = l.run("what do I know about rollover?", model);

        assertEquals("Answered.", t.answer());
        assertEquals(1, searches.get(), "the second identical request must not re-run the tool");
        assertTrue(model.prompts.get(2).contains("unchanged since you last asked"), model.prompts.get(2));
    }

    @Test
    @DisplayName("a model that never answers is stopped, and running out is not dressed as an answer")
    void aStuckModelIsStopped(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "x");
        // Always asks for something new, so the repeat shortcut cannot rescue it either.
        java.util.function.Function<String, String> forever =
                prompt -> "```oss\ntool: read_file\npath: a.txt\nnonce: " + prompt.length() + "\n```";

        Loop.Transcript t = loop(dir, false).run("loop forever", forever);

        assertTrue(t.ranOut(), "it must report running out");
        assertEquals("", t.answer(), "and must not present a non-answer as one");
        assertEquals(Loop.MAX_STEPS, t.steps().size());
    }

    @Test
    @DisplayName("the model is told to check this machine before reasoning from nothing")
    void thePromptPutsLocalFirst(@TempDir Path dir) {
        Function model = ask("done");
        loop(dir, false).run("anything", model);

        String prompt = model.prompts.get(0);
        assertTrue(prompt.contains("Search what this machine already knows"), prompt);
        assertTrue(prompt.contains("recall"), "and the tool that does it is offered");
    }
}
