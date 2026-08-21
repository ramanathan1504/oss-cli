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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a digest reads the notes rather than counting them.
 *
 * <p>{@code map} answers "which notes mention log4j" — an index, which tells you nothing and leaves
 * you to open every note. This pulls the problem and the resolution out of each one, which works
 * because the notes have a shape: of 623 notes in a real archive, 443 carry
 * {@code ## The Problem (What & Where)}.
 */
class DigestTest {

    private static final String NOTE = """
            # apache/logging-log4j2 #4249

            ## The Problem (What & Where)

            A cyclic cause chain made the renderer loop forever.

            ## The Solution (How)

            Track visited throwables in an identity set.

            ## The "Why" (Review Discussions)

            HashSet was already identity-based for most exceptions.
            """;

    @Test
    @DisplayName("each named section is pulled out whole")
    void sectionsAreExtracted() {
        Map<String, String> s = Digest.sectionsOf(NOTE);

        assertEquals(3, s.size());
        assertTrue(s.get("The Problem (What & Where)").startsWith("A cyclic cause chain"));
        assertTrue(s.get("The Solution (How)").contains("identity set"));
        assertTrue(s.get("The \"Why\" (Review Discussions)").contains("HashSet"));
        // The body stops at the next heading, or a digest would repeat the whole note three times.
        assertFalse(s.get("The Problem (What & Where)").contains("identity set"));
    }

    @Test
    @DisplayName("a heading with nothing under it is left out, not recorded empty")
    void emptySectionsAreDropped() {
        String hollow = "# n\n\n## The Problem (What & Where)\n\n## The Solution (How)\n\nfixed it\n";

        Map<String, String> s = Digest.sectionsOf(hollow);

        // A digest of empty sections reads as though the work was done and produced nothing.
        assertEquals(1, s.size());
        assertTrue(s.containsKey("The Solution (How)"));
    }

    @Test
    @DisplayName("a note with no structure contributes nothing rather than noise")
    void unstructuredNotesAreSkipped() {
        assertTrue(Digest.sectionsOf("just some prose about appenders").isEmpty());
        assertTrue(Digest.sectionsOf("").isEmpty());
        assertTrue(Digest.sectionsOf(null).isEmpty());
    }

    @Test
    @DisplayName("where a note came from is read from its name, not guessed from its prose")
    void originIsReadNotInferred() {
        assertEquals("github", Digest.originOf("gh-apache-logging-log4j2-4249.md"));
        assertEquals("github", Digest.originOf("Issue-4249-review-20260820.md"));
        assertEquals("conversation", Digest.originOf("claude-code-session-log4j-2026-08-19.md"));
        assertEquals("conversation", Digest.originOf("ai-studio-export-2026-06-02.md"));
        assertEquals("note", Digest.originOf("my-own-thoughts.md"));
    }

    @Test
    @DisplayName("what was agreed in public sorts above what was reasoned in private")
    void publicRecordRanksFirst() {
        List<Digest.Entry> ranked = Digest.rank(List.of(
                new Digest.Entry("claude-session.md", "conversation", Map.of("The Solution (How)", "b")),
                new Digest.Entry("gh-owner-name-1.md", "github", Map.of("The Solution (How)", "a"))));

        // Both matter and which is which matters: one is what was said on the thread and how it was
        // resolved, the other is the reasoning that got there. Merging them would read as one
        // account when it is two.
        assertEquals("gh-owner-name-1.md", ranked.get(0).note());
        assertEquals("claude-session.md", ranked.get(1).note());
    }

    @Test
    @DisplayName("the page says where each piece of evidence came from")
    void renderLabelsItsEvidence() {
        String page = Digest.render(
                "log4j", List.of(new Digest.Entry("gh-owner-name-1.md", "github", Digest.sectionsOf(NOTE))));

        assertTrue(page.startsWith("# log4j"), page);
        assertTrue(page.contains("_(github)_"), "the reader cannot tell public record from reasoning");
        assertTrue(page.contains("**The Problem (What & Where)**"), page);
        assertTrue(page.contains("identity set"), "the digest lost the content it exists to carry");
    }

    @Test
    @DisplayName("a topic with nothing worked out says so, and says where to look")
    void emptyTopicIsHonest() {
        String page = Digest.render("kafka", List.of());

        assertTrue(page.contains("No note under this topic carries a problem or a solution yet"));
        // An empty answer is where a tool teaches the next command.
        assertTrue(page.contains("oss memory map"));
    }
}
