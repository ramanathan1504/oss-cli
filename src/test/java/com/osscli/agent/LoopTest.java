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
    @DisplayName("an attempt that names a real tool is honoured rather than corrected")
    void anUnderstandableAttemptIsHonoured(@TempDir Path dir) {
        // Every shape qwen2.5:0.5b produced on this machine, in order, including echoing the usage
        // line back on the third turn. All three name `read_file` and say what they want.
        //
        // These used to be rejected and corrected, three times, and then the run ended with
        // nothing. The model had done the hard part -- deciding what to look at -- and failed a
        // keyword. So they are read now, and each one becomes a real call that fails honestly
        // against a file that is not there.
        Function model = ask(
                "read_file:path:/some/invented/path.py",
                "read_file\"path\": \"path/to/project\"",
                "read_file - path: <file>   [from: <line>]   read part of a file in this project");

        Loop.Transcript t = loop(dir, false).run("what build tool is this?", model);

        assertTrue(
                t.steps().stream().anyMatch(step -> step.startsWith("read_file")),
                t.steps().toString());
        assertFalse(t.couldNotFollow(), "it understood the intent; that is not a failure to follow");
        assertTrue(t.unchecked(), "nothing was successfully read, so the answer must say so");
    }

    @Test
    @DisplayName("an attempt is never printed as the answer, even at the very end")
    void anAttemptIsNeverTheAnswer(@TempDir Path dir) {
        // The guarantee that matters, isolated: a model still reaching for a tool when it is asked
        // to conclude has not finished, and its reply is not an answer. Nonsense presented
        // confidently is the one output worse than a refusal.
        //
        // ````oss` with no tool named parses as nothing, so the loop corrects; and the conclude
        // pass gets the same shape back and must refuse rather than print it.
        Function model = ask(
                "```oss\nplease: read something\n```",
                "```oss\nplease: read something\n```",
                "```oss\nplease: read something\n```",
                "```oss\nplease: read something\n```",
                "```oss\nplease: read something\n```");

        Loop.Transcript t = loop(dir, false).run("what build tool is this?", model);

        assertTrue(t.couldNotFollow(), "it must say the model could not follow the format");
        assertEquals("", t.answer(), "and must not present the attempt as an answer");
        assertTrue(model.prompts.get(1).contains("that was not a block"), model.prompts.get(1));
    }

    @Test
    @DisplayName("prose that merely mentions a tool is an answer, not a failed attempt")
    void mentioningAToolIsNotAnAttempt(@TempDir Path dir) {
        // The inverse mistake: treating a finished answer as confusion because it used the word.
        Function model = ask("I used read_file on pom.xml and it is a Maven project.");

        Loop.Transcript t = loop(dir, false).run("what is it?", model);

        assertFalse(t.couldNotFollow());
        assertTrue(t.answer().contains("Maven project"), t.answer());
    }

    @Test
    @DisplayName("a step ceiling is honoured, because a flag that does nothing is worse than none")
    void theStepCeilingIsReal(@TempDir Path dir) throws IOException {
        // --steps was accepted and silently ignored: the Loop had no such parameter and always used
        // MAX_STEPS. The message printed on running out told the reader to "raise the ceiling with
        // --steps", which is advice that does nothing -- worse than the flag not existing.
        Files.writeString(dir.resolve("a.txt"), "x");
        java.util.function.Function<String, String> forever =
                prompt -> "```oss\ntool: read_file\npath: a.txt\nnonce: " + prompt.length() + "\n```";

        Loop.Transcript three = new Loop(new Workspace(dir), List.of(new ReadFile()), false, 3).run("go", forever);

        assertTrue(three.ranOut());
        assertEquals(3, three.steps().size(), "it must stop where it was told to");
    }

    @Test
    @DisplayName("a ceiling below one is one, not a loop that never looks")
    void aZeroCeilingStillLooksOnce(@TempDir Path dir) {
        Function model = ask("answered without looking");

        Loop.Transcript t = new Loop(new Workspace(dir), List.of(new ReadFile()), false, 0).run("go", model);

        assertEquals("answered without looking", t.answer(), "zero must not mean do nothing at all");
    }

    @Test
    @DisplayName("what this machine knows is in front of the model before it decides anything")
    void memoryLeadsEveryQuestion(@TempDir Path dir) {
        // recall exists and the model MAY call it, and "may" is the problem: one that does not
        // think to look answers from nothing, on a machine holding the note that solved it.
        Function model = ask("answered");
        new Loop(new Workspace(dir), List.of(new ReadFile()), false)
                .remembering(q ->
                        "— note:kafka-bug.md\n  changed break to return so a successful retry stops reporting an error")
                .run("kafka appender keeps failing", model);

        String prompt = model.prompts.get(0);
        assertTrue(prompt.contains("What this machine already holds"), prompt);
        assertTrue(prompt.contains("changed break to return"), "the fix itself, not a filename:\n" + prompt);
    }

    @Test
    @DisplayName("a known fix is offered before anything new is invented")
    void whatIsKnownComesFirst(@TempDir Path dir) {
        // The order is stated because the reverse is what a model does by default: answer from its
        // own knowledge and mention the reader's history as a footnote, if at all.
        Function model = ask("answered");
        new Loop(new Workspace(dir), List.of(new ReadFile()), false)
                .remembering(q -> "— note:something.md\n  the fix")
                .run("anything", model);

        String prompt = model.prompts.get(0);
        assertTrue(prompt.contains("If one of these already solved it"), prompt);
        assertTrue(prompt.contains("point at it by number before"), prompt);
        assertTrue(prompt.contains("Only when none of them applies"), prompt);
    }

    @Test
    @DisplayName("an unreadable corpus costs depth, never the answer")
    void memoryFailureIsNotFatal(@TempDir Path dir) {
        Function model = ask("answered anyway");
        Loop.Transcript t = new Loop(new Workspace(dir), List.of(new ReadFile()), false)
                .remembering(q -> {
                    throw new IllegalStateException("the store is locked");
                })
                .run("anything", model);

        assertEquals("answered anyway", t.answer());
        assertFalse(model.prompts.get(0).contains("already holds"), "no empty block either");
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
