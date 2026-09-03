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
package com.osscli.knowledge;

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
 * That a note says which directory its session ran in, correctly enough to open.
 *
 * <p>It did not. The directory was reconstructed from the folder name Claude Code writes, which
 * flattens {@code /}, {@code _} and {@code -} to a single {@code -}. A session in
 * {@code ~/Downloads/Spot_Rates-Worker-client-live-2026-08-27-54ee268} was filed as
 * {@code Downloads-Spot-Rates-Worker-client-live-2026-08-27-54ee268} -- a path that does not exist
 * -- while every one of the transcript's 150 entries carried the real one in {@code cwd}.
 */
class SessionCwdTest {

    private static Path transcript(Path dir, String name, String cwd) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        String body = cwd == null
                ? "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}\n"
                : "{\"type\":\"user\",\"cwd\":\"" + cwd.replace("\\", "\\\\")
                        + "\",\"message\":{\"content\":\"hi\"}}\n";
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    @DisplayName("an underscore in a real path survives into the project")
    void underscoresSurvive(@TempDir Path root) throws IOException {
        String home = System.getProperty("user.home");
        Path t = transcript(
                root.resolve("-Users-x-Downloads-Spot-Rates-Worker-client-live-54ee268"),
                "a.jsonl",
                home + "/Downloads/Spot_Rates-Worker-client-live-2026-08-27-54ee268");

        assertTrue(
                SessionNotes.projectOf(t).contains("Spot_Rates"),
                "the folder name cannot spell this; the transcript can: " + SessionNotes.projectOf(t));
    }

    @Test
    @DisplayName("the note carries a path that can be opened")
    void pathIsAbsolute(@TempDir Path root) throws IOException {
        String cwd = System.getProperty("user.home") + "/Downloads/Spot_Rates-Worker-client-live";
        Path t = transcript(root.resolve("anything"), "a.jsonl", cwd);

        assertEquals(cwd, SessionNotes.pathOf(t));
    }

    @Test
    @DisplayName("a transcript that records no cwd still yields the old answer")
    void fallbackIsUnchanged(@TempDir Path root) throws IOException {
        // Codex, Gemini and anything named in kb.json's `transcripts` need not carry the field, and
        // neither do Claude Code's own older files. Losing them to a stricter reader would be a
        // worse bug than the one being fixed.
        Path t = transcript(root.resolve("-Users-x-Projects-thing"), "a.jsonl", null);

        assertTrue(SessionNotes.projectOf(t).endsWith("Projects-thing"), SessionNotes.projectOf(t));
        assertEquals("", SessionNotes.pathOf(t));
    }

    @Test
    @DisplayName("this repository stays excluded once the real path is read")
    void theToolStillExcludesItself(@TempDir Path root) throws IOException {
        // The corner case that makes this whole change dangerous. oss-cli lives at `~/own repo/
        // oss-cli`, with a space. The folder name flattened it to `own-repo-oss-cli`, which is the
        // string in kb.json's exclude list. The real cwd spells it `own repo-oss-cli`, and a plain
        // substring test stops matching -- so the tool's own sessions, the ones deliberately
        // excluded, would quietly start filing themselves as knowledge every hour.
        Path t = transcript(
                root.resolve("-Users-x-own-repo-oss-cli"),
                "a.jsonl",
                System.getProperty("user.home") + "/own repo/oss-cli");

        String project = SessionNotes.projectOf(t);
        assertTrue(project.contains("own repo"), "the space is the truth: " + project);
        assertTrue(
                SessionNotes.matchable(project).contains("own-repo-oss-cli"),
                "and it must still match an exclusion written the old way: " + SessionNotes.matchable(project));
    }

    @Test
    @DisplayName("both spellings of one directory normalise to one string")
    void oldAndNewAgree() {
        for (List<String> pair : List.of(
                List.of("own repo-oss-cli", "own-repo-oss-cli"),
                List.of("Downloads-Spot_Rates-Worker-client-live", "Downloads-Spot-Rates-Worker-client-live"),
                List.of("Projects/a_b", "Projects-a-b"))) {
            assertEquals(
                    SessionNotes.matchable(pair.get(1)),
                    SessionNotes.matchable(pair.get(0)),
                    "a rule written against either spelling must match both: " + pair);
        }
    }

    @Test
    @DisplayName("a scratchpad is still recognised from its real path")
    void temporaryDirectoriesStillCaught(@TempDir Path root) throws IOException {
        for (String cwd : List.of(
                "/private/tmp",
                "/private/tmp/claude-501/-Users-x-own-repo-oss-cli/c5258d98/scratchpad/sess",
                "/var/folders/9k/abc/T/something")) {
            Path t = transcript(root.resolve("d" + Math.abs(cwd.hashCode())), "a.jsonl", cwd);
            String project = SessionNotes.projectOf(t);
            assertTrue(
                    SessionNotes.ranInATempDirectory(project)
                            || SessionNotes.matchable(project).contains("own-repo-oss-cli"),
                    cwd + " became " + project + ", which nothing would stop filing");
        }
    }

    @Test
    @DisplayName("a Windows path is read without its escapes")
    void windowsSeparators(@TempDir Path root) throws IOException {
        Path t = transcript(root.resolve("D--a-repo"), "a.jsonl", "D:\\a\\Spot_Rates-Worker");

        assertEquals("D:\\a\\Spot_Rates-Worker", SessionNotes.pathOf(t));
        assertFalse(SessionNotes.projectOf(t).contains("\\"), "separators are flattened: " + SessionNotes.projectOf(t));
        assertTrue(SessionNotes.projectOf(t).contains("Spot_Rates"), SessionNotes.projectOf(t));
    }

    @Test
    @DisplayName("a title that names nothing is qualified by where it ran")
    void namelessTitlesGainTheirProject() {
        // The note this whole change came from: eight kilobytes about a Python scraper -- carrier
        // modules, DrissionPage, Xvfb, a Postgres claim loop -- filed as "i need run this linux so
        // what need to change". Nothing in the name was searchable.
        java.util.List<com.osscli.memory.Sessions.Turn> turns = java.util.List.of(
                new com.osscli.memory.Sessions.Turn(true, "chek and tell what in the project"),
                new com.osscli.memory.Sessions.Turn(true, "i need run this linux so what need to change"));

        String title = SessionNotes.titleOf(
                turns, "fallback", null, "Downloads-Spot_Rates-Worker-client-live-2026-08-27-54ee268");

        assertTrue(title.contains("Spot_Rates"), "the project is free and it is a name: " + title);
    }

    @Test
    @DisplayName("noise at both ends of a project is not part of the name")
    void checkoutFoldersAndStampsAreDropped() {
        assertEquals(
                "Spot_Rates-Worker-client-live",
                SessionNotes.whereItRan("Downloads-Spot_Rates-Worker-client-live-2026-08-27-54ee268"));
        assertEquals("oss-cli", SessionNotes.whereItRan("Projects-oss-cli"));
        assertEquals("", SessionNotes.whereItRan("Downloads"));
        assertEquals("", SessionNotes.whereItRan(""));
    }

    @Test
    @DisplayName("a title that already names something is left alone")
    void goodTitlesAreNotPrefixed() {
        java.util.List<com.osscli.memory.Sessions.Turn> turns = java.util.List.of(new com.osscli.memory.Sessions.Turn(
                true, "the RollingFileAppender skips a file when the policy fires twice in one second"));

        String title = SessionNotes.titleOf(turns, "fallback", null, "Projects-log4j");

        assertTrue(title.startsWith("the RollingFileAppender"), "a symbol already makes it findable: " + title);
    }
}
