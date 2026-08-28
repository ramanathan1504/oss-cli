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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That one subject is one note, that appending is safe to repeat, and that nothing you wrote by
 * hand is ever written over.
 *
 * <p>The middle one is what makes this survivable. This runs hourly and a session is re-read every
 * time it grows, so an append that only ever adds would grow the file once an hour for as long as
 * somebody kept working.
 */
class SessionLogTest {

    @Test
    @DisplayName("four days on one issue is one note, not five files")
    void oneSubjectOneNote(@TempDir Path archive) throws IOException {
        // Measured on a real archive: five files for one issue, each a fifth of the story and none
        // of them the place to look.
        Path log = SessionLog.pathFor(archive, "log4j", "name issue 707");

        SessionLog.append(log, "name issue 707", "log4j", "p", "s1", "## 2026-08-25\n\nfirst day");
        SessionLog.append(log, "name issue 707", "log4j", "p", "s2", "## 2026-08-26\n\nsecond day");
        SessionLog.append(log, "name issue 707", "log4j", "p", "s3", "## 2026-08-28\n\nthird day");

        String text = Files.readString(log);
        assertEquals(3, SessionLog.sessionsIn(log));
        assertTrue(text.contains("first day") && text.contains("second day") && text.contains("third day"));
        assertTrue(text.indexOf("first day") < text.indexOf("third day"), "oldest first, the way the work went");
    }

    @Test
    @DisplayName("re-reading a session replaces its own block rather than adding another")
    void appendingIsIdempotent(@TempDir Path archive) throws IOException {
        // The hourly job re-reads a session every time it grows. Without this the file would gain a
        // block an hour until it was unreadable.
        Path log = SessionLog.pathFor(archive, "log4j", "name PR 812");
        SessionLog.append(log, "name PR 812", "log4j", "p", "s1", "## 2026-08-25\n\nhalf the work");
        SessionLog.append(log, "name PR 812", "log4j", "p", "s2", "## 2026-08-26\n\nsomebody else's session");

        SessionLog.append(log, "name PR 812", "log4j", "p", "s1", "## 2026-08-25\n\nall of the work");

        String text = Files.readString(log);
        assertEquals(2, SessionLog.sessionsIn(log), "still two sessions, not three");
        assertTrue(text.contains("all of the work"), "the block was updated");
        assertFalse(text.contains("half the work"), "and the stale version is gone");
        assertTrue(text.contains("somebody else's session"), "the other block is untouched");
    }

    @Test
    @DisplayName("a note you wrote by hand is never written over")
    void handWrittenNotesAreSafe(@TempDir Path archive) throws IOException {
        // On macOS "Issue-3704.md" and "issue-3704.md" are the same file, so the generated log
        // would silently replace a page somebody spent an afternoon on. Ownership is decided by
        // what is in the file, not by what it is called.
        Path mine = archive.resolve("Projects/log4j/name-issue-707.md");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "---\ntitle: my own plan\n---\n\n# hours of my own writing\n");

        Path log = SessionLog.pathFor(archive, "log4j", "name issue 707");

        assertFalse(log.equals(mine), "the log must go beside it, not through it");
        assertTrue(log.getFileName().toString().endsWith("-sessions.md"), log.toString());
        assertTrue(Files.readString(mine).contains("hours of my own writing"), "and the original is untouched");
    }

    @Test
    @DisplayName("a log this code wrote is appended to rather than duplicated")
    void ownLogsAreReused(@TempDir Path archive) throws IOException {
        Path first = SessionLog.pathFor(archive, "log4j", "name PR 812");
        SessionLog.append(first, "name PR 812", "log4j", "p", "s1", "## day one\n\nwork");

        assertEquals(first, SessionLog.pathFor(archive, "log4j", "name PR 812"));
    }

    @Test
    @DisplayName("the log says it is a log, which is how ownership is decided")
    void logsCarryTheirMarker(@TempDir Path archive) throws IOException {
        Path log = SessionLog.pathFor(archive, "log4j", "name PR 812");
        SessionLog.append(log, "name PR 812", "log4j", "p", "s1", "## day one\n\nwork");

        assertTrue(Files.readString(log).contains(SessionLog.MARKER));
        assertTrue(SessionLog.isOurs(log));
    }

    @Test
    @DisplayName("a section carries its date and the summary that was written for it")
    void sectionsAreDatedAndAttributed() {
        String section = SessionLog.sectionFor(
                "2026-08-28",
                "why rollover skips a file",
                new Enrichment.Summary("The policy fired twice.", Enrichment.By.tool("claude")),
                "> the turns");

        assertTrue(section.startsWith("## 2026-08-28"), section);
        assertTrue(section.contains("why rollover skips a file"));
        assertTrue(section.contains("The policy fired twice."));
        assertTrue(section.contains("Summarised by claude"));
    }

    @Test
    @DisplayName("a section with no summary is still a section")
    void summaryIsOptional() {
        String section = SessionLog.sectionFor("2026-08-28", "a title", null, "> the turns");

        assertTrue(section.contains("> the turns"));
        assertFalse(section.contains("Summarised by"));
    }
}
