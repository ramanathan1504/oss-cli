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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the transcript reader answers about a real transcript, and that the offline half of harvest
 * runs when the online half cannot.
 *
 * <p>Both of these were found by looking at the machine rather than at the code. The sessions
 * folder had never received a file, and the state left by the scheduled run said why.
 */
class SessionFilingTest {

    /** A transcript in the shape Claude Code actually writes. */
    private static Path transcript(Path dir, String name, String... lines) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return f;
    }

    private static String user(String text) {
        return "{\"type\":\"user\",\"timestamp\":\"2026-08-28T10:00:00Z\","
                + "\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
    }

    private static String toolUse(String path) {
        return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\","
                + "\"input\":{\"file_path\":\"" + path + "\"}}]}}";
    }

    @Test
    @DisplayName("the files a session opened are read out of its tool calls")
    void touchedPathsAreCollected(@TempDir Path home) throws IOException {
        // Which files, and nothing else about the call: the arguments, the output and the diffs
        // are the bulk of a transcript and none of it reads well later. Which files is the one
        // line that answers "where was I".
        Path f = transcript(
                home.resolve(".claude/projects/-Users-x-apache-logging-log4j2"),
                "s.jsonl",
                user("why does rollover skip a file"),
                toolUse("/src/RollingFileAppender.java"),
                toolUse("/src/RollingFileAppender.java"),
                toolUse("/src/TriggeringPolicy.java"));

        Sessions.Session s = Sessions.read(f);

        assertEquals(List.of("/src/RollingFileAppender.java", "/src/TriggeringPolicy.java"), s.touchedPaths());
    }

    @Test
    @DisplayName("a session's files are collected past the turn budget, because they are the whole session")
    void pathsOutliveTheTurnCap() throws IOException, Exception {
        // The turn cap exists so a note stays readable. Applying it to the file list as well would
        // report the first thirty turns' worth of files as everything the session touched.
        Path dir = Files.createTempDirectory("sessions");
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lines.add(user("turn number " + i + " about the rolling file appender"));
        }
        lines.add(toolUse("/src/Late.java"));
        Path f = transcript(dir, "s.jsonl", lines.toArray(new String[0]));

        Sessions.Session s = Sessions.read(f);

        assertEquals(Sessions.MAX_TURNS, s.raw().size(), "turns are capped");
        assertTrue(s.touchedPaths().contains("/src/Late.java"), "a file opened at turn 61 is still a file opened");
    }

    @Test
    @DisplayName("the rendered turns still read the way they always did")
    void renderingIsUnchanged(@TempDir Path home) throws IOException {
        Path f = transcript(home.resolve(".claude/projects/p"), "s.jsonl", user("why does rollover skip a file"));

        Sessions.Session s = Sessions.read(f);

        assertEquals(1, s.turns().size());
        assertTrue(s.turns().get(0).startsWith("**you:** "), s.turns().get(0));
        assertTrue(s.raw().get(0).user());
        assertEquals("why does rollover skip a file", s.raw().get(0).text());
    }

    @Test
    @DisplayName("the local half of harvest is not behind the check for a network")
    void offlineHarvestStillFilesSessions() throws IOException {
        // Measured, not imagined. The scheduled run of 2026-08-28 recorded:
        //   failed  could not reach GitHub: no network
        // and ~/.oss-cli/memory/sessions was empty -- because the GitHub failure returned before
        // the only call that reads local transcripts, which need no network and no token. The
        // method's own documentation had claimed the opposite since it was written.
        String body = Files.readString(
                Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"), StandardCharsets.UTF_8);
        int failure = body.indexOf("could not reach GitHub: ");
        assertTrue(failure > 0, "the GitHub failure branch moved; this guard needs rewriting");
        int returnOne = body.indexOf("return 1;", failure);
        int localHalf = body.indexOf("harvestSessions();", failure);

        assertTrue(localHalf > 0, "nothing runs the local half after GitHub fails");
        assertTrue(
                localHalf < returnOne,
                "the local transcripts need no network, so a GitHub failure must not skip them; "
                        + "this is exactly the bug that left the sessions folder empty for a month");
    }

    @Test
    @DisplayName("an excluded project is matched anywhere in the path, not only at the start")
    void exclusionMatchesAnywhere() {
        List<String> excluded = List.of("own-repo-oss-cli");

        assertTrue(BuiltinMemory.isExcluded("own-repo-oss-cli", excluded));
        // The directory check alone missed twelve of these: a session started from the home folder
        // edits this repository all afternoon under a path that names neither.
        assertTrue(BuiltinMemory.isExcluded("/Users/x/own-repo-oss-cli/src/Main.java", excluded));
        assertFalse(BuiltinMemory.isExcluded("apache-logging-log4j2", excluded));
        assertFalse(BuiltinMemory.isExcluded("", excluded));
    }

    @Test
    @DisplayName("nothing is excluded until somebody writes it down")
    void nothingIsDroppedByDefault() {
        // Whose work is incidental is a judgement about a person's life, not a property of the
        // software. An empty list means file everything.
        assertFalse(BuiltinMemory.isExcluded("own-repo-oss-cli", List.of()));
    }
}
