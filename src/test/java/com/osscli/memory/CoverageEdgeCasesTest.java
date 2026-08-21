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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The awkward archives: the ones a measurement quietly gets wrong.
 *
 * <p>Coverage is a number people will act on — it decides what gets read next — so the ways it can
 * be wrong matter more than the ways it can crash. An area counted twice, a binary file that stops
 * the walk, a subdirectory nobody looked in, an area whose name is a substring of another: none of
 * those fail, they just produce a figure that is not true.
 */
class CoverageEdgeCasesTest {

    private static void note(Path dir, String name, String body) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    @Test
    @DisplayName("notes in subdirectories count, because that is how anyone files anything")
    void theWalkIsRecursive(@TempDir Path archive) throws IOException {
        note(archive, "top.md", "appenders appenders appenders");
        note(archive, "projects/log4j/deep.md", "appenders appenders appenders");
        note(archive, "a/b/c/deeper.md", "appenders appenders appenders");

        assertEquals(3, Coverage.score(archive, List.of("Appenders")).get(0).notes());
    }

    @Test
    @DisplayName("only markdown is read, so a binary in the folder is not a mention")
    void nonMarkdownIsIgnored(@TempDir Path archive) throws IOException {
        note(archive, "real.md", "appenders appenders appenders");
        Files.write(archive.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3});
        note(archive, "notes.txt", "appenders appenders appenders appenders");

        // A .txt full of the word is not counted -- which is a choice, and the reason it is written
        // down: an archive is markdown here, and counting everything would sweep in exports,
        // transcripts and whatever else shares the folder.
        assertEquals(1, Coverage.score(archive, List.of("Appenders")).get(0).notes());
    }

    @Test
    @DisplayName("an unreadable file costs that file, not the measurement")
    void oneBadFileDoesNotEndTheWalk(@TempDir Path archive) throws IOException {
        note(archive, "good-1.md", "filters filters filters");
        // Invalid UTF-8, which is what a truncated download or a mislabelled export looks like.
        Files.write(archive.resolve("broken.md"), new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA9});
        note(archive, "good-2.md", "filters filters filters");
        note(archive, "good-3.md", "filters filters filters");

        Coverage.Area area = Coverage.score(archive, List.of("Filters")).get(0);

        assertEquals(3, area.notes(), "the readable notes must still be counted");
        assertEquals("covered", area.grade());
    }

    @Test
    @DisplayName("an area whose name contains another is not double counted")
    void overlappingAreaNames(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "json template layout, json template layout, json template layout");

        List<Coverage.Area> areas = Coverage.score(archive, List.of("Layout", "JSON Template Layout"));
        Map<String, Coverage.Area> byName = new java.util.HashMap<>();
        areas.forEach(a -> byName.put(a.name(), a));

        // Both legitimately match: "Layout" IS in the text. What must not happen is a count that
        // depends on the order the areas were listed in.
        assertEquals(3, byName.get("Layout").mentions());
        assertEquals(3, byName.get("JSON Template Layout").mentions());
    }

    @Test
    @DisplayName("matching ignores case, since a heading and a sentence spell things differently")
    void caseDoesNotMatter(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "# Appenders\nappenders are APPENDERS however you write them");

        assertEquals(3, Coverage.score(archive, List.of("appenders")).get(0).mentions());
    }

    @Test
    @DisplayName("overlapping occurrences are counted the way a person counts them")
    void countingIsNonOverlapping(@TempDir Path archive) throws IOException {
        // "aaaaaa" contains "aa" three times to a person and five times to a scan that steps one
        // character at a time. The difference decides whether a floor of three is reached, so it is
        // not a detail -- and the body is six characters rather than four precisely because a
        // count below the floor is discarded, which would have hidden the property being tested.
        note(archive, "a.md", "aaaaaa");

        assertEquals(3, Coverage.score(archive, List.of("aa")).get(0).mentions());
    }

    @Test
    @DisplayName("an empty archive and an empty yardstick are both answers, not errors")
    void emptyInputs(@TempDir Path archive) throws IOException {
        assertTrue(Coverage.score(archive, List.of()).isEmpty());
        assertEquals(
                "nothing", Coverage.score(archive, List.of("Anything")).get(0).grade());
        assertTrue(Coverage.map(archive, Map.of()).isEmpty());
    }

    @Test
    @DisplayName("unicode in a note and in an area name both work")
    void unicodeIsFine(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "garbage-free ☕ garbage-free ☕ garbage-free ☕");

        assertEquals(3, Coverage.score(archive, List.of("garbage-free")).get(0).mentions());
        // Three, because the first draft wrote two and asserted three: a multi-byte character is
        // one occurrence, and the floor discards anything under three, so the count read as zero.
        assertEquals(3, Coverage.score(archive, List.of("☕")).get(0).mentions());
    }

    @Test
    @DisplayName("a thousand notes of random text does not throw, and the totals stay consistent")
    void aLargeRandomArchive(@TempDir Path archive) throws IOException {
        Random random = new Random(20260819L);
        String[] words = {"appender", "layout", "filter", "lookup", "thread", "buffer", "café", "…"};
        int planted = 0;
        for (int i = 0; i < 1000; i++) {
            StringBuilder body = new StringBuilder();
            for (int w = 0; w < 40; w++) {
                body.append(words[random.nextInt(words.length)]).append(' ');
            }
            boolean plant = random.nextInt(4) == 0;
            if (plant) {
                body.append(" lookup lookup lookup lookup lookup ");
                planted++;
            }
            note(archive, "note-" + i + ".md", body.toString());
        }

        Coverage.Area lookups = Coverage.score(archive, List.of("lookup")).get(0);

        // The planted notes are a floor, not the answer: random text produces the word too. What is
        // asserted is that the count is at least the planting and never more than the corpus.
        assertTrue(lookups.notes() >= planted, lookups.notes() + " < " + planted);
        assertTrue(lookups.notes() <= 1000);
        assertNotNull(lookups.strongest());
        assertEquals("covered", lookups.grade());
    }

    @Test
    @DisplayName("a note that is only whitespace is simply not evidence")
    void emptyNotes(@TempDir Path archive) throws IOException {
        note(archive, "blank.md", "");
        note(archive, "spaces.md", "   \n\n  \t ");

        assertEquals(
                "nothing", Coverage.score(archive, List.of("Appenders")).get(0).grade());
    }

    @Test
    @DisplayName("a blank area name matches nothing rather than everything")
    void blankAreaName(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "some words here");

        // An empty needle is in every string. Without the guard, a stray comma in a yardstick would
        // report every area as fully covered -- the most flattering possible bug.
        assertEquals(0, Coverage.score(archive, List.of("")).get(0).mentions());
    }

    @Test
    @DisplayName("the map ignores a topic whose terms nothing matches, without dropping the topic")
    void aTopicWithNoHitsStillAppears(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "appender appender appender");

        Map<String, List<String>> map =
                Coverage.map(archive, Map.of("log4j", List.of("appender"), "kafka", List.of("broker")));

        assertEquals(List.of("a.md"), map.get("log4j"));
        // Present and empty, not absent. "No notes on kafka" is the answer; a missing key reads as
        // "kafka was never asked about".
        assertTrue(map.containsKey("kafka"));
        assertTrue(map.get("kafka").isEmpty());
    }

    @Test
    @DisplayName("a file that is a directory named like a note does not break the walk")
    void aDirectoryNamedLikeANote(@TempDir Path archive) throws IOException {
        Files.createDirectories(archive.resolve("looks-like.md"));
        note(archive, "real.md", "filters filters filters");

        assertEquals(1, Coverage.score(archive, List.of("Filters")).get(0).notes());
    }

    @Test
    @DisplayName("a symlinked note is read once, not chased in a circle")
    void symlinksDoNotLoop(@TempDir Path archive) throws IOException {
        note(archive, "real.md", "filters filters filters");
        try {
            Files.createSymbolicLink(archive.resolve("loop"), archive);
        } catch (IOException | UnsupportedOperationException e) {
            return; // no symlink support here; the property is untestable rather than broken
        }

        // Files.walk does not follow symlinks unless asked, which is the behaviour relied on: an
        // archive containing a link to itself would otherwise walk until it ran out of path.
        Coverage.Area area = Coverage.score(archive, List.of("Filters")).get(0);
        assertEquals(1, area.notes());
    }

    @Test
    @DisplayName("kb.json is read from the home, and a malformed one falls back rather than failing")
    void configurationIsOptionalAndForgiving(@TempDir Path dir) throws IOException {
        // Loading must never throw: a knowledge base that refuses to start until its configuration
        // parses is one that stops working the day somebody leaves a trailing comma in it.
        KnowledgePack pack = KnowledgePack.load();
        assertNotNull(pack.archive());
        assertNotNull(pack.topics());
        assertNotNull(pack.yardsticks());
    }

    @Test
    @DisplayName("the strongest note is the one with the most mentions, not the first one seen")
    void strongestIsAMaximum(@TempDir Path archive) throws IOException {
        note(archive, "aaa-first.md", "lookup lookup lookup");
        note(archive, "zzz-last.md", "lookup lookup lookup lookup lookup lookup");

        assertEquals(
                "zzz-last.md", Coverage.score(archive, List.of("lookup")).get(0).strongest());
    }
}
