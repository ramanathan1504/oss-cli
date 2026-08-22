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
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first tool here that can destroy something, so these are mostly about what it refuses.
 *
 * <p>Every case ends by asserting what is on disk. A test that only checked the returned string
 * would pass for a tool that said "applied" and wrote nothing, and pass just as happily for one
 * that said "declined" and wrote anyway.
 */
class EditFileTest {

    private final ByteArrayOutputStream shown = new ByteArrayOutputStream();

    private EditFile tool(boolean answer) {
        return new EditFile(question -> answer, new PrintStream(shown, true, StandardCharsets.UTF_8));
    }

    private static Action edit(String path, String find, String replace) {
        return Action.firstIn(
                        "```oss\ntool: edit\npath: " + path + "\nfind: " + find + "\nreplace: " + replace + "\n```")
                .orElseThrow();
    }

    @Test
    @DisplayName("a confirmed edit is applied, and only where it was told to")
    void yesWrites(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Config.java");
        Files.writeString(file, "int timeout = 30;\nint retries = 3;\n");

        String result = tool(true).run(edit("Config.java", "timeout = 30", "timeout = 90"), new Workspace(dir));

        assertTrue(result.startsWith("applied"), result);
        assertEquals("int timeout = 90;\nint retries = 3;\n", Files.readString(file));
    }

    @Test
    @DisplayName("a declined edit changes nothing at all")
    void noWritesNothing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Config.java");
        String before = "int timeout = 30;\n";
        Files.writeString(file, before);

        String result = tool(false).run(edit("Config.java", "30", "90"), new Workspace(dir));

        assertTrue(result.startsWith("declined"), result);
        assertEquals(before, Files.readString(file), "the file must be untouched to the byte");
    }

    @Test
    @DisplayName("the diff is shown before the question, and comes from the bytes")
    void theDiffIsShownFirst(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree\n");

        tool(false).run(edit("a.txt", "two", "TWO"), new Workspace(dir));

        String printed = shown.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("- 2  two"), printed);
        assertTrue(printed.contains("+ 2  TWO"), printed);
        assertTrue(printed.contains("a.txt"), printed);
    }

    @Test
    @DisplayName("text that appears twice is refused rather than applied to the first one")
    void ambiguityIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Repeated.java");
        String before = "int x = 1;\nint y = 1;\n";
        Files.writeString(file, before);

        // A model working from memory of a file it half-read hands over a fragment like this. "The
        // first occurrence" is not a rule anybody would choose if they were asked.
        String result = tool(true).run(edit("Repeated.java", "= 1;", "= 2;"), new Workspace(dir));

        assertTrue(result.contains("appears 2 times"), result);
        assertEquals(before, Files.readString(file), "nothing may be written when it is ambiguous");
    }

    @Test
    @DisplayName("text that is not there is refused, and says the file may have been misremembered")
    void aMissedMatchIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello\n");

        String result = tool(true).run(edit("a.txt", "goodbye", "hi"), new Workspace(dir));

        assertTrue(result.contains("not in"), result);
        assertEquals("hello\n", Files.readString(file));
    }

    @Test
    @DisplayName("a file outside the project is refused before anything is read")
    void outsideTheWorkspaceIsRefused(@TempDir Path dir) throws IOException {
        Path outside = Files.createTempDirectory("outside").resolve("secrets.txt");
        Files.writeString(outside, "not yours\n");

        String result = tool(true).run(edit("../" + outside.getFileName(), "not", "very"), new Workspace(dir));

        assertTrue(result.contains("outside this project"), result);
        assertEquals("not yours\n", Files.readString(outside));
    }

    @Test
    @DisplayName("an empty replacement deletes the text, which is a real edit and not a mistake")
    void deletingIsAllowed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "keep this DELETE ME too\n");

        // Quoted, because the leading space is the point and an unquoted value is stripped.
        String result = tool(true).run(edit("a.txt", "\" DELETE ME\"", "\"\""), new Workspace(dir));

        assertTrue(result.startsWith("applied"), result);
        assertEquals("keep this too\n", Files.readString(file));
    }

    @Test
    @DisplayName("the loop will not offer it at all unless the run allows writing")
    void theLoopGateComesFirst(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "one\n");
        Loop readOnly = new Loop(new Workspace(dir), java.util.List.of(tool(true)), false);

        Loop.Transcript t = readOnly.run(
                "change it",
                prompt -> prompt.contains("you asked")
                        ? "I was not allowed."
                        : "```oss\ntool: edit\npath: a.txt\nfind: one\nreplace: two\n```");

        assertEquals("I was not allowed.", t.answer());
        assertEquals("one\n", Files.readString(dir.resolve("a.txt")), "the tool never ran");
        assertFalse(shown.toString(StandardCharsets.UTF_8).contains("- 1"), "and no diff was even shown");
    }

    @Test
    @DisplayName("with no terminal there is nothing to confirm at, so nothing is written")
    void noTerminalMeansNo(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        // Confirm.never() is what a run gets when it cannot ask -- a pipe treated as consent is how
        // files get rewritten in a job nobody was watching.
        EditFile piped = new EditFile(Confirm.never(), new PrintStream(shown, true, StandardCharsets.UTF_8));

        String result = piped.run(edit("a.txt", "one", "two"), new Workspace(dir));

        assertTrue(result.startsWith("declined"), result);
        assertEquals("one\n", Files.readString(file));
    }
}
