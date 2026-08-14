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

import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat sessions and their turns.
 *
 * <p>Every write here is one statement against one row, on a connection opened and closed around
 * it. That is not an accident of style: the user may well have {@code sync} running in another
 * window, and a chat that held a transaction open across a person's thinking time would block that
 * sync for minutes. Short writes are what make several terminals usable at once.
 *
 * <p>Reads are correspondingly cheap and are never cached, so {@code oss history} in one terminal
 * shows a session another terminal is still typing into.
 */
public final class ChatSessionStore {

    private ChatSessionStore() {}

    /** Identifies this process for the heartbeat. */
    public static long myPid() {
        return ProcessHandle.current().pid();
    }

    /** Identifies this machine, so a pid from another laptop is never mistaken for a local one. */
    public static String myHost() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        return host;
    }

    private static String now() {
        return Instant.now().toString();
    }

    // ==========================================
    // Sessions
    // ==========================================

    /** Opens a session and returns its id. {@code parentId} is set only when forking an in-use session. */
    public static long open(String repository, long issueNumber, String issueTitle, String provider, Long parentId)
            throws SQLException {
        String sql = "INSERT INTO chat_session (repository, issue_number, issue_title, provider, started_at,"
                + " updated_at, owner_pid, owner_host, parent_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";
        String stamp = now();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, repository);
            ps.setLong(2, issueNumber);
            ps.setString(3, issueTitle);
            ps.setString(4, provider);
            ps.setString(5, stamp);
            ps.setString(6, stamp);
            ps.setLong(7, myPid());
            ps.setString(8, myHost());
            if (parentId == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setLong(9, parentId);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /**
     * Takes ownership of an existing session and clears any end stamp.
     *
     * <p>Resuming a session that was ended is ordinary -- most are ended deliberately -- so this
     * reopens rather than refusing.
     */
    public static void claim(long sessionId) throws SQLException {
        String sql = "UPDATE chat_session SET owner_pid = ?, owner_host = ?, updated_at = ?, ended_at = NULL"
                + " WHERE id = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, myPid());
            ps.setString(2, myHost());
            ps.setString(3, now());
            ps.setLong(4, sessionId);
            ps.executeUpdate();
        }
    }

    /**
     * Marks the heartbeat without adding a turn.
     *
     * <p>Called while the user is typing, so a session someone is thinking in does not look
     * abandoned to the terminal next door.
     */
    public static void touch(long sessionId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement("UPDATE chat_session SET updated_at = ? WHERE id = ?;")) {
            ps.setString(1, now());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    /** Ends the session and releases it, so another terminal can resume it without a warning. */
    public static void end(long sessionId) throws SQLException {
        String sql = "UPDATE chat_session SET ended_at = ?, updated_at = ?, owner_pid = NULL, owner_host = NULL"
                + " WHERE id = ?;";
        String stamp = now();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stamp);
            ps.setString(2, stamp);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        }
    }

    public static void setOverview(long sessionId, String overview) throws SQLException {
        update(sessionId, "overview", overview);
    }

    public static void setSummary(long sessionId, String summary) throws SQLException {
        update(sessionId, "summary", summary);
    }

    public static void setNotePath(long sessionId, String notePath) throws SQLException {
        update(sessionId, "note_path", notePath);
    }

    private static void update(long sessionId, String column, String value) throws SQLException {
        // The column name is a literal from the three callers above, never user input.
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE chat_session SET " + column + " = ? WHERE id = ?;")) {
            ps.setString(1, value);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    private static final String SELECT_SESSION =
            "SELECT s.id, s.repository, s.issue_number, s.issue_title, s.provider, s.summary, s.overview,"
                    + " s.started_at, s.updated_at, s.ended_at, s.note_path, s.owner_pid, s.owner_host, s.parent_id,"
                    + " (SELECT COUNT(*) FROM chat_turn t WHERE t.session_id = s.id) AS turns"
                    + " FROM chat_session s";

    /**
     * Sessions newest first.
     *
     * <p>Empty sessions are left out. Starting {@code chat} and pressing ctrl-c before typing
     * anything creates a row with nothing in it, and a history listing padded with blank entries is
     * one nobody scrolls.
     */
    public static List<ChatSession> recent(String repository, Long issueNumber, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(SELECT_SESSION).append(" WHERE turns > 0");
        List<Object> params = new ArrayList<>();
        if (repository != null && !repository.isBlank()) {
            sql.append(" AND s.repository = ?");
            params.add(repository);
        }
        if (issueNumber != null) {
            sql.append(" AND s.issue_number = ?");
            params.add(issueNumber);
        }
        sql.append(" ORDER BY s.updated_at DESC LIMIT ?;");
        params.add(limit);

        List<ChatSession> out = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    public static ChatSession byId(long sessionId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(SELECT_SESSION + " WHERE s.id = ?;")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** The most recent non-empty session, optionally narrowed to one repository and issue. */
    public static ChatSession latest(String repository, Long issueNumber) throws SQLException {
        List<ChatSession> found = recent(repository, issueNumber, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static ChatSession read(ResultSet rs) throws SQLException {
        long ownerPid = rs.getLong("owner_pid");
        Long pid = rs.wasNull() ? null : ownerPid;
        long parent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parent;
        return new ChatSession(
                rs.getLong("id"),
                rs.getString("repository"),
                rs.getLong("issue_number"),
                rs.getString("issue_title"),
                rs.getString("provider"),
                rs.getString("summary"),
                rs.getString("overview"),
                rs.getString("started_at"),
                rs.getString("updated_at"),
                rs.getString("ended_at"),
                rs.getString("note_path"),
                pid,
                rs.getString("owner_host"),
                parentId,
                rs.getInt("turns"));
    }

    // ==========================================
    // Turns
    // ==========================================

    /**
     * Appends a turn, then stamps the session.
     *
     * <p>The sequence number is chosen <b>inside</b> the insert rather than read first and written
     * after. Two statements would be a race between any two writers -- and not a theoretical one:
     * the user has three terminals open, and forking a busy session copies turns into it while the
     * original may still be live. Both would read the same {@code MAX(seq)} and the second would hit
     * the unique constraint. Worse, reading in one transaction and writing in the next asks SQLite
     * to upgrade a read lock to a write lock, which it refuses immediately rather than waiting, so
     * {@code busy_timeout} would not have covered it either.
     *
     * <p>As one statement it is a single write transaction that takes the write lock for its whole
     * duration, and a second writer simply waits its turn.
     *
     * <p>The heartbeat is a separate statement on purpose. What must be durable is the turn; the
     * stamp only decides whether another terminal thinks this session is live, and a process that
     * dies between the two leaves a saved turn and a slightly old timestamp -- the harmless way
     * round.
     */
    public static void append(long sessionId, ChatTurn.Role role, String content) throws SQLException {
        String stamp = now();
        String insert = "INSERT INTO chat_turn (session_id, seq, role, content, created_at) VALUES"
                + " (?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM chat_turn WHERE session_id = ?), ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setLong(1, sessionId);
                ps.setLong(2, sessionId);
                ps.setString(3, role.name());
                ps.setString(4, content);
                ps.setString(5, stamp);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE chat_session SET updated_at = ? WHERE id = ?;")) {
                ps.setString(1, stamp);
                ps.setLong(2, sessionId);
                ps.executeUpdate();
            }
        }
    }

    public static List<ChatTurn> turns(long sessionId) throws SQLException {
        List<ChatTurn> out = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT id, session_id, seq, role, content, created_at FROM chat_turn"
                                + " WHERE session_id = ? ORDER BY seq ASC;")) {
            ps.setLong(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChatTurn(
                            rs.getLong("id"),
                            rs.getLong("session_id"),
                            rs.getInt("seq"),
                            ChatTurn.Role.of(rs.getString("role")),
                            rs.getString("content"),
                            rs.getString("created_at")));
                }
            }
        }
        return out;
    }

    /**
     * Copies every turn of one session into another, keeping their order.
     *
     * <p>The fork path: when a session is already open in another terminal, the second one gets its
     * own session carrying the same history rather than two processes writing interleaved turns into
     * one transcript that afterwards reads as nonsense.
     */
    public static void copyTurns(long fromSessionId, long toSessionId) throws SQLException {
        for (ChatTurn t : turns(fromSessionId)) {
            append(toSessionId, t.role(), t.content());
        }
    }

    /** The transcript as the model sees it, with the folded summary first when there is one. */
    public static String transcript(ChatSession session, List<ChatTurn> turns) {
        StringBuilder b = new StringBuilder();
        if (session != null && session.summary() != null && !session.summary().isBlank()) {
            b.append("--- EARLIER IN THIS CONVERSATION (SUMMARISED) ---\n")
                    .append(session.summary())
                    .append("\n\n");
        }
        for (ChatTurn t : turns) {
            switch (t.role()) {
                case USER -> b.append("User: ");
                case LOCAL -> b.append("AI (Local): ");
                case CLOUD -> b.append("AI (Hybrid): ");
            }
            b.append(t.content()).append("\n\n");
        }
        return b.toString();
    }
}
