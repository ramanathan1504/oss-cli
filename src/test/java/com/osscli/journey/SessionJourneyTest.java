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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filing transcripts, run as somebody runs it, asserted on the notes that appear.
 *
 * <p>Written because the unit tests were not enough and said they were. {@code ranInATempDirectory}
 * had four passing tests and no call site: the edit adding it was lost, the function stayed
 * correct, and a real run filed a note called "Reply with exactly: OK" from a scratchpad
 * transcript. Every one of those tests still passed, because they tested the rule rather than the
 * command.
 *
 * <p>So this runs {@code oss memory sessions} in a subprocess against transcripts a test wrote, and
 * looks at the files on disk afterwards. A rule that is not wired in fails here, whatever its own
 * tests say.
 */
class SessionJourneyTest {

    /** One transcript, in the shape Claude Code writes: newline-delimited JSON, one entry a line. */
    private static void transcript(Path root, String project, String name, String... turns) throws IOException {
        Path dir = root.resolve(project);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (String turn : turns) {
            sb.append("{\"type\":\"user\",\"timestamp\":\"2026-08-28T10:00:00Z\",\"message\":{\"content\":")
                    .append("[{\"type\":\"text\",\"text\":\"")
                    .append(turn.replace("\"", "\\\""))
                    .append("\"}]}}\n");
        }
        // Padded past the 2 KB floor below which a transcript is somebody opening and closing a
        // window rather than working.
        sb.append("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"")
                .append("acknowledged. ".repeat(200))
                .append("\"}]}}\n");
        Files.writeString(dir.resolve(name), sb.toString(), StandardCharsets.UTF_8);
    }

    /** The configuration that points the tool at a test's archive and a test's transcripts. */
    private static void configure(Path home, Path archive, Path transcripts) throws IOException {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("kb.json"), """
                {
                  "archive": "%s",
                  "transcripts": ["%s"],
                  "topics": { "log4j": ["log4j", "appender", "rollover"] },
                  "exclude": ["some-tool-repo"]
                }
                """.formatted(json(archive), json(transcripts)), StandardCharsets.UTF_8);
    }

    /**
     * A path as a JSON string value.
     *
     * <p>Windows hands back {@code D:\a\oss-cli\...}, and a backslash is an escape character in
     * JSON -- so the configuration this test wrote was not valid JSON there, the tool fell back to
     * its defaults, and four journeys failed on a machine nobody had run them on. They passed on
     * macOS and Linux for the only reason that those separators need no escaping.
     */
    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private static List<Path> notesIn(Path archive) throws IOException {
        if (!Files.isDirectory(archive)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(archive)) {
            return walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static String namesOf(List<Path> notes) {
        StringBuilder sb = new StringBuilder();
        notes.forEach(n -> sb.append(n.getFileName()).append('\n'));
        return sb.toString();
    }

    @Test
    @DisplayName("a scratchpad transcript is never filed, and a real one is")
    void tempDirectoriesAreNotFiled(@TempDir Path home, @TempDir Path work) throws Exception {
        // The bug this file exists for. A rule with four passing unit tests and no call site let
        // this note through, called "Reply with exactly: OK".
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(transcripts, "private-tmp-claude-501-scratchpad", "a.jsonl", "Reply with exactly: OK");
        transcript(
                transcripts,
                "acme-log4j-fork",
                "b.jsonl",
                "the rollover appender skips a file when the policy fires twice");

        Journey.Ran ran = Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all");

        assertEquals(0, ran.code(), ran.all());
        List<Path> notes = notesIn(archive);
        assertFalse(
                namesOf(notes).toLowerCase(java.util.Locale.ROOT).contains("reply-with-exactly"),
                "a scratchpad transcript is this tool talking to itself:\n" + namesOf(notes));
        assertTrue(namesOf(notes).contains("rollover"), "and real work must still be filed:\n" + namesOf(notes));
    }

    @Test
    @DisplayName("an excluded project is not filed")
    void excludedProjectsAreNotFiled(@TempDir Path home, @TempDir Path work) throws Exception {
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(transcripts, "some-tool-repo", "a.jsonl", "building the appender tooling itself today");

        Journey.Ran ran = Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(notesIn(archive).isEmpty(), "kb.json excluded it:\n" + namesOf(notesIn(archive)));
    }

    @Test
    @DisplayName("two sessions naming one pull request become one note, not two")
    void oneSubjectOneNote(@TempDir Path home, @TempDir Path work) throws Exception {
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(transcripts, "acme-log4j-fork", "a.jsonl", "look at pr 4321 the rollover appender is wrong");
        transcript(transcripts, "acme-log4j-fork", "b.jsonl", "back on pr 4321 today, the appender rollover again");

        Journey.Ran ran = Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all");

        assertEquals(0, ran.code(), ran.all());
        List<Path> logs = notesIn(archive).stream()
                .filter(p -> p.getFileName().toString().contains("4321"))
                .toList();
        assertEquals(1, logs.size(), "one pull request is one note:\n" + namesOf(notesIn(archive)));
        String text = Files.readString(logs.get(0));
        assertEquals(2, text.split("<!-- session:", -1).length - 1, "both sessions belong in it:\n" + text);
    }

    @Test
    @DisplayName("running twice does not duplicate a session's section")
    void filingIsIdempotent(@TempDir Path home, @TempDir Path work) throws Exception {
        // The hourly job re-reads a session every time it grows. Without replace-in-place a file
        // would gain a block an hour for as long as somebody kept working.
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(transcripts, "acme-log4j-fork", "a.jsonl", "pr 4321 the rollover appender misbehaves");

        assertEquals(
                0,
                Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all")
                        .code());
        assertEquals(
                0,
                Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all")
                        .code());

        Path log = notesIn(archive).stream()
                .filter(p -> p.getFileName().toString().contains("4321"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no note was filed"));
        assertEquals(
                1,
                Files.readString(log).split("<!-- session:", -1).length - 1,
                "a second run must replace the section, not append another");
    }

    @Test
    @DisplayName("the tool's own summarising prompt is never filed as knowledge")
    void theToolDoesNotFileItsOwnPrompts(@TempDir Path home, @TempDir Path work) throws Exception {
        // Asking a command-line tool to summarise a transcript creates a session of its own, which
        // the next run reads. 229 notes came back that way on a real machine, once an hour.
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(
                transcripts,
                "acme-log4j-fork",
                "a.jsonl",
                "Below is a transcript of one working session on log4j. Write at most four sentences.");

        Journey.Ran ran = Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(
                notesIn(archive).isEmpty(),
                "a machine writing notes about its own notes:\n" + namesOf(notesIn(archive)));
    }

    @Test
    @DisplayName("nothing is filed under a folder named after the program that produced it")
    void theToolIsNeverAFolder(@TempDir Path home, @TempDir Path work) throws Exception {
        Path archive = work.resolve("archive");
        Path transcripts = work.resolve("transcripts");
        configure(home, archive, transcripts);
        transcript(transcripts, "acme-log4j-fork", "a.jsonl", "the rollover appender skips a file on the hour");

        assertEquals(
                0,
                Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "sessions", "--all")
                        .code());

        for (Path note : notesIn(archive)) {
            String path = note.toString().toLowerCase(java.util.Locale.ROOT);
            assertFalse(
                    path.contains("claude") || path.contains("codex") || path.contains("gemini"),
                    "the tool is a field on the note, never a folder: " + note);
        }
        assertFalse(notesIn(archive).isEmpty(), "and something was filed");
    }
}
