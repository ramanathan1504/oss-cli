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

import com.osscli.model.Provenance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a note says which conversation it came out of.
 *
 * <p>The conversation behind a note is usually still on disk and can still be reopened. Its id was
 * written into the note and nowhere else — for a running log, as a fence in the body — so finding
 * the note told you what had been settled and not where to go on with it.
 */
class SessionProvenanceTest {

    @Test
    @DisplayName("a running log names every session inside it, in its header")
    void runningLogsNameTheirSessions(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("issue-3161.md");

        SessionLog.append(log, "issue 3161", "log4j", "apache-log4j-main", "aaaa-1111", "first afternoon");
        SessionLog.append(log, "issue 3161", "log4j", "apache-log4j-main", "bbbb-2222", "the next morning");

        String text = Files.readString(log);
        String front = text.substring(0, text.indexOf("\n---", 3));
        assertTrue(front.contains("sessions: aaaa-1111, bbbb-2222"), front);
        // Both blocks are still there: the header is written from the fences, not instead of them.
        assertEquals(2, SessionLog.idsIn(text).size(), text);
        assertTrue(text.contains("first afternoon") && text.contains("the next morning"));
    }

    @Test
    @DisplayName("re-reading one session rewrites its block and leaves the header right")
    void rereadingASessionDoesNotDoubleTheHeader(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("issue-3161.md");
        SessionLog.append(log, "issue 3161", "log4j", "", "aaaa-1111", "half the afternoon");
        SessionLog.append(log, "issue 3161", "log4j", "", "aaaa-1111", "all of the afternoon");

        String text = Files.readString(log);
        assertEquals(1, SessionLog.idsIn(text).size(), text);
        assertEquals(1, text.split("sessions: ", -1).length - 1, "the header gained a second sessions line");
        assertTrue(text.contains("all of the afternoon"));
        assertFalse(text.contains("half the afternoon"));
    }

    @Test
    @DisplayName("a log written before this existed gains the header without being touched again")
    void olderLogsAreBackfilled(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j");
        Files.createDirectories(folder);
        Path old = folder.resolve("issue-3161.md");
        Files.writeString(old, """
                ---
                title: issue 3161
                topic: log4j
                kind: running-log
                source: session-log
                ---

                <!-- session:18fed060-fc29-4077-94ce-d72be8a6479b -->
                ## 2026-09-15 · issue 3161
                <!-- /session:18fed060-fc29-4077-94ce-d72be8a6479b -->
                """, StandardCharsets.UTF_8);

        assertEquals(1, SessionLog.backfill(projects));

        String text = Files.readString(old);
        assertTrue(text.contains("sessions: 18fed060-fc29-4077-94ce-d72be8a6479b"), text);
        // Twice is one pass and no writes: an archive under git must not record a commit a day for
        // a field that did not change.
        assertEquals(0, SessionLog.backfill(projects));
    }

    @Test
    @DisplayName("a note that came from no session is left alone")
    void notesWithoutSessionsAreUntouched(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("hand-written.md"), "---\ntitle: mine\n---\n\n# mine\n");

        assertEquals(0, SessionLog.backfill(projects));
        assertFalse(Files.readString(folder.resolve("hand-written.md")).contains("sessions:"));
    }

    @Test
    @DisplayName("both spellings of the field are read, and quotes do not survive into an id")
    void provenanceIsReadFromEitherKindOfNote() {
        Provenance single = Provenance.of("---\ntool: claude-code\nsession: aaaa-1111\nsource: session\n---\n\n# x\n");
        assertEquals("aaaa-1111", single.sessions());
        assertEquals("claude-code", single.tool());

        Provenance log = Provenance.of("---\nkind: running-log\nsessions: aaaa-1111, bbbb-2222\ntools: codex\n---\n");
        assertEquals("aaaa-1111, bbbb-2222", log.sessions());
        assertEquals("aaaa-1111", log.firstSession());
        assertEquals("codex", log.tool());

        // An id that keeps its quotes matches nothing when it is pasted after --resume.
        assertEquals(
                "aaaa-1111", Provenance.of("---\nsession: \"aaaa-1111\"\n---\n").sessions());

        assertFalse(Provenance.of("---\ntitle: mine\n---\n").present());
        assertFalse(Provenance.of("no frontmatter at all").present());
    }
}
