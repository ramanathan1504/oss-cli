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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The floor everything else stands on.
 *
 * <p>Term search is what answers when no model has been fetched, which is the state every new
 * install begins in and some stay in permanently. It is not a degraded mode to be tolerated — for
 * those users it is the whole product, so it has to actually rank sensibly rather than merely
 * return something.
 */
class TextIndexTest {

    private static TextIndex indexed() {
        TextIndex ix = new TextIndex();
        ix.add("a", "Log rotation with zstd", "When a file grows past its size trigger the old segment is compressed.");
        ix.add(
                "b",
                "Consumer retry semantics",
                "At-least-once delivery means a consumer may see the same record twice.");
        ix.add("c", "Rotation throughput", "Compression level three is the default because throughput matters more.");
        ix.build();
        return ix;
    }

    @Test
    @DisplayName("a matching term finds the document that contains it")
    void findsByTerm() {
        List<TextIndex.Hit> hits = indexed().search("zstd", 5);
        assertFalse(hits.isEmpty(), "a term present in exactly one document must find it");
        assertEquals("a", hits.get(0).id());
    }

    @Test
    @DisplayName("a term nobody uses finds nothing rather than everything")
    void unmatchedTerm() {
        assertTrue(indexed().search("kubernetes", 5).isEmpty());
    }

    @Test
    @DisplayName("a rare term outranks a common one")
    void rareTermsWeighMore() {
        List<TextIndex.Hit> hits = indexed().search("zstd compression", 5);
        assertEquals("a", hits.get(0).id(), "the document with the rare term should lead");
    }

    @Test
    @DisplayName("the limit is honoured")
    void limitHonoured() {
        assertTrue(indexed().search("compression rotation consumer", 1).size() <= 1);
    }

    @Test
    @DisplayName("results come back best first")
    void orderedByScore() {
        List<TextIndex.Hit> hits = indexed().search("compression throughput", 5);
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(), "results are not ordered by score");
        }
    }

    @Test
    @DisplayName("case does not matter")
    void caseInsensitive() {
        assertFalse(indexed().search("ZSTD", 5).isEmpty());
    }

    @Test
    @DisplayName("an empty query returns nothing rather than everything")
    void emptyQuery() {
        assertTrue(indexed().search("", 5).isEmpty());
        assertTrue(indexed().search("   ", 5).isEmpty());
    }

    @Test
    @DisplayName("an empty index answers rather than failing")
    void emptyIndex() {
        TextIndex ix = new TextIndex();
        ix.build();
        assertEquals(0, ix.size());
        assertTrue(ix.search("anything", 5).isEmpty());
    }

    @Test
    @DisplayName("size counts what was added")
    void sizeCounts() {
        assertEquals(3, indexed().size());
    }

    @Test
    @DisplayName("the title is searched, not only the body")
    void titleIsSearched() {
        TextIndex ix = new TextIndex();
        ix.add("x", "Deadlock in the network appender", "unrelated body text about configuration");
        ix.build();
        assertFalse(ix.search("deadlock", 5).isEmpty(), "a word in the title must be findable");
    }
}
