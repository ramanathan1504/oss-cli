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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import com.osscli.storage.DatabaseManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That one conversation cannot outgrow the window it is sent to.
 *
 * <p>The transcript folds itself when it gets long, which was already true. What was not true is
 * that it folded at the right moment: the threshold was a bare 16,000 characters, while the
 * retrieved notes beside it in the same prompt were independently allowed up to 6,000 tokens. Two
 * budgets that cannot see each other are not a budget, and their sum overflowed a window neither of
 * them had exceeded. These tests pin the arithmetic that joins them.
 */
class SessionBudgetTest {

    @BeforeAll
    static void schema() throws Exception {
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store.");
        DatabaseManager.initializeSchema();
    }

    // ==========================================
    // The budget
    // ==========================================

    @Test
    @DisplayName("what the notes take, the transcript does not get")
    void otherPromptContentShrinksTheBudget() {
        int alone = SessionDigest.budgetChars(0);
        int crowded = SessionDigest.budgetChars(8_000);
        assertTrue(
                crowded < alone,
                "an 8,000-character block of notes must cost the transcript room: " + alone + " vs " + crowded);
        assertEquals(8_000, alone - crowded, "the cost should be exactly what the notes occupy");
    }

    @Test
    @DisplayName("the floor is the tail, because the tail is never folded away")
    void budgetNeverFallsBelowTheKeptTail() {
        // A retrieval block larger than the whole window would drive a naive subtraction negative,
        // and a negative budget means "fold everything" -- which compaction cannot do, since the
        // most recent turns are always kept verbatim. Promising a fold that cannot happen would
        // put the loop into a compaction attempt on every single turn.
        int absurd = SessionDigest.budgetChars(10_000_000);
        assertTrue(absurd > 0, "budget went to " + absurd + "; a non-positive budget is unsatisfiable");
        assertEquals(SessionDigest.budgetChars(1_000_000), absurd, "below the floor every input gives the same floor");
    }

    @Test
    @DisplayName("a negative declaration is treated as nothing, not as extra room")
    void negativeOtherContentIsIgnored() {
        assertEquals(SessionDigest.budgetChars(0), SessionDigest.budgetChars(-5_000));
    }

    // ==========================================
    // The decision to fold
    // ==========================================

    @Test
    @DisplayName("a short conversation is left alone")
    void shortTranscriptIsNotCompacted() {
        ChatSession s = session(null);
        assertFalse(SessionDigest.needsCompaction(s, turns(3, 100), 0));
    }

    @Test
    @DisplayName("a long conversation folds")
    void longTranscriptIsCompacted() {
        ChatSession s = session(null);
        assertTrue(SessionDigest.needsCompaction(s, turns(40, 2_000), 0));
    }

    @Test
    @DisplayName("the same conversation folds sooner when the notes are large")
    void theNotesCanTipItOver() {
        ChatSession s = session(null);
        // Sized to sit under the budget on its own and over it once a large retrieval block is
        // charged against the same window. This is exactly the case the old bare constant missed.
        List<ChatTurn> t = turns(12, 1_500);
        int used = SessionDigest.used(s, t);
        assertTrue(used < SessionDigest.budgetChars(0), "fixture must fit when it is alone; it used " + used);
        assertFalse(SessionDigest.needsCompaction(s, t, 0));
        assertTrue(
                SessionDigest.needsCompaction(s, t, 20_000),
                "20,000 characters of retrieved notes must push a " + used + "-character transcript over");
    }

    @Test
    @DisplayName("the stored summary is charged too, not counted as free")
    void theSummaryCountsAgainstTheBudget() {
        List<ChatTurn> t = turns(2, 100);
        int bare = SessionDigest.used(session(null), t);
        int withSummary = SessionDigest.used(session("x".repeat(4_000)), t);
        assertTrue(
                withSummary > bare + 3_900,
                "a fold that is not itself budgeted would grow without limit across repeated folds");
    }

    // ==========================================
    // Fixtures
    // ==========================================

    private static ChatSession session(String summary) {
        String now = Instant.now().toString();
        return new ChatSession(
                1L, "owner/name", 1L, "a title", "ollama", summary, null, now, now, null, null, null, null, null, 0);
    }

    /** {@code count} turns of {@code chars} characters each, alternating who spoke. */
    private static List<ChatTurn> turns(int count, int chars) {
        String now = Instant.now().toString();
        List<ChatTurn> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ChatTurn(
                    i + 1L, 1L, i + 1, i % 2 == 0 ? ChatTurn.Role.USER : ChatTurn.Role.LOCAL, "x".repeat(chars), now));
        }
        return out;
    }
}
