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
 * Cutting a note into pieces the embedder can actually read.
 *
 * <p>The embedder's input window is a few hundred tokens. A note longer than that is represented by
 * its opening and nothing else, so the middle of a long document becomes unfindable — which is the
 * failure this class exists to prevent. Two properties matter: nothing may be dropped, and
 * consecutive passages must overlap, because a passage boundary landing mid-sentence would other
 * otherwise cut a matching phrase in half and lose it from both sides.
 */
class PassageSplitterTest {

    private static String words(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("word").append(i).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    @DisplayName("a short note is one passage, unchanged")
    void shortNote() {
        String text = "A short note about rollover.";
        assertEquals(List.of(text), PassageSplitter.split(text));
    }

    @Test
    @DisplayName("a long note becomes several passages")
    void longNoteSplits() {
        List<String> parts = PassageSplitter.split(words(2000));
        assertTrue(parts.size() > 1, "a long note must be split, got " + parts.size());
    }

    @Test
    @DisplayName("no passage exceeds the requested size by more than a word")
    void passagesAreBounded() {
        for (String p : PassageSplitter.split(words(3000), 500, 100)) {
            assertTrue(p.length() <= 600, "passage of " + p.length() + " chars exceeds the bound");
        }
    }

    @Test
    @DisplayName("consecutive passages overlap, so a phrase on a boundary is not lost")
    void passagesOverlap() {
        List<String> parts = PassageSplitter.split(words(1200), 400, 120);
        if (parts.size() < 2) {
            return;
        }
        String endOfFirst = parts.get(0).substring(Math.max(0, parts.get(0).length() - 60));
        String[] tokens = endOfFirst.trim().split("\\s+");
        String lastWord = tokens[tokens.length - 1];
        assertTrue(parts.get(1).contains(lastWord), "the tail of one passage should reappear in the next");
    }

    @Test
    @DisplayName("every word of the original survives somewhere")
    void nothingIsDropped() {
        String text = words(1500);
        String joined = String.join(" ", PassageSplitter.split(text, 400, 80));
        for (String w : new String[] {"word0", "word750", "word1499"}) {
            assertTrue(joined.contains(w), w + " was dropped by splitting");
        }
    }

    @Test
    @DisplayName("empty and null produce no passages rather than one empty one")
    void emptyInput() {
        assertTrue(PassageSplitter.split(null).isEmpty());
        assertTrue(PassageSplitter.split("").isEmpty());
        assertTrue(PassageSplitter.split("   \n\t ").isEmpty());
    }

    @Test
    @DisplayName("no passage is blank")
    void noBlankPassages() {
        for (String p : PassageSplitter.split(words(900), 300, 60)) {
            assertFalse(p.isBlank(), "a blank passage would embed to a meaningless vector");
        }
    }

    @Test
    @DisplayName("the defaults are the documented ones")
    void defaults() {
        assertEquals(1500, PassageSplitter.DEFAULT_PASSAGE_CHARS);
        assertEquals(200, PassageSplitter.DEFAULT_OVERLAP_CHARS);
    }
}
