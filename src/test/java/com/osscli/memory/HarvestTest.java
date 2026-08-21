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

import com.osscli.model.Issue;
import com.osscli.model.Label;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That harvesting your own work needs nothing but oss-cli.
 *
 * <p>A sibling repository did this in Python against DEVONthink. The notes it wrote were always
 * ordinary markdown in a folder — DEVONthink was an index on top — so the built-in writes the same
 * files with no such dependency, and an archive extension still takes over when one is attached.
 */
class HarvestTest {

    private static Issue issue(long number, String url, String title, List<Label> labels) {
        return new Issue(number, title, "the body", "open", 0, null, null, null, labels, null, null, url);
    }

    @Test
    @DisplayName("the repository comes from the item's own URL")
    void repositoryIsReadNotGuessed() {
        // The search API returns the item, not the repository it came from. Falling back to the
        // configured default would file somebody else's issue under your project's name.
        Issue i = issue(4249, "https://github.com/apache/logging-log4j2/pull/4249", "t", List.of());
        assertEquals("apache/logging-log4j2", BuiltinMemory.repositoryOf(i));

        Issue other = issue(1, "https://github.com/elastic/elasticsearch/issues/1", "t", List.of());
        assertEquals("elastic/elasticsearch", BuiltinMemory.repositoryOf(other));
    }

    @Test
    @DisplayName("an item with no url is filed as unknown rather than as yours")
    void missingUrlIsNotGuessed() {
        assertEquals("unknown", BuiltinMemory.repositoryOf(issue(7, null, "t", List.of())));
    }

    @Test
    @DisplayName("harvesting the same item twice rewrites one note")
    void nameIsStableAcrossRuns() {
        // Timestamping instead is how one review ended up in a real archive six times, each copy
        // embedded and each competing to answer the same question.
        Issue i = issue(4249, "https://github.com/apache/logging-log4j2/pull/4249", "t", List.of());

        assertEquals("gh-apache-logging-log4j2-4249.md", BuiltinMemory.harvestName(i));
        assertEquals(BuiltinMemory.harvestName(i), BuiltinMemory.harvestName(i));
    }

    @Test
    @DisplayName("two repositories never collide on one file")
    void differentReposDifferentNotes() {
        Issue a = issue(1, "https://github.com/apache/kafka/issues/1", "t", List.of());
        Issue b = issue(1, "https://github.com/apache/spark/issues/1", "t", List.of());

        assertTrue(!BuiltinMemory.harvestName(a).equals(BuiltinMemory.harvestName(b)));
    }

    @Test
    @DisplayName("the note is markdown a person could have written")
    void noteIsPlainMarkdown() {
        Issue i = issue(
                4249,
                "https://github.com/apache/logging-log4j2/pull/4249",
                "Fix circular references in exceptions",
                List.of(new Label("bug"), new Label("appenders")));

        String note = BuiltinMemory.harvestNote(i, List.of());

        assertTrue(note.startsWith("# apache/logging-log4j2 #4249"), note);
        assertTrue(note.contains("## Fix circular references in exceptions"), note);
        assertTrue(note.contains("- labels: bug, appenders"), note);
        assertTrue(note.contains("- link: https://github.com/apache/logging-log4j2/pull/4249"), note);
        // No DEVONthink, no database, no front matter anything has to understand: a folder of
        // markdown is the whole format, which is why the built-in can read what the extension wrote.
        assertTrue(!note.contains("---\n"), "the note should not need front matter");
    }

    @Test
    @DisplayName("harvest is offered by the built-in, with nothing attached")
    void harvestIsABuiltInVerb() {
        assertTrue(BuiltinMemory.VERBS.contains("harvest"));
        assertTrue(BuiltinMemory.supports("harvest"));
    }

    @Test
    @DisplayName("a harvested note carries the headings a digest reads")
    void noteIsShapedForTheDigest() {
        Issue i = issue(4249, "https://github.com/apache/logging-log4j2/pull/4249", "Fix circular refs", List.of());

        String note = BuiltinMemory.harvestNote(i, List.of());

        // Every harvester that has written into this archive uses these, Digest mines them, and 443
        // of 623 notes carry them. A harvest inventing its own layout writes notes the rest of the
        // tool cannot read.
        assertTrue(note.contains("## The Problem (What & Where)"), note);
        assertTrue(note.contains("## The Solution (How)"), note);
        assertFalse(Digest.sectionsOf(note).isEmpty(), "the digest cannot read what harvest wrote");
    }

    @Test
    @DisplayName("the conversation is kept in order, with who said it and when")
    void discussionIsPreserved() {
        Issue i = issue(1, "https://github.com/o/n/issues/1", "t", List.of());

        String note = BuiltinMemory.harvestNote(
                i,
                List.of(
                        BuiltinMemory.comment(java.util.Map.of(
                                "user", java.util.Map.of("login", "alice"),
                                "created_at", "2026-07-24T03:28:42Z",
                                "body", "this is the argument that mattered")),
                        BuiltinMemory.comment(
                                java.util.Map.of("user", java.util.Map.of("login", "bob"), "body", "and the reply"))));

        assertTrue(note.contains("## The \"Why\" (Review Discussions)"), note);
        assertTrue(note.indexOf("@alice") < note.indexOf("@bob"), "the thread lost its order");
        assertTrue(note.contains("2026-07-24"), "a year later, when it was said is most of what makes it readable");
        assertTrue(note.contains("this is the argument that mattered"), note);
    }

    @Test
    @DisplayName("a comment with nothing in it still names its author")
    void thinCommentsDoNotBreakTheThread() {
        String c = BuiltinMemory.comment(java.util.Map.of("user", java.util.Map.of("login", "alice")));
        assertTrue(c.contains("@alice"), c);

        // An anonymous or malformed comment is somebody else's data, not a reason to fail.
        assertTrue(BuiltinMemory.comment(java.util.Map.of()).contains("someone"));
    }

    @Test
    @DisplayName("an item with no conversation has no Why section rather than an empty one")
    void noThreadMeansNoHeading() {
        Issue i = issue(1, "https://github.com/o/n/issues/1", "t", List.of());

        assertFalse(BuiltinMemory.harvestNote(i, List.of()).contains("(Review Discussions)"));
    }
}
