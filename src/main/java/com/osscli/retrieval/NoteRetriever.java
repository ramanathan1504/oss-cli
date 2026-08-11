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

import com.osscli.llm.OllamaClient;
import com.osscli.model.PromptContextChunk;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds passages in the user's own notes that bear on an arbitrary piece of text.
 *
 * <p>{@link ContextRetriever} answers "what do we know about issue N", which needs an issue to exist and be stored. A
 * pull request under review is neither: it is a title, a set of paths and a diff. This retrieves against that text
 * directly so work already done on the same area is reachable while reviewing, rather than only after the fact.
 *
 * <p>Scores passages rather than whole notes, keeping the best passage per note and deduplicating by content, for the
 * same reasons documented on the issue path: a long note otherwise crowds out every other source, and the corpus holds
 * genuinely duplicated notes saved under several names.
 */
public final class NoteRetriever {

    private static final Logger LOGGER = LogManager.getLogger(NoteRetriever.class);

    private NoteRetriever() {}

    /**
     * Returns the most relevant note passages for {@code queryText}, highest scoring first.
     *
     * <p>Returns empty rather than throwing when Ollama is unreachable or the corpus is unindexed. This is an
     * enrichment layer: a review must still be produced for a user who has no notes at all.
     *
     * @param excludePathSubstring notes whose path contains this are skipped; used to keep a review from being handed
     *     its own earlier output about the same change, which would read as corroboration while being an echo
     */
    public static List<PromptContextChunk> retrieveFor(String queryText, int limit, String excludePathSubstring) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            String embedModel = SqliteStorage.loadConfig("ollama.model.embedding");
            if (embedModel == null || embedModel.isBlank()) {
                embedModel = "all-minilm";
            }

            OllamaClient embedder = new OllamaClient(embedModel);
            if (!embedder.isServerReachable()) {
                return List.of();
            }

            List<SqliteStorage.ChatChunk> chunks = SqliteStorage.loadPersonalChatChunkVectors();
            if (chunks.isEmpty()) {
                return List.of();
            }

            double[] queryVector = embedder.generateEmbedding(queryText);
            double threshold = ContextRetriever.similarityThreshold();

            Map<String, SqliteStorage.ChatChunk> bestPerFile = new HashMap<>();
            Map<String, Double> bestScore = new HashMap<>();

            for (SqliteStorage.ChatChunk ch : chunks) {
                if (excludePathSubstring != null && ch.filePath().contains(excludePathSubstring)) {
                    continue;
                }
                double sim = ContextRetriever.similarity(queryVector, ch.vector());
                if (sim < threshold) {
                    continue;
                }
                Double prev = bestScore.get(ch.filePath());
                if (prev == null || sim > prev) {
                    bestScore.put(ch.filePath(), sim);
                    bestPerFile.put(ch.filePath(), ch);
                }
            }

            List<PromptContextChunk> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (SqliteStorage.ChatChunk ch : bestPerFile.values()) {
                String passage = SqliteStorage.loadPersonalChatChunkContent(ch.filePath(), ch.chunkIndex());
                if (passage == null || passage.isBlank() || !seen.add(passage.strip())) {
                    continue;
                }
                String label = SqliteStorage.loadPersonalChatFileName(ch.filePath());
                found.add(new PromptContextChunk(
                        "chat_memory",
                        (label == null ? ch.filePath() : label) + " (passage " + (ch.chunkIndex() + 1) + ")",
                        passage,
                        bestScore.get(ch.filePath()),
                        ContextRetriever.estimateTokens(passage),
                        true));
            }

            found.sort(Comparator.comparingDouble(PromptContextChunk::relevanceScore)
                    .reversed());
            return found.size() > limit ? found.subList(0, limit) : found;

        } catch (Exception e) {
            LOGGER.debug("Note retrieval unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
