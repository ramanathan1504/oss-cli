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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model proposes an edit, a person is shown a diff, and only then is a file changed.
 *
 * <p>The chain behind {@code oss ask --allow-edit}, driven end to end: the loop reads the model's
 * block, {@link EditFile} resolves it against the workspace, {@link Diff} renders it, {@link
 * Confirm} is asked, and the file on disk is or is not rewritten. Each of those has its own tests.
 * The order they happen in, and whether a "no" really means the bytes are untouched, did not.
 *
 * <p><b>Not run through the command line, and the reason is worth stating.</b> {@code oss ask}
 * chooses a rung from what the machine has, and the address of a local model lives in the SQLite
 * config rather than in the environment -- so a journey would have to stand up a fake Ollama and
 * write a config row to reach it, and would then be testing the rung ladder rather than the edit.
 * The model is faked here instead, at the seam the loop already has for it, and everything below
 * that seam is the real code.
 */
class EditJourneyTest {

    /** A model that says these things, in order, and then answers in prose. */
    private static Function<String, String> saying(String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        return prompt -> queue.isEmpty() ? "Done — the null check is in place." : queue.poll();
    }

    private record Run(Loop.Transcript transcript, String shown) {}

    private static Run ask(Path root, Confirm confirm, Function<String, String> model) {
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(shown, true, StandardCharsets.UTF_8);
        Loop loop = new Loop(new Workspace(root), List.of(new ReadFile(), new EditFile(confirm, out)), true, 12);
        return new Run(loop.run("fix the null check", model), shown.toString(StandardCharsets.UTF_8));
    }

    private static final String EDIT = """
            ```oss
            tool: edit
            path: Parser.java
            find: "    return name.length();"
            replace: "    return name == null ? 0 : name.length();"
            ```
            """;

    private static void aFile(Path root) throws Exception {
        Files.writeString(root.resolve("Parser.java"), """
                class Parser {
                  int width(String name) {
                    return name.length();
                  }
                }
                """);
    }

    @Test
    @DisplayName("yes: the diff is shown first, then the file changes")
    void confirmedEditIsWritten(@TempDir Path root) throws Exception {
        aFile(root);
        List<String> asked = new ArrayList<>();

        Run run = ask(
                root,
                question -> {
                    asked.add(question);
                    return true;
                },
                saying(EDIT));

        String after = Files.readString(root.resolve("Parser.java"));
        assertTrue(
                after.contains("name == null ? 0 : name.length()"),
                "the edit was confirmed and not applied:\n" + after);
        assertFalse(asked.isEmpty(), "the file was changed without anybody being asked");
        // Shown BEFORE the answer, and as a diff -- an edit a person approves without seeing is a
        // permission they did not really give.
        assertTrue(run.shown().contains("-") && run.shown().contains("+"), "no diff was rendered: " + run.shown());
        assertTrue(run.shown().contains("name.length()"), run.shown());
    }

    @Test
    @DisplayName("no: the file is byte-for-byte what it was")
    void refusedEditChangesNothing(@TempDir Path root) throws Exception {
        aFile(root);
        byte[] before = Files.readAllBytes(root.resolve("Parser.java"));

        Run run = ask(root, question -> false, saying(EDIT));

        assertEquals(
                new String(before, StandardCharsets.UTF_8),
                Files.readString(root.resolve("Parser.java")),
                "a refused edit changed the file");
        // And the loop carries on rather than dying, so the model can say something else.
        assertFalse(run.transcript().answer().isBlank(), "a refusal ended the run instead of continuing it");
    }

    @Test
    @DisplayName("without --allow-edit the tool is refused before anybody is asked")
    void readOnlyRunNeverAsks(@TempDir Path root) throws Exception {
        aFile(root);
        byte[] before = Files.readAllBytes(root.resolve("Parser.java"));
        List<String> asked = new ArrayList<>();

        // allowWrites = false is what a run without the flag has. The tool is registered, so the
        // model can still ask for it -- and must be told no without a person being interrupted.
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        Loop loop = new Loop(
                new Workspace(root),
                List.of(new EditFile(
                        question -> {
                            asked.add(question);
                            return true;
                        },
                        new PrintStream(shown, true, StandardCharsets.UTF_8))),
                false,
                12);
        loop.run("fix the null check", saying(EDIT));

        assertTrue(asked.isEmpty(), "a read-only run asked for confirmation: " + asked);
        assertEquals(
                new String(before, StandardCharsets.UTF_8),
                Files.readString(root.resolve("Parser.java")),
                "a read-only run changed a file");
    }

    @Test
    @DisplayName("an edit outside the workspace is refused whatever the answer would have been")
    void anEditOutsideTheWorkspaceIsRefused(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        aFile(root);
        Path outside = elsewhere.resolve("secrets.txt");
        Files.writeString(outside, "untouched");

        String escape = "```oss\ntool: edit\npath: " + outside.toAbsolutePath()
                + "\nfind: \"untouched\"\nreplace: \"owned\"\n```";
        Run run = ask(root, question -> true, saying(escape));

        assertEquals(
                "untouched", Files.readString(outside), "a path outside the workspace was written: " + run.shown());
    }
}
