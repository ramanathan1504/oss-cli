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

import com.osscli.storage.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything this machine knows, searchable in milliseconds instead of fourteen seconds.
 *
 * <p>The measurement that produced this: putting the corpus in front of every question meant
 * loading 10,702 issues and 51,668 note passages and adding all of them to an in-memory index —
 * <b>14.3 seconds before the model was called</b>, and paid again on the next command, because a
 * command-line process runs once and exits. The second search inside the same process took 39
 * milliseconds. The work was never the searching; it was rebuilding an index the machine had
 * already built the last time it was asked.
 *
 * <p>So the index lives in SQLite beside the rows. Filled the first time it is needed, topped up
 * when the counts move, and queried directly after that.
 */
public final class Corpuses {

    /** How many hits a question gets. Eight is what the prompt block was already showing. */
    private static final int HITS = 8;

    /** FTS5's operators, which are noise when somebody types them as English. */
    private static final java.util.Set<String> OPERATORS = java.util.Set.of("and", "not", "near");

    private Corpuses() {}

    /** One searchable thing, whatever it came from. */
    public record Hit(String id, String kind, String title, String excerpt) {}

    /**
     * Search everything, filling the index first if it has fallen behind.
     *
     * <p>Never throws: a corpus that cannot be read costs the answer depth, and there is no version
     * of that worth failing a question over.
     */
    public static List<Hit> search(String query) {
        List<Hit> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            fillIfStale(conn);
            String sql = "SELECT id, kind, title, excerpt FROM corpus_fts WHERE corpus_fts MATCH ?"
                    + " ORDER BY rank LIMIT " + HITS + ";";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, forFts(query));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Hit(
                                rs.getString("id"),
                                rs.getString("kind"),
                                rs.getString("title"),
                                rs.getString("excerpt")));
                    }
                }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * A query FTS5 will accept.
     *
     * <p>Everything a person types is a bare word here — punctuation is stripped and the words are
     * ORed. Passing the raw string through gets a syntax error the moment somebody asks about
     * {@code appender-ref} or types a quote, and a search that throws on a hyphen is a search
     * nobody trusts twice.
     */
    static String forFts(String query) {
        List<String> words = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            // FTS5's own operator words are dropped rather than searched for. Somebody typing
            // `"kafka" AND appender` means both words; leaving `and` in makes it a term that
            // matches nearly every row in the corpus and drowns the two that were asked for.
            if (word.length() > 2 && !OPERATORS.contains(word)) {
                words.add(word);
            }
        }
        return words.isEmpty() ? "\"" + query.replace("\"", "") + "\"" : String.join(" OR ", words);
    }

    /** How many rows are indexed, and how many exist. Cheap, and the only thing checked. */
    private static void fillIfStale(Connection conn) throws Exception {
        long indexed;
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM corpus_fts;")) {
            indexed = rs.next() ? rs.getLong(1) : 0;
        }
        long available = count(conn, "SELECT count(*) FROM issues;")
                + count(conn, "SELECT count(*) FROM personal_chat_chunk WHERE length(content) > 120;")
                + count(conn, "SELECT count(*) FROM chat_turn WHERE length(content) > 40;");
        if (indexed == available) {
            return;
        }
        // Counts moved, so the index is rebuilt rather than reconciled. Reconciling means knowing
        // which rows changed, which means triggers on three tables and a migration that has to be
        // right the first time; rebuilding is seconds, happens when the corpus actually grew, and
        // cannot drift.
        rebuild(conn);
    }

    private static long count(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void rebuild(Connection conn) throws Exception {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM corpus_fts;");
        }
        String insert = "INSERT INTO corpus_fts (id, kind, title, body, excerpt) VALUES (?, ?, ?, ?, ?);";
        try (PreparedStatement ins = conn.prepareStatement(insert)) {
            copy(conn, ins, "SELECT repository, number, title, body FROM issues;", rs -> new String[] {
                rs.getString(1) + "#" + rs.getLong(2), "issue", rs.getString(3), rs.getString(4), rs.getString(3)
            });
            copy(
                    conn,
                    ins,
                    "SELECT file_path, chunk_index, content FROM personal_chat_chunk WHERE length(content) > 120;",
                    rs -> {
                        String path = rs.getString(1);
                        String name = path == null ? "note" : path.substring(path.lastIndexOf('/') + 1);
                        String content = rs.getString(3);
                        return new String[] {
                            "note:" + name + "#" + rs.getInt(2), "note", name, content, excerpt(content)
                        };
                    });
            copy(conn, ins, "SELECT session_id, seq, role, content FROM chat_turn WHERE length(content) > 40;", rs -> {
                String content = rs.getString(4);
                return new String[] {
                    "asked:" + rs.getLong(1) + "#" + rs.getInt(2),
                    "conversation",
                    rs.getString(3),
                    content,
                    excerpt(content)
                };
            });
            ins.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private interface Row {
        String[] of(ResultSet rs) throws Exception;
    }

    private static void copy(Connection conn, PreparedStatement ins, String select, Row row) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(select);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String[] values = row.of(rs);
                for (int i = 0; i < values.length; i++) {
                    ins.setString(i + 1, values[i] == null ? "" : values[i]);
                }
                ins.addBatch();
            }
        }
    }

    /** The readable part, front matter skipped and capped — this goes into a prompt. */
    static String excerpt(String content) {
        if (content == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String line : content.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("---") || t.startsWith("#") || t.matches("^[a-z_]+:.*")) {
                continue;
            }
            b.append(t).append(' ');
            if (b.length() > 260) {
                break;
            }
        }
        String out = b.toString().strip();
        return out.length() > 300 ? out.substring(0, 299) + "…" : out;
    }
}
