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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rules that decide what gets filed, and whether a filed copy still matches its source. */
class PackNotesTest {

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    @Test
    void findsWriteUpsAndLeavesTheOperatingDocsAlone(@TempDir Path root) throws IOException {
        write(root.resolve("repros/issue-4279/README.md"), "# Log4j issue #4279 reproduction\n");
        write(root.resolve("docs/finding.md"), "# A finding\n");
        write(root.resolve("README.md"), "# The repository\n");
        write(root.resolve("CLAUDE.md"), "# Instructions\n");
        write(root.resolve("GAP-ANALYSIS.md"), "# Gaps\n");

        List<Path> found = PackNotes.discover(root, false);

        assertEquals(2, found.size(), "only the write-ups: " + found);
        assertTrue(found.contains(root.resolve("repros/issue-4279/README.md")));
        assertTrue(found.contains(root.resolve("docs/finding.md")));
    }

    @Test
    void allWidensTheWalkButStillRefusesTheReadme(@TempDir Path root) throws IOException {
        write(root.resolve("elsewhere/note.md"), "# Kept somewhere else\n");
        write(root.resolve("README.md"), "# The repository\n");
        write(root.resolve("CLAUDE.md"), "# Instructions\n");

        List<Path> found = PackNotes.discover(root, true);

        assertEquals(List.of(root.resolve("elsewhere/note.md")), found);
    }

    @Test
    void neverWalksBuildOutput(@TempDir Path root) throws IOException {
        write(root.resolve("target/classes/generated.md"), "# Generated\n");
        write(root.resolve(".git/notes.md"), "# Internals\n");
        write(root.resolve("node_modules/pkg/readme.md"), "# Somebody else's\n");

        assertTrue(PackNotes.discover(root, true).isEmpty());
    }

    @Test
    void namesTheNoteAfterItsTitleNotItsFilename(@TempDir Path root) throws IOException {
        Path source = root.resolve("repros/issue-4279/README.md");
        write(source, "# Log4j issue #4279 reproduction\n\nbody\n");

        PackNotes.Found found = PackNotes.examine(root, source);

        assertEquals("Log4j issue #4279 reproduction", found.title());
        assertEquals("log4j-issue-4279-reproduction", found.slug());
        assertEquals("repros/issue-4279/README.md", found.relative());
    }

    @Test
    void aReadmeWithNoHeadingIsNamedForItsDirectory(@TempDir Path root) throws IOException {
        Path source = root.resolve("repros/issue-4143/README.md");
        write(source, "no heading here\n");

        assertEquals("issue-4143", PackNotes.examine(root, source).title());
    }

    @Test
    void provenanceMergesIntoExistingFrontMatterRatherThanStackingOnIt() {
        String body = "---\ntitle: Original\ntopic: log4j\n---\n\n# Heading\n\nbody\n";

        String out = PackNotes.withProvenance(body, Map.of("sha256", "abc"));

        assertEquals(2, count(out, "---\n"), "exactly one front-matter block, opened and closed");
        Map<String, String> front = PackNotes.frontMatter(out);
        assertEquals("log4j", front.get("topic"), "the note's own fields survive");
        assertEquals("abc", front.get("sha256"));
        assertTrue(out.contains("# Heading"), "the body survives");
        assertFalse(out.contains("---\n---"), "no second block stacked on the first");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aNoteWithNoFrontMatterGetsOne() {
        String out = PackNotes.withProvenance("# Heading\n\nbody\n", Map.of("source", "a/b.md"));

        assertEquals("a/b.md", PackNotes.frontMatter(out).get("source"));
        assertTrue(out.contains("# Heading"));
    }

    private static Path fileTracked(Path store, Path source, String sha) throws IOException {
        Path note = store.resolve("note.md");
        write(
                note,
                PackNotes.withProvenance(
                        "# Note\n",
                        new java.util.LinkedHashMap<>(Map.of(
                                "source",
                                source.getFileName().toString(),
                                "repo",
                                source.getParent().toString(),
                                "sha256",
                                sha))));
        return note;
    }

    @Test
    void doctorSaysNothingWhenNoNoteIsTracked(@TempDir Path store) throws IOException {
        write(store.resolve("hand-filed.md"), "# Filed by hand, no provenance\n");

        assertTrue(BuiltinMemory.driftChecks(store).isEmpty(), "an untracked store has nothing to compare");
    }

    @Test
    void doctorWarnsWhenTheSourceMovedAheadOfTheCopy(@TempDir Path store, @TempDir Path repo) throws IOException {
        Path source = repo.resolve("finding.md");
        write(source, "# Note\n");
        fileTracked(store, source, PackNotes.sha256("# Note\n"));
        assertEquals(
                BuiltinMemory.Check.Status.OK,
                BuiltinMemory.driftChecks(store).get(0).status(),
                "matching to begin with");

        write(source, "# Note\n\nthree commits later\n");

        List<BuiltinMemory.Check> checks = BuiltinMemory.driftChecks(store);
        assertEquals(1, checks.size());
        assertEquals(BuiltinMemory.Check.Status.WARN, checks.get(0).status());
        assertTrue(checks.get(0).detail().contains("finding.md"), checks.get(0).detail());
    }

    @Test
    void doctorFailsWhenTheSourceIsGoneEntirely(@TempDir Path store, @TempDir Path repo) throws IOException {
        Path source = repo.resolve("finding.md");
        write(source, "# Note\n");
        fileTracked(store, source, PackNotes.sha256("# Note\n"));

        Files.delete(source);

        List<BuiltinMemory.Check> checks = BuiltinMemory.driftChecks(store);
        assertEquals(1, checks.size());
        assertEquals(
                BuiltinMemory.Check.Status.FAIL,
                checks.get(0).status(),
                "a copy with no source left is the only record there is");
    }

    @Test
    void theDigestIsWhatDetectsAnEditedSource() {
        String before = PackNotes.sha256("# Note\n\none line\n");
        String after = PackNotes.sha256("# Note\n\none line\nand another\n");

        assertFalse(before.equals(after));
        assertEquals(before, PackNotes.sha256("# Note\n\none line\n"), "same text, same digest");
    }
}
