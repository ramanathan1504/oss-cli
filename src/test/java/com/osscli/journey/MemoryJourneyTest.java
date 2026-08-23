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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keep something, then find it again: {@code memory file}, {@code memory index}, {@code memory
 * search}.
 *
 * <p>This is the half of the product that needs no AI at all -- filing, the topic map and the
 * digests are deterministic, and only finding *by meaning* wants a model. That claim is made on
 * every surface this project ships, and until now no test typed the three commands in a row to see
 * whether a note filed by the first is found by the third.
 *
 * <p>Deliberately with no model present: the journey runs against a store that has never fetched
 * the embedder, so what it proves is the floor -- search by shared terms, which is what a new
 * install has and what the landing page promises before anything is downloaded.
 */
class MemoryJourneyTest {

    private static void aNote(Path dir) throws Exception {
        Files.writeString(dir.resolve("note.md"), """
                # Pool deadlock above 200 threads

                The borrow path took the locks in the opposite order to the eviction sweep.
                Ordering the sweep like borrow removed the cycle.
                """);
    }

    @Test
    @DisplayName("a note filed is a note found, with no model installed")
    void fileThenSearch(@TempDir Path home, @TempDir Path work) throws Exception {
        aNote(work);

        Journey.Ran filed = Journey.oss(home, work, "memory", "file", "note.md");
        assertEquals(0, filed.code(), filed.all());
        assertTrue(filed.all().contains("filed"), filed.all());

        Journey.Ran indexed = Journey.oss(home, work, "memory", "index");
        assertEquals(0, indexed.code(), indexed.all());

        // The words are not the note's words. Shared-term ranking is what makes this a search
        // rather than a grep, and it is the only thing a fresh install has.
        Journey.Ran found = Journey.oss(home, work, "memory", "search", "lock ordering deadlock");
        assertEquals(0, found.code(), found.all());
        assertTrue(found.all().contains("Pool deadlock"), "the note was filed and not found: " + found.all());
    }

    @Test
    @DisplayName("searching an empty archive says so instead of ranking nothing")
    void searchingNothing(@TempDir Path home, @TempDir Path work) throws Exception {
        // Every nearest-neighbour search has a nearest neighbour, so an archive holding nothing
        // about a question can still hand back its least-unrelated file in the shape of a real hit.
        // With nothing filed at all there is no excuse for a result.
        Journey.Ran found = Journey.oss(home, work, "memory", "search", "lock ordering deadlock");

        assertEquals(0, found.code(), found.all());
        assertFalse(found.all().contains("0.3"), "an empty archive produced a scored hit: " + found.all());
    }

    @Test
    @DisplayName("filing the same note twice does not file it twice")
    void filingIsIdempotent(@TempDir Path home, @TempDir Path work) throws Exception {
        aNote(work);
        Journey.oss(home, work, "memory", "file", "note.md");
        Journey.Ran again = Journey.oss(home, work, "memory", "file", "note.md");

        assertEquals(0, again.code(), again.all());
        // One note, however many times it is filed. An archive that grows a copy per invocation
        // is one that answers the same question three times a year from now.
        try (var kept = Files.walk(home.resolve("memory"))) {
            long notes =
                    kept.filter(p -> p.getFileName().toString().endsWith(".md")).count();
            assertTrue(notes <= 1, "filing twice left " + notes + " notes");
        }
    }
}
