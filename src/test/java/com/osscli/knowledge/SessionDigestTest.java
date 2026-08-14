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

import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of the digest that works with no model attached.
 *
 * <p>That half is the one most users see, so it is the one under test here. The generated line is
 * an improvement layered on top, and a test that needed a running model to pass would be a test
 * that fails on every machine that does not have one -- which is the arrangement this project
 * exists to avoid.
 */
class SessionDigestTest {

    @Test
    @DisplayName("the overview is the first thing the user actually asked")
    void overviewIsTheOpeningQuestion() {
        List<ChatTurn> turns = List.of(
                turn(1, ChatTurn.Role.USER, "why does the retry loop swallow the interrupt?"),
                turn(2, ChatTurn.Role.LOCAL, "because the catch block resets the flag"));

        assertEquals(
                "why does the retry loop swallow the interrupt?",
                SessionDigest.extractiveOverview(session(null), turns));
    }

    @Test
    @DisplayName("an answer that arrived before any question does not become the overview")
    void assistantTurnsAreNotTheOverview() {
        List<ChatTurn> turns = List.of(
                turn(1, ChatTurn.Role.LOCAL, "I have loaded the issue."),
                turn(2, ChatTurn.Role.USER, "right, where does it deadlock?"));

        assertEquals("right, where does it deadlock?", SessionDigest.extractiveOverview(session(null), turns));
    }

    @Test
    @DisplayName("a long opening question is cut to one line, not wrapped into the list")
    void overviewIsClipped() {
        String long_ = "a".repeat(500);
        String overview = SessionDigest.extractiveOverview(session(null), List.of(turn(1, ChatTurn.Role.USER, long_)));

        assertTrue(overview.length() <= 72, "the overview has to fit a column: " + overview.length());
        assertTrue(overview.endsWith("…"), "clipping is visible rather than silent");
    }

    @Test
    @DisplayName("newlines in a question never break the row they are printed in")
    void overviewIsOneLine() {
        String overview = SessionDigest.extractiveOverview(
                session(null), List.of(turn(1, ChatTurn.Role.USER, "first line\nsecond line\n\nthird")));

        assertFalse(overview.contains("\n"), "a multi-line overview would corrupt the picker's layout");
    }

    @Test
    @DisplayName("with nothing said yet, the issue title stands in")
    void fallsBackToTheIssueTitle() {
        assertEquals(
                "Flaky integration test",
                SessionDigest.extractiveOverview(session("Flaky integration test"), List.of()));
    }

    @Test
    @DisplayName("and with neither, it says so rather than showing an empty row")
    void fallsBackToSomethingVisible() {
        assertEquals("(no question yet)", SessionDigest.extractiveOverview(session(null), List.of()));
    }

    @Test
    @DisplayName("a short conversation is never compacted")
    void shortConversationsAreLeftAlone() {
        List<ChatTurn> turns =
                List.of(turn(1, ChatTurn.Role.USER, "short question"), turn(2, ChatTurn.Role.LOCAL, "short answer"));

        assertFalse(SessionDigest.needsCompaction(session(null), turns));
    }

    @Test
    @DisplayName("a conversation past the context budget is compacted")
    void longConversationsAreCompacted() {
        // Sized from the budget rather than from a number typed here. This test previously assumed
        // the 16,000-character constant that the budget replaced, and so began passing vacuously
        // the moment the constant moved -- the failure mode a fixed fixture always eventually has.
        int turnSize = 1_000;
        int enough = SessionDigest.budgetChars(0) / turnSize + 2;

        List<ChatTurn> turns = new ArrayList<>();
        for (int i = 0; i < enough; i++) {
            turns.add(turn(i + 1, i % 2 == 0 ? ChatTurn.Role.USER : ChatTurn.Role.LOCAL, "x".repeat(turnSize)));
        }

        assertTrue(SessionDigest.needsCompaction(session(null), turns));
    }

    @Test
    @DisplayName("an already-folded summary counts towards the budget, so folding cannot loop forever")
    void theSummaryItselfCounts() {
        ChatSession withSummary = new ChatSession(
                1,
                "owner/name",
                42,
                null,
                "local",
                "s".repeat(SessionDigest.budgetChars(0) + 1),
                null,
                stamp(),
                stamp(),
                null,
                null,
                null,
                null,
                null,
                1);

        assertTrue(
                SessionDigest.needsCompaction(withSummary, List.of(turn(1, ChatTurn.Role.USER, "tiny"))),
                "a summary that has grown past the budget is itself over budget");
    }

    private static ChatSession session(String title) {
        return new ChatSession(
                1, "owner/name", 42, title, "local", null, null, stamp(), stamp(), null, null, null, null, null, 0);
    }

    private static String stamp() {
        return java.time.Instant.now().toString();
    }

    private static ChatTurn turn(int seq, ChatTurn.Role role, String content) {
        return new ChatTurn(seq, 1, seq, role, content, stamp());
    }
}
