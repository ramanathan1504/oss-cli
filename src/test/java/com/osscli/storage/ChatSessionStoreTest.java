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
package com.osscli.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a conversation survives the process it was typed into.
 *
 * <p>The point of these tables is the case nobody tests by hand: the terminal is killed. So the
 * tests below never end a session politely before reading it back -- they write turns and then read
 * them as a different caller would, which is exactly what resuming does.
 */
class ChatSessionStoreTest {

    private static final String REPO = "owner/name";

    @BeforeAll
    static void schema() throws Exception {
        // Same refusal as SchemaTest, and for the same reason: these tests write to whatever
        // database AppPaths resolves, and a redirection that quietly failed to apply once cost a
        // real 496 MB store. Assert where we are pointing rather than trusting the build.
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store. "
                        + "Set OSS_CLI_HOME (the environment variable, not the oss.cli.home property).");
        DatabaseManager.initializeSchema();
    }

    @Test
    @DisplayName("turns are readable the moment they are written, without the session being ended")
    void turnsSurviveWithoutAGracefulExit() throws Exception {
        long id = ChatSessionStore.open(REPO, 4129, "Intermittent failure on CI", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "why does this only fail on CI?");
        ChatSessionStore.append(id, ChatTurn.Role.LOCAL, "the clock is different there");

        // No end() call: this is the killed-terminal case.
        List<ChatTurn> turns = ChatSessionStore.turns(id);
        assertEquals(2, turns.size());
        assertEquals(ChatTurn.Role.USER, turns.get(0).role());
        assertEquals("why does this only fail on CI?", turns.get(0).content());
        assertEquals(ChatTurn.Role.LOCAL, turns.get(1).role());
        assertEquals(1, turns.get(0).seq());
        assertEquals(2, turns.get(1).seq());
    }

    @Test
    @DisplayName("a session that was never spoken in stays out of the history list")
    void emptySessionsAreNotListed() throws Exception {
        long empty = ChatSessionStore.open(REPO, 777, "started and abandoned", "local", null);
        List<ChatSession> listed = ChatSessionStore.recent(REPO, 777L, 10);
        assertTrue(listed.isEmpty(), "an empty session should not appear in history");

        ChatSessionStore.append(empty, ChatTurn.Role.USER, "actually, one thing");
        assertEquals(1, ChatSessionStore.recent(REPO, 777L, 10).size());
    }

    @Test
    @DisplayName("recent() is newest first and honours the repository and issue filters")
    void recentIsOrderedAndFiltered() throws Exception {
        long older = ChatSessionStore.open(REPO, 100, "first", "local", null);
        ChatSessionStore.append(older, ChatTurn.Role.USER, "older question");
        long newer = ChatSessionStore.open(REPO, 101, "second", "local", null);
        ChatSessionStore.append(newer, ChatTurn.Role.USER, "newer question");

        List<ChatSession> all = ChatSessionStore.recent(REPO, null, 50);
        int iOlder = indexOf(all, older);
        int iNewer = indexOf(all, newer);
        assertTrue(iNewer >= 0 && iOlder >= 0, "both sessions should be listed");
        assertTrue(iNewer < iOlder, "the most recently touched session comes first");

        List<ChatSession> justOne = ChatSessionStore.recent(REPO, 101L, 50);
        assertEquals(1, justOne.size());
        assertEquals(newer, justOne.get(0).id());

        assertTrue(ChatSessionStore.recent("someone/else", null, 50).isEmpty());
    }

    @Test
    @DisplayName("turn count comes back with the session, so the list does not have to load transcripts")
    void turnCountIsCarried() throws Exception {
        long id = ChatSessionStore.open(REPO, 202, "counting", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "one");
        ChatSessionStore.append(id, ChatTurn.Role.LOCAL, "two");
        ChatSessionStore.append(id, ChatTurn.Role.CLOUD, "three");

        ChatSession s = ChatSessionStore.byId(id);
        assertNotNull(s);
        assertEquals(3, s.turnCount());
    }

    @Test
    @DisplayName("ending releases the session; resuming it clears the end stamp")
    void endThenResume() throws Exception {
        long id = ChatSessionStore.open(REPO, 303, "ends and resumes", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "half a thought");

        ChatSessionStore.end(id);
        ChatSession ended = ChatSessionStore.byId(id);
        assertTrue(ended.ended());
        assertNull(ended.ownerPid(), "an ended session is held by nobody");
        assertFalse(ended.heldElsewhere(999999L, "some-other-host"));

        ChatSessionStore.claim(id);
        ChatSession resumed = ChatSessionStore.byId(id);
        assertFalse(resumed.ended(), "resuming reopens the session rather than starting a new one");
        assertEquals(ChatSessionStore.myPid(), resumed.ownerPid());
        assertEquals(1, ChatSessionStore.turns(id).size(), "resuming keeps what was already said");
    }

    @Test
    @DisplayName("a live session in another terminal is detected; this terminal's own is not")
    void heldElsewhere() throws Exception {
        long id = ChatSessionStore.open(REPO, 404, "two terminals", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "typed over here");

        ChatSession mine = ChatSessionStore.byId(id);
        assertFalse(
                mine.heldElsewhere(ChatSessionStore.myPid(), ChatSessionStore.myHost()),
                "a session this process owns is not held elsewhere");
        assertTrue(
                mine.heldElsewhere(ChatSessionStore.myPid() + 1, ChatSessionStore.myHost()),
                "another live pid on this host is a collision");
    }

    @Test
    @DisplayName("a heartbeat older than the stale window is treated as abandoned, not locked")
    void staleOwnershipIsNotALock() throws Exception {
        long id = ChatSessionStore.open(REPO, 505, "killed terminal", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "then the lid closed");

        // Age the heartbeat directly: this is the process-was-killed case, which cannot be
        // reproduced by waiting without making the suite take minutes.
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE chat_session SET updated_at = '2020-01-01T00:00:00Z' WHERE id = " + id + ";");
        }

        ChatSession stale = ChatSessionStore.byId(id);
        assertFalse(
                stale.heldElsewhere(ChatSessionStore.myPid() + 1, ChatSessionStore.myHost()),
                "a dead process must not lock the user out of their own conversation");
    }

    @Test
    @DisplayName("forking copies the history and leaves the original alone")
    void forkCarriesHistory() throws Exception {
        long original = ChatSessionStore.open(REPO, 606, "forked", "local", null);
        ChatSessionStore.append(original, ChatTurn.Role.USER, "first");
        ChatSessionStore.append(original, ChatTurn.Role.LOCAL, "second");

        long fork = ChatSessionStore.open(REPO, 606, "forked", "local", original);
        ChatSessionStore.copyTurns(original, fork);

        assertEquals(2, ChatSessionStore.turns(fork).size());
        assertEquals(2, ChatSessionStore.turns(original).size(), "the original is untouched");
        assertEquals(original, ChatSessionStore.byId(fork).parentId());
        assertEquals("first", ChatSessionStore.turns(fork).get(0).content(), "order is preserved");
    }

    @Test
    @DisplayName("the transcript puts the folded summary before the turns that remain")
    void transcriptLeadsWithTheSummary() throws Exception {
        long id = ChatSessionStore.open(REPO, 707, "long one", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "the newest question");
        ChatSessionStore.setSummary(id, "we had already ruled out the cache");

        ChatSession s = ChatSessionStore.byId(id);
        String transcript = ChatSessionStore.transcript(s, ChatSessionStore.turns(id));

        assertTrue(transcript.contains("we had already ruled out the cache"));
        assertTrue(
                transcript.indexOf("we had already ruled out the cache") < transcript.indexOf("the newest question"),
                "the summary is the earlier part of the conversation and reads first");
        assertTrue(transcript.contains("User: the newest question"));
    }

    @Test
    @DisplayName("the note path is remembered, so a resumed conversation rewrites one note")
    void notePathRoundTrips() throws Exception {
        long id = ChatSessionStore.open(REPO, 808, "filed once", "local", null);
        ChatSessionStore.append(id, ChatTurn.Role.USER, "something worth keeping");
        ChatSessionStore.setNotePath(id, "/tmp/archive/oss-cli/Issue-808-chat-20260814-120000.md");

        assertEquals(
                "/tmp/archive/oss-cli/Issue-808-chat-20260814-120000.md",
                ChatSessionStore.byId(id).notePath());
    }

    @Test
    @DisplayName("the database is in WAL mode, so a second terminal can read while one writes")
    void walIsOn() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
            assertTrue(rs.next());
            assertEquals("wal", rs.getString(1).toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("statements wait for a busy database instead of failing immediately")
    void busyTimeoutIsSet() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout;")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1000, "busy_timeout should be seconds, not milliseconds-by-accident");
        }
    }

    @Test
    @DisplayName("two connections can write to the same session without either being refused")
    void concurrentWritersBothLand() throws Exception {
        long id = ChatSessionStore.open(REPO, 909, "three terminals", "local", null);

        // Two writers interleaved, each opening its own connection exactly as separate oss
        // processes do. Under the old rollback-journal default one of these threw SQLITE_BUSY.
        Thread a = new Thread(() -> appendQuietly(id, "from terminal one", 5));
        Thread b = new Thread(() -> appendQuietly(id, "from terminal two", 5));
        a.start();
        b.start();
        a.join();
        b.join();

        List<ChatTurn> turns = ChatSessionStore.turns(id);
        assertEquals(10, turns.size(), "every turn from both writers landed");
        for (int i = 0; i < turns.size(); i++) {
            assertEquals(i + 1, turns.get(i).seq(), "sequence numbers stay unique and gapless");
        }
    }

    private static void appendQuietly(long sessionId, String text, int times) {
        for (int i = 0; i < times; i++) {
            try {
                ChatSessionStore.append(sessionId, ChatTurn.Role.USER, text + " #" + i);
            } catch (Exception e) {
                throw new IllegalStateException("concurrent append failed: " + e.getMessage(), e);
            }
        }
    }

    private static int indexOf(List<ChatSession> sessions, long id) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }
}
