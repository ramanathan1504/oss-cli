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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three questions {@code oss doctor} cannot answer.
 *
 * <p>It reports on the install: database, schema, models, token, folders. It has never reported on
 * whether the thing the install exists for is working — whether the archive is reachable, whether
 * the scheduled harvest is succeeding, whether the job is even loaded. A sibling tool's daily job
 * failed for four days into a log nobody read, and the reason nobody read it is that no command ever
 * asked.
 */
class MemoryHealthTest {

    @Test
    @DisplayName("counting notes never throws for a folder that is not there")
    void countingAbsentFoldersIsZero(@TempDir Path dir) throws IOException {
        assertEquals(0L, BuiltinMemory.countNotes(dir.resolve("never-created")));
        assertEquals(0L, BuiltinMemory.countNotes(dir));

        Files.writeString(dir.resolve("a.md"), "x", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("deep"));
        Files.writeString(dir.resolve("deep").resolve("b.md"), "x", StandardCharsets.UTF_8);
        // Not a note: an archive always has one of these, and counting it inflates the answer.
        Files.writeString(dir.resolve("kb.json"), "{}", StandardCharsets.UTF_8);

        assertEquals(2L, BuiltinMemory.countNotes(dir));
    }

    @Test
    @DisplayName("a fresh install is a warning, not a failure")
    void freshInstallIsNotAFailure() {
        // The default archive does not exist until the first note is filed. Greeting a new user
        // with a red line about a folder they were never asked to make is how a working install
        // gets described as a broken one — and the whole promise here is that the floor works with
        // nothing configured.
        BuiltinMemory.Check archive = archiveCheck(KnowledgePack.of(KnowledgePack.DEFAULT_ARCHIVE, Map.of(), Map.of()));

        if (Files.isDirectory(KnowledgePack.DEFAULT_ARCHIVE)) {
            assertEquals(BuiltinMemory.Check.Status.OK, archive.status(), archive.toString());
        } else {
            assertEquals(BuiltinMemory.Check.Status.WARN, archive.status(), archive.toString());
            assertTrue(archive.advice().contains("nothing filed yet"), archive.advice());
            assertFalse(archive.advice().contains("kb.json"), "nobody named this folder; do not blame a file");
        }
    }

    @Test
    @DisplayName("an archive somebody named and that is missing IS a failure, and says which kind")
    void configuredButMissingIsLoud(@TempDir Path dir) {
        // The difference that matters: "you have not written anything yet" versus "the folder you
        // named is not on this machine right now" — which for a synced archive means it has not
        // downloaded, and is the one worth acting on.
        BuiltinMemory.Check archive = archiveCheck(packAt(dir.resolve("not-there")));

        assertEquals(BuiltinMemory.Check.Status.FAIL, archive.status());
        assertTrue(archive.advice().contains("kb.json names this folder"), archive.advice());
        assertTrue(archive.advice().contains("has not downloaded"), archive.advice());
    }

    @Test
    @DisplayName("an archive that is there passes, and the notes in it are counted")
    void presentArchivePasses(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("one.md"), "a note", StandardCharsets.UTF_8);

        List<BuiltinMemory.Check> checks = BuiltinMemory.health(packAt(dir));

        assertEquals(BuiltinMemory.Check.Status.OK, byName(checks, "archive").status());
        assertEquals(BuiltinMemory.Check.Status.OK, byName(checks, "notes").status());
        assertTrue(
                byName(checks, "notes").detail().startsWith("1 "),
                byName(checks, "notes").detail());
    }

    @Test
    @DisplayName("an archive that is there but empty is a warning that says what to type")
    void emptyArchiveAdvises(@TempDir Path dir) {
        BuiltinMemory.Check notes = byName(BuiltinMemory.health(packAt(dir)), "notes");

        assertEquals(BuiltinMemory.Check.Status.WARN, notes.status());
        assertTrue(notes.advice().contains("oss memory file"), notes.advice());
    }

    @Test
    @DisplayName("every question oss doctor cannot answer is asked here")
    void theThreeMissingQuestionsAreCovered(@TempDir Path dir) {
        List<String> asked = BuiltinMemory.health(packAt(dir)).stream()
                .map(BuiltinMemory.Check::name)
                .toList();

        // oss doctor reports the install — database, schema, models, token, folders — and none of
        // these three.
        assertTrue(asked.contains("archive"), asked.toString());
        assertTrue(asked.contains("last run"), asked.toString());
        assertTrue(asked.contains("schedule"), asked.toString());
    }

    @Test
    @DisplayName("a schedule installed but not held by the system is a failure, not a tick")
    void installedIsNotTheSameAsRunning(@TempDir Path dir) {
        BuiltinMemory.Check schedule = byName(BuiltinMemory.health(packAt(dir)), "schedule");

        // The gap between "the file is on disk" and "the system is holding it" is exactly where a
        // dead agent hides: a check for the file alone reports everything as fine.
        if (schedule.detail().contains("NOT loaded")) {
            assertEquals(BuiltinMemory.Check.Status.FAIL, schedule.status());
        } else if (schedule.detail().equals("not installed")) {
            assertEquals(BuiltinMemory.Check.Status.WARN, schedule.status());
            // A daily job that appeared because the tool was run once would be the same broken
            // promise as a 22 MB download nobody asked for.
            assertTrue(schedule.advice().contains("--install"), schedule.advice());
        } else {
            assertEquals(BuiltinMemory.Check.Status.OK, schedule.status());
        }
    }

    @Test
    @DisplayName("a run that has never happened is not reported as a run that failed")
    void neverRunIsNotFailed(@TempDir Path dir) {
        BuiltinMemory.Check last = byName(BuiltinMemory.health(packAt(dir)), "last run");

        assertFalse(
                last.detail().equals("never recorded") && last.status() == BuiltinMemory.Check.Status.FAIL,
                "a fresh install has never harvested; that is not a failure");
    }

    @Test
    @DisplayName("--at takes a time, and refuses anything that is not one")
    void timesAreValidated() {
        assertArrayEquals(new int[] {9, 15}, BuiltinMemory.parseTime("09:15"));
        assertArrayEquals(new int[] {7, 0}, BuiltinMemory.parseTime("7:00"));
        assertArrayEquals(new int[] {23, 59}, BuiltinMemory.parseTime("23:59"));
        assertArrayEquals(new int[] {0, 0}, BuiltinMemory.parseTime("00:00"));

        // Every one of these parses as numbers and is not a time. launchd accepts 25:00 into a
        // plist and then simply never fires, which from the outside is indistinguishable from an
        // install that did not happen.
        for (String notATime : List.of("24:00", "09:60", "25:00", "9", "0915", "9:5", "", "nine", "-1:00", "9:15:30")) {
            assertNull(BuiltinMemory.parseTime(notATime), notATime);
        }
        assertNull(BuiltinMemory.parseTime(null));
    }

    /** A pack pointing at a chosen folder, which is what naming one in kb.json produces. */
    private static KnowledgePack packAt(Path archive) {
        return KnowledgePack.of(archive, Map.of(), Map.of());
    }

    private static BuiltinMemory.Check archiveCheck(KnowledgePack pack) {
        return byName(BuiltinMemory.health(pack), "archive");
    }

    private static BuiltinMemory.Check byName(List<BuiltinMemory.Check> checks, String name) {
        return checks.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name + " in " + checks));
    }
}
