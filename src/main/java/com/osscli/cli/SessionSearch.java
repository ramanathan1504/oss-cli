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
package com.osscli.cli;

import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import com.osscli.retrieval.LocalEmbedder;
import com.osscli.storage.ChatSessionStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds a past conversation from a description of it rather than its id.
 *
 * <p>You remember what you were doing, not when. "the flaky test one" has to find a conversation
 * that never used the word flaky, which is exactly what the in-process embedder is for -- and it is
 * already there, needing no server, no account and no network.
 *
 * <p>Without the model this falls back to matching words. That is a real downgrade and it says so,
 * rather than returning a thin result set that looks like an answer. Term matching is the floor of
 * this tool, not its failure mode.
 */
final class SessionSearch {

    private static final Logger LOGGER = LogManager.getLogger(SessionSearch.class);

    /**
     * Below this, a match is the embedder being polite rather than agreeing.
     *
     * <p>Deliberately low: the corpus being searched is a handful of the user's own conversations,
     * not a million documents, so the cost of one weak extra row is a line they scroll past, while
     * the cost of dropping the right one is the feature not working.
     */
    private static final double MIN_SIMILARITY = 0.25;

    /** How much of a conversation is embedded for matching. The opening and the shape of it, not all of it. */
    private static final int SAMPLE_CHARS = 2_000;

    private SessionSearch() {}

    /** Sessions that match {@code query}, best first. */
    static List<ChatSession> rank(List<ChatSession> sessions, String query) {
        LocalEmbedder embedder = com.osscli.retrieval.Embeddings.ifPresent(m -> LOGGER.info("  {}", m));
        if (embedder == null) {
            LOGGER.warn("  ⚠ No local model, so this is matching words, not meaning.");
            LOGGER.warn("    {}", com.osscli.retrieval.Embeddings.ABSENT_HINT);
            return byTerm(sessions, query);
        }
        try {
            return bySimilarity(sessions, query, embedder);
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Search by meaning failed ({}), so this is matching words instead.", e.getMessage());
            return byTerm(sessions, query);
        }
    }

    private static List<ChatSession> bySimilarity(List<ChatSession> sessions, String query, LocalEmbedder embedder)
            throws Exception {
        double[] q = embedder.embed(query);
        List<Scored> scored = new ArrayList<>();
        for (ChatSession s : sessions) {
            double[] v = embedder.embed(sample(s));
            double sim = cosine(q, v);
            if (sim >= MIN_SIMILARITY) {
                scored.add(new Scored(s, sim));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<ChatSession> out = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            out.add(s.session());
        }
        return out;
    }

    /** Substring matching over the same text the embedder would have seen. */
    private static List<ChatSession> byTerm(List<ChatSession> sessions, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<ChatSession> out = new ArrayList<>();
        for (ChatSession s : sessions) {
            if (sample(s).toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(s);
            }
        }
        return out;
    }

    /** What a session "is", as text: its issue, its overview and the start of what was said. */
    private static String sample(ChatSession s) {
        StringBuilder b = new StringBuilder();
        b.append(s.repository()).append(" #").append(s.issueNumber()).append(' ');
        if (s.issueTitle() != null) {
            b.append(s.issueTitle()).append(' ');
        }
        if (s.overview() != null) {
            b.append(s.overview()).append(' ');
        }
        if (s.summary() != null) {
            b.append(s.summary()).append(' ');
        }
        try {
            for (ChatTurn t : ChatSessionStore.turns(s.id())) {
                if (b.length() >= SAMPLE_CHARS) {
                    break;
                }
                b.append(t.content()).append(' ');
            }
        } catch (Exception e) {
            // A session whose turns will not load still matches on its title and overview.
            LOGGER.debug("Could not read turns for session {}: {}", s.id(), e.getMessage());
        }
        return b.length() <= SAMPLE_CHARS ? b.toString() : b.substring(0, SAMPLE_CHARS);
    }

    /**
     * Cosine similarity, refusing to compare vectors of different lengths.
     *
     * <p>Mismatched lengths mean two different models, and truncating to the shorter one produces a
     * number that looks like a similarity and means nothing. Both vectors here come from the same
     * embedder in the same process, so this can only fire if that stops being true -- which is
     * precisely when a silent zero beats a plausible score.
     */
    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(ChatSession session, double score) {}
}
