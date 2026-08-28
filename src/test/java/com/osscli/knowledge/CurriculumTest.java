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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the three states mean what they say, and that the generator respects a person's decision.
 *
 * <p>The second is the one that decides whether this is usable. A folder that quietly undoes an
 * afternoon's reading is a folder you stop trusting, and that happens exactly once.
 */
class CurriculumTest {

    private static void note(Path archive, String name, String body) throws IOException {
        Path f = archive.resolve(name);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
    }

    private static final Map<String, List<String>> YARDSTICKS =
            Map.of("dsa", List.of("Sliding Window", "Red Black Tree"));

    // ==========================================
    // Placing
    // ==========================================

    @Test
    @DisplayName("an area the archive never mentions is a gap")
    void nothingIsAGap(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "a note about queues and nothing else");

        List<Curriculum.Item> items = Curriculum.place(archive, YARDSTICKS);

        assertTrue(items.stream().allMatch(i -> "gap".equals(i.state())), items.toString());
    }

    @Test
    @DisplayName("an area met across several notes is backlog, not a gap and not knowledge")
    void touchedIsBacklog(@TempDir Path archive) throws IOException {
        // The distinction this class exists for. Forty mentions across three pull requests is an
        // area you have run into while fixing something else -- the understanding is real and it is
        // spread across a transcript and a diff, which is not the same as knowing it.
        note(archive, "a.md", "we used a sliding window sliding window sliding window here");
        note(archive, "b.md", "the sliding window sliding window sliding window again");
        note(archive, "c.md", "sliding window sliding window sliding window once more");

        Curriculum.Item sliding = Curriculum.place(archive, YARDSTICKS).stream()
                .filter(i -> i.area().equals("Sliding Window"))
                .findFirst()
                .orElseThrow();

        assertEquals("backlog", sliding.state());
        assertTrue(sliding.notes() >= 2, "backlog needs more than one passing mention");
    }

    @Test
    @DisplayName("one passing mention is not an encounter")
    void oneNoteIsStillAGap(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "sliding window sliding window sliding window in one place only");

        Curriculum.Item sliding = Curriculum.place(archive, YARDSTICKS).stream()
                .filter(i -> i.area().equals("Sliding Window"))
                .findFirst()
                .orElseThrow();

        assertEquals("gap", sliding.state());
    }

    @Test
    @DisplayName("nothing is ever placed as covered")
    void coveredIsNeverAssigned(@TempDir Path archive) throws IOException {
        // Covered is a claim about having read something. No count of mentions can establish it,
        // which is exactly why the move is done by hand.
        for (int i = 0; i < 20; i++) {
            note(archive, "n" + i + ".md", "sliding window sliding window sliding window everywhere");
        }

        assertFalse(
                Curriculum.place(archive, YARDSTICKS).stream().anyMatch(i -> "covered".equals(i.state())),
                "only a person can say they have learned something");
    }

    // ==========================================
    // Respecting a decision
    // ==========================================

    @Test
    @DisplayName("a file moved to covered is never written back to gap or backlog")
    void coveredIsLeftAlone(@TempDir Path archive) throws IOException {
        Curriculum.Item item = new Curriculum.Item("dsa", "Sliding Window", "gap", 0, 0, "");
        Path covered = Curriculum.pathFor(archive, "dsa", "covered", "Sliding Window");
        Files.createDirectories(covered.getParent());
        Files.writeString(covered, "---\nstatus: covered\n---\n# my own words about it\n");

        boolean wrote = Curriculum.write(archive, item, List.of());

        assertFalse(wrote, "a decision was made here and the generator must not undo it");
        assertTrue(Files.readString(covered).contains("my own words"), "and must not rewrite it either");
        assertFalse(Files.exists(Curriculum.pathFor(archive, "dsa", "gap", "Sliding Window")));
    }

    @Test
    @DisplayName("an area that moves between the two generated states is not listed twice")
    void noDuplicateAcrossGeneratedStates(@TempDir Path archive) throws IOException {
        Curriculum.write(archive, new Curriculum.Item("dsa", "Sliding Window", "gap", 0, 0, ""), List.of());
        assertTrue(Files.exists(Curriculum.pathFor(archive, "dsa", "gap", "Sliding Window")));

        Curriculum.write(archive, new Curriculum.Item("dsa", "Sliding Window", "backlog", 3, 9, "a.md"), List.of());

        assertTrue(Files.exists(Curriculum.pathFor(archive, "dsa", "backlog", "Sliding Window")));
        assertFalse(
                Files.exists(Curriculum.pathFor(archive, "dsa", "gap", "Sliding Window")),
                "the same area under two answers is worse than either answer");
    }

    @Test
    @DisplayName("where an area sits is read from disk, because that is where the answer lives")
    void stateComesFromTheFolder(@TempDir Path archive) throws IOException {
        assertNull(Curriculum.existingState(archive, "dsa", "Sliding Window"));

        Curriculum.write(archive, new Curriculum.Item("dsa", "Sliding Window", "backlog", 3, 9, ""), List.of());

        assertEquals("backlog", Curriculum.existingState(archive, "dsa", "Sliding Window"));
    }

    // ==========================================
    // The note and the tally
    // ==========================================

    @Test
    @DisplayName("a backlog note points at what you already wrote")
    void backlogCarriesItsEvidence() {
        // The difference between a reading list and a to-do list somebody else made for you.
        String note = Curriculum.noteFor(
                new Curriculum.Item("dsa", "Sliding Window", "backlog", 3, 9, "a.md"),
                List.of("Projects/log4j/rollover.md", "Projects/log4j/contributions/pr-812.md"));

        assertTrue(note.contains("Where you have already touched it"), note);
        assertTrue(note.contains("Projects/log4j/rollover.md"), note);
        assertTrue(note.contains("status: backlog"), note);
    }

    @Test
    @DisplayName("where you used it comes before where you mentioned it")
    void appliedEvidenceRanksFirst(@TempDir Path archive) throws IOException {
        // The first version listed whatever the walk reached first, so "Array" -- a word in every
        // Java note ever written -- cited a digest and a page about job applications. True, and
        // useless. A change that merged is evidence the technique was applied to real code.
        note(archive, "aaa-digest.md", "mentions sliding window in passing");
        note(archive, "Projects/x/contributions/2026-01-01-pr-1-a.md", "used a sliding window here");
        note(archive, "Projects/x/pr-reviews/review.md", "the sliding window in this diff");

        List<String> evidence = Curriculum.evidenceFor(archive, "Sliding Window", 8);

        assertTrue(evidence.size() >= 3, evidence.toString());
        assertTrue(
                Curriculum.isApplied(evidence.get(0)) && Curriculum.isApplied(evidence.get(1)),
                "code that shipped must come first: " + evidence);
        assertTrue(evidence.get(2).contains("digest"), evidence.toString());
    }

    @Test
    @DisplayName("the curriculum never cites itself")
    void curriculumNotesAreNotEvidence(@TempDir Path archive) throws IOException {
        // Every one of these notes contains its own area name, so without this they would all cite
        // each other and the evidence section would be a mirror.
        Curriculum.write(archive, new Curriculum.Item("dsa", "Sliding Window", "backlog", 3, 9, ""), List.of());

        assertTrue(Curriculum.evidenceFor(archive, "Sliding Window", 8).isEmpty());
    }

    @Test
    @DisplayName("a gap note says to go and read, not to go and look at your own notes")
    void gapNoteSendsYouToTheManual() {
        String note = Curriculum.noteFor(new Curriculum.Item("dsa", "Red Black Tree", "gap", 0, 0, ""), List.of());

        assertTrue(note.contains("Nothing in the archive touches this"), note);
        assertTrue(note.contains("Read the section of the manual"), note);
    }

    @Test
    @DisplayName("the tally counts folders, not intentions")
    void tallyReadsDisk(@TempDir Path archive) throws IOException {
        Curriculum.write(archive, new Curriculum.Item("dsa", "Sliding Window", "backlog", 3, 9, ""), List.of());
        Curriculum.write(archive, new Curriculum.Item("dsa", "Red Black Tree", "gap", 0, 0, ""), List.of());
        Path covered = Curriculum.pathFor(archive, "dsa", "covered", "Binary Search");
        Files.createDirectories(covered.getParent());
        Files.writeString(covered, "done");

        Curriculum.Tally t = Curriculum.tallies(archive, YARDSTICKS).get(0);

        assertEquals(1, t.gap());
        assertEquals(1, t.backlog());
        assertEquals(1, t.covered());
        assertEquals(33, t.percent());
    }

    @Test
    @DisplayName("a subject with nothing measured reports zero rather than dividing by it")
    void emptySubjectDoesNotDivideByZero(@TempDir Path archive) {
        assertEquals(0, Curriculum.tallies(archive, YARDSTICKS).get(0).percent());
    }
}
