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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import com.osscli.model.Label;
import com.osscli.model.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a harvest refreshes the notes an archive already has rather than doubling them.
 *
 * <p>The folder this writes existed for a year before anything in this program could write it, and
 * it was filled by a script that named its files a particular way. Every test here is about not
 * making a second copy of a note that is already there: by name, by identity, by timestamp.
 */
class IssueNotesTest {

    private static final String STAMP = "2026-01-01T00:00:00Z";

    private static Issue issue(long number, String url, String title, List<Label> labels) {
        return new Issue(
                number, title, "the body", "open", 0, null, null, null, labels, new User("someone"), null, url);
    }

    @Test
    @DisplayName("a name matches what the archive already filed")
    void namesMatchTheOnesOnDisk() {
        // Taken from notes that were on disk before this class existed. A different limit or a
        // different rule for apostrophes renames every one of them, and a renamed note is a second
        // copy of the first.
        assertEquals(
                "issue-03161-synchronization-with-main-backlog.md",
                IssueNotes.nameFor(false, 3161, "Synchronization with `main` backlog"));
        assertEquals(
                "issue-04244-log4j2eventlisteners-conditionalonproperty-has-no-effect---t.md",
                IssueNotes.nameFor(
                        false,
                        4244,
                        "Log4j2EventListener's @ConditionalOnProperty has no effect - the listener always runs"));
        assertEquals(
                "issue-04105-missing-ansi-italic-style-and-rendering-error-in-underline-s.md",
                IssueNotes.nameFor(false, 4105, "Missing ANSI Italic Style and Rendering Error in Underline Style"));
        assertEquals(
                "pr-00033-add-a-developer-to-the-team-list.md",
                IssueNotes.nameFor(true, 33, "Add a developer to the team list"));
    }

    @Test
    @DisplayName("punctuation is dropped, whitespace separates, a typed hyphen is kept")
    void theSlugRuleIsTheOneOnDisk() {
        // Each of these was a name in the archive that a rule mapping punctuation to a dash got
        // wrong, and every one of them would have been filed a second time.
        assertEquals("featevents-add-a-helper", IssueNotes.slug("feat(events): add a helper"));
        assertEquals(
                "spark-53015build-upgrade-log4j-to-2251",
                IssueNotes.slug("[SPARK-53015][BUILD] Upgrade log4j to 2.25.1"));
        assertEquals("an-etdeta-request-times-out", IssueNotes.slug("an ETD/ETA request times out"));
        // Whitespace and underscores are separators and a run of them is one dash.
        assertEquals("evp-pkey-signverify-message", IssueNotes.slug("EVP_PKEY_sign/verify_message"));
        assertEquals("two-words", IssueNotes.slug("two   words"));
        // A hyphen somebody typed is not whitespace and is not collapsed with it.
        assertEquals("has-no-effect---the-listener", IssueNotes.slug("has no effect - the listener"));
    }

    @Test
    @DisplayName("a title is trimmed before it is cut, not after")
    void trimmedBeforeTheCut() {
        // Stripping afterwards spends one of the sixty characters on a dash that is then removed,
        // which is a name one character shorter than the one already on disk.
        String leading = IssueNotes.slug("-" + "a".repeat(80));
        assertEquals(60, leading.length());
        assertEquals("a".repeat(60), leading);
    }

    @Test
    @DisplayName("a title with nothing usable in it still produces a file name")
    void emptyTitlesStillName() {
        assertEquals("issue-00001-untitled.md", IssueNotes.nameFor(false, 1, "…"));
        assertEquals("issue-00001-untitled.md", IssueNotes.nameFor(false, 1, null));
        // Windows drops a trailing dot or space in silence, so a name ending in one refers to a
        // different file than the one asked for.
        assertTrue(!IssueNotes.nameFor(false, 1, "ends with a space ").contains(" .md"));
    }

    @Test
    @DisplayName("an item that has not changed is not rewritten")
    void unchangedItemsAreLeftAlone() {
        Issue i = issue(42, "https://github.com/owner/name/issues/42", "A title", List.of());
        String first = IssueNotes.rewrite(null, i, "owner/name", List.of(), Set.of("author"), STAMP);

        assertNotNull(first);
        // The timestamp alone must not count as a change: a daily job that rewrites every note
        // every morning turns an archive under git into a thousand-file commit a day saying
        // nothing happened.
        assertNull(IssueNotes.rewrite(first, i, "owner/name", List.of(), Set.of("author"), "2026-02-02T00:00:00Z"));
    }

    @Test
    @DisplayName("what the note already knew survives being refreshed")
    void priorFactsAreKept() {
        String prior = """
                ---
                tags: [oss, github, hand-written-tag]
                github: owner/name#42
                kind: pr
                my_role: reviewer, inline-reviewer
                state: MERGED (merged)
                harvested: 2026-01-01T00:00:00Z
                ---

                # owner/name PR #42 — A title

                stale body
                """;
        Issue i = issue(42, "https://github.com/owner/name/pull/42", "A title", List.of());
        Issue closed = new Issue(
                42,
                "A title",
                "a new body",
                "closed",
                0,
                null,
                null,
                null,
                List.of(),
                new User("someone"),
                null,
                "https://github.com/owner/name/pull/42");

        String fresh = IssueNotes.rewrite(prior, closed, "owner/name", List.of(), Set.of("commenter"), STAMP);

        assertNotNull(fresh);
        Map<String, String> front = IssueNotes.frontmatter(fresh);
        // Establishing these costs two API calls per item that this command does not make, so a
        // refresh that dropped them would be a downgrade wearing an update's timestamp.
        assertTrue(front.get("my_role").contains("reviewer"), front.get("my_role"));
        assertTrue(front.get("my_role").contains("commenter"), front.get("my_role"));
        assertTrue(front.get("tags").contains("hand-written-tag"), front.get("tags"));
        // The search API reports a merged pull request as closed; the note knew better.
        assertEquals("MERGED (merged)", front.get("state"));
        assertTrue(fresh.contains("a new body"), fresh);
        assertEquals(STAMP, front.get("harvested"));
        assertTrue(i.isPullRequest() == closed.isPullRequest());
    }

    @Test
    @DisplayName("a note is found again by the item it is about, not by its name")
    void notesAreFoundByIdentity(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j").resolve(IssueNotes.FOLDER);
        Files.createDirectories(folder);
        Path note = folder.resolve("issue-00042-the-old-title.md");
        Issue old = issue(42, "https://github.com/owner/name/issues/42", "The old title", List.of());
        Files.writeString(
                note,
                IssueNotes.rewrite(null, old, "owner/name", List.of(), Set.of("author"), STAMP),
                StandardCharsets.UTF_8);

        Map<String, IssueNotes.Filed> filed = IssueNotes.filed(projects).byItem();

        // A title edited on GitHub would otherwise file a second note under the new slug, leaving
        // two copies of one thread for retrieval to fight over.
        IssueNotes.Filed found = filed.get(IssueNotes.id("owner/name", 42));
        assertNotNull(found);
        assertEquals(note, found.path());
        assertEquals("log4j", found.topic());
        assertEquals("The old title", found.title());
    }

    @Test
    @DisplayName("the index links at files that are there")
    void indexLinksResolve(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j").resolve(IssueNotes.FOLDER);
        Files.createDirectories(folder);
        Issue i = issue(42, "https://github.com/owner/name/issues/42", "A title", List.of(new Label("bug")));
        Files.writeString(
                folder.resolve("issue-00042-a-title.md"),
                IssueNotes.rewrite(null, i, "owner/name", List.of(), Set.of("author"), STAMP),
                StandardCharsets.UTF_8);

        Path index = projects.resolve(IssueNotes.FOLDER).resolve(IssueNotes.INDEX);
        Files.createDirectories(index.getParent());
        Files.writeString(
                index, IssueNotes.index(IssueNotes.filed(projects).byItem().values(), STAMP), StandardCharsets.UTF_8);

        // The index this replaced pointed at a folder layout that had been renamed, so all 173 of
        // its links were dead and nothing noticed for four weeks.
        String text = Files.readString(index);
        assertTrue(text.contains("(../log4j/issues/issue-00042-a-title.md)"), text);
        for (String line : text.lines().toList()) {
            int open = line.indexOf("](");
            if (open < 0) {
                continue;
            }
            String link = line.substring(open + 2, line.indexOf(')', open));
            assertTrue(Files.exists(index.getParent().resolve(link).normalize()), link);
        }
    }

    @Test
    @DisplayName("one thread filed under two topics is reported as one note and one copy")
    void doubledNotesAreFound(@TempDir Path projects) throws IOException {
        Issue i = issue(42, "https://github.com/owner/name/issues/42", "A title", List.of());
        String note = IssueNotes.rewrite(null, i, "owner/name", List.of(), Set.of("author"), STAMP);
        for (String topic : List.of("build-tooling", "jreleaser")) {
            Path folder = projects.resolve(topic).resolve(IssueNotes.FOLDER);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("issue-00042-a-title.md"), note, StandardCharsets.UTF_8);
        }

        IssueNotes.Known known = IssueNotes.filed(projects);

        // Both copies answer searches, and only one of them is ever rewritten.
        assertEquals(1, known.byItem().size());
        assertEquals(
                projects.resolve("build-tooling").resolve(IssueNotes.FOLDER).resolve("issue-00042-a-title.md"),
                known.byItem().get(IssueNotes.id("owner/name", 42)).path());
        assertEquals(
                List.of(projects.resolve("jreleaser").resolve(IssueNotes.FOLDER).resolve("issue-00042-a-title.md")),
                known.duplicates().get(IssueNotes.id("owner/name", 42)));
    }

    @Test
    @DisplayName("the index is not counted as one of the notes it lists")
    void theIndexIsNotANote(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j").resolve(IssueNotes.FOLDER);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(IssueNotes.INDEX), "---\ntags: [index]\n---\n\n# index\n");

        assertTrue(IssueNotes.filed(projects).byItem().isEmpty());
    }
}
