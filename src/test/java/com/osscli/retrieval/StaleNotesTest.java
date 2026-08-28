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
package com.osscli.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a moved note stops answering under its old name, and that an unreachable archive is never
 * mistaken for a deleted one.
 *
 * <p>The second is the one worth having. The first is a tidy-up; the second is the difference
 * between a cleanup and losing an index of a decade of notes because a folder was slow to mount.
 */
class StaleNotesTest {

    @Test
    @DisplayName("a note whose folder is there and whose file is not has been deleted")
    void deletedNotesAreFound(@TempDir Path dir) throws IOException {
        Path present = dir.resolve("still-here.md");
        Files.writeString(present, "# here");

        StaleNotes.Sweep sweep = StaleNotes.sweep(
                List.of(present.toString(), dir.resolve("gone.md").toString()), List.of(dir));

        assertEquals(List.of(dir.resolve("gone.md").toString()), sweep.gone());
        assertTrue(sweep.unreachableFolders().isEmpty());
    }

    @Test
    @DisplayName("an archive that cannot be reached is never pruned")
    void unreachableRootsAreNeverPruned(@TempDir Path dir) {
        // The rule that stops this being unrecoverable. This archive spent a year in iCloud, where
        // a folder being briefly unreadable is an ordinary afternoon -- and Files.exists cannot
        // tell that from a deletion. If the root is not there, nothing under it is judged.
        Path unmounted = dir.resolve("not-mounted-yet");

        StaleNotes.Sweep sweep = StaleNotes.sweep(
                List.of(
                        unmounted.resolve("a.md").toString(),
                        unmounted.resolve("b.md").toString()),
                List.of(unmounted));

        assertTrue(sweep.nothingToDo(), "an unmounted archive is not a deleted one: " + sweep.gone());
        assertFalse(sweep.unreachableFolders().isEmpty(), "and it says what it could not reach");
    }

    @Test
    @DisplayName("a folder deleted inside a live archive is a deletion, not a missing disk")
    void deletedSubfoldersInsideALiveRootArePruned(@TempDir Path root) throws IOException {
        // Checking the note's own folder was too cautious to be useful: reorganising an archive
        // deletes the folders it empties, and 1,156 rows pointing into folders that had been
        // deliberately removed were protected as though a disk were missing.
        Files.writeString(root.resolve("kept.md"), "# kept");
        String removed = root.resolve("claude-code").resolve("moved.md").toString();

        StaleNotes.Sweep sweep = StaleNotes.sweep(List.of(removed), List.of(root));

        assertEquals(List.of(removed), sweep.gone());
    }

    @Test
    @DisplayName("a sweep of a healthy index finds nothing to do")
    void nothingToDoIsTheNormalCase(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.md");
        Files.writeString(a, "# a");

        assertTrue(StaleNotes.sweep(List.of(a.toString()), List.of(dir)).nothingToDo());
    }

    @Test
    @DisplayName("blank and null paths are skipped rather than counted as deletions")
    void garbageRowsAreNotDeletions() {
        StaleNotes.Sweep sweep = StaleNotes.sweep(java.util.Arrays.asList(null, "", "   "), List.of());

        assertTrue(sweep.gone().isEmpty());
        assertFalse(sweep.unreachableFolders().contains(""));
    }
}
