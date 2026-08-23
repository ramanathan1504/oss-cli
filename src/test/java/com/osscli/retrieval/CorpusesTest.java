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

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The corpus, searched where it lives.
 *
 * <p>Putting what this machine knows in front of every question meant loading 10,702 issues and
 * 51,668 note passages and indexing all of them in process: <b>14.3 seconds before the model was
 * called</b>, on every command, because a command-line process runs once and exits. The second
 * search in the same process took 39 milliseconds — the work was never the searching.
 *
 * <p>The risk in moving it to FTS5 is not that it is slow. It is that a question containing a
 * hyphen, a quote or the word {@code AND} becomes a syntax error, and a search that throws on
 * {@code appender-ref} is one nobody trusts twice. Most of these are about that.
 */
class CorpusesTest {

    @Test
    @DisplayName("a plain question becomes words, ORed")
    void ordinaryQuestions() {
        assertEquals("kafka OR appender", Corpuses.forFts("kafka appender"));
        assertEquals("rollover", Corpuses.forFts("a rollover"), "words of two letters or fewer carry no signal");
    }

    @Test
    @DisplayName("punctuation that is FTS syntax is stripped, not escaped into meaning")
    void punctuationCannotBecomeSyntax() {
        // Each of these is a real thing somebody types. Passed through raw, every one is either a
        // syntax error or a query that means something the reader did not ask for.
        assertEquals("appender OR ref", Corpuses.forFts("appender-ref"));
        assertEquals("kafka OR appender", Corpuses.forFts("\"kafka\" AND appender"));
        assertEquals("log4j", Corpuses.forFts("log4j*"));
        assertEquals("config OR xml", Corpuses.forFts("config.xml"));
        assertEquals("match", Corpuses.forFts("NEAR(match)"), "NEAR is an operator, not a word somebody meant");
    }

    @Test
    @Timeout(20)
    @DisplayName("no random punctuation produces a query FTS5 will refuse")
    void fuzzTheQuery() throws Exception {
        // Deterministic seed: a fuzz test that finds a failure you cannot reproduce is a rumour.
        Random random = new Random(20260823L);
        String alphabet = "abc XY \"'`*()[]{}:^-+.,/\\|&!?~<>=$#@%\n\t";

        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
                java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE VIRTUAL TABLE t USING fts5(id UNINDEXED, title, body)");
            s.execute("INSERT INTO t VALUES ('a','kafka appender','the broker blocks startup')");

            for (int i = 0; i < 400; i++) {
                StringBuilder q = new StringBuilder();
                for (int n = random.nextInt(12); n > 0; n--) {
                    q.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String prepared = Corpuses.forFts(q.toString());
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT id FROM t WHERE t MATCH ?")) {
                    ps.setString(1, prepared);
                    ps.executeQuery().close();
                } catch (Exception e) {
                    throw new AssertionError("input <" + q + "> became <" + prepared + ">: " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    @DisplayName("an excerpt skips front matter and is capped, because it goes into a prompt")
    void excerptsAreReadableAndBounded() {
        String note =
                "---\ntags: [kafka]\nsource: somewhere\n---\n# Heading\n\nThe fix was to change break to return.\n";

        String excerpt = Corpuses.excerpt(note);

        assertTrue(excerpt.startsWith("The fix was"), excerpt);
        assertFalse(excerpt.contains("tags:"), "front matter says nothing about what was done: " + excerpt);
        assertFalse(excerpt.contains("# Heading"), excerpt);
        assertTrue(Corpuses.excerpt("x ".repeat(2000)).length() <= 300, "eight of these share one prompt");
        assertEquals("", Corpuses.excerpt(null));
    }

    @Test
    @DisplayName("searching an unreadable corpus is empty, never an exception")
    void failureIsEmpty() {
        // The suite runs against a store with no corpus_fts table until a migration makes one, so
        // this is the real path rather than a hypothetical: a decoration must never fail the
        // command it decorates.
        assertTrue(Corpuses.search("anything").isEmpty());
        assertTrue(Corpuses.search("").isEmpty());
        assertTrue(Corpuses.search(null).isEmpty());
    }
}
