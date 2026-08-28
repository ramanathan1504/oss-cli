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
    @DisplayName("every run that adds to the index also drops what is gone")
    void embeddingPrunesToo() throws IOException {
        // Adding is only half of keeping an index true. 89 deleted notes kept their rows and kept
        // answering: asked whether one issue was in the store, five of the hits were files nobody
        // could open. Nothing was wrong with the delete -- the index had no idea it had happened,
        // and would not until somebody ran another command and thought to look.
        String source = Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"));
        int embed = source.indexOf("private static void embedNotes(");
        assertTrue(embed > 0, "embedNotes moved; this guard needs rewriting");

        int end = source.indexOf("\n    }", embed);
        assertTrue(
                source.substring(embed, end).contains("pruneMovedNotes("),
                "a run that adds notes must also drop the ones that are gone");
    }

    @Test
    @DisplayName("a run that files nothing still drops what is gone")
    void pruningIsNotInsideTheWriteBranch() throws IOException {
        // It was, and a deletion is exactly the case that writes nothing. 229 junk notes were
        // removed from an archive, the next tick found no new transcripts, and 294 rows for files
        // nobody could open stayed in the index answering searches.
        String source = Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"));
        int sessions = source.indexOf("private static int sessions(");
        int prune = source.indexOf("pruneMovedNotes(", sessions);
        int writeBranch = source.indexOf("if (!written.isEmpty())", sessions);

        assertTrue(prune > 0 && writeBranch > 0, "the session run changed shape; this guard needs rewriting");
        assertTrue(
                prune < writeBranch,
                "pruning must happen whether or not anything was filed, or a deletion is never noticed");
    }

    @Test
    @DisplayName("blank and null paths are skipped rather than counted as deletions")
    void garbageRowsAreNotDeletions() {
        StaleNotes.Sweep sweep = StaleNotes.sweep(java.util.Arrays.asList(null, "", "   "), List.of());

        assertTrue(sweep.gone().isEmpty());
        assertFalse(sweep.unreachableFolders().contains(""));
    }
}
