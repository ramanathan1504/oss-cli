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

import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.retrieval.Embeddings;
import com.osscli.storage.SqliteStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "duplicates",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "Identify potential duplicate issues using local vector embeddings")
public class DuplicatesCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(DuplicatesCommand.class);

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-t", "--threshold"},
            description = "Cosine similarity threshold (0.0 to 1.0)",
            defaultValue = "0.80")
    private double threshold;

    @Override
    public Integer call() throws Exception {

        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error(
                        "No target repository specified. Please use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        if (issues.isEmpty()) {
            LOGGER.error("No local issues found for '{}'. Please run 'sync' first.", repository);
            return 1;
        }

        List<IssueEmbedding> cachedEmbeddings = SqliteStorage.loadEmbeddings(repository);
        Map<Long, double[]> vectorMap = new HashMap<>();
        for (IssueEmbedding emb : cachedEmbeddings) {
            vectorMap.put(emb.issueNumber(), emb.vector());
        }

        LOGGER.info(
                "Starting duplicate analysis for '{}' (Model: {}, Threshold: {})...",
                repository,
                Embeddings.MODEL,
                String.format("%.2f", threshold));

        // Clustering is the one thing here that genuinely cannot be done by shared terms: near
        // duplicates are near duplicates precisely when they say the same thing in different words.
        // So an absent model is reported rather than worked around -- but whatever was indexed
        // earlier still clusters, because refusing to use vectors already on disk helps nobody.
        com.osscli.retrieval.LocalEmbedder embedder = Embeddings.ifPresent(m -> LOGGER.info("  {}", m));
        boolean cacheUpdated = false;

        for (Issue issue : issues) {
            if (!vectorMap.containsKey(issue.number())) {
                if (embedder == null) {
                    continue;
                }
                LOGGER.info("  Generating embedding vector for Issue #{}...", issue.number());
                String content = "Title: " + issue.title() + "\nBody: " + (issue.body() == null ? "" : issue.body());
                try {
                    vectorMap.put(issue.number(), embedder.embed(content));
                    cacheUpdated = true;
                } catch (IOException e) {
                    // One issue that will not embed must not abandon the rest of the repository.
                    LOGGER.warn("  ↳ Skipped #{}: {}", issue.number(), e.getMessage());
                }
            }
        }

        if (vectorMap.isEmpty()) {
            LOGGER.error("No vectors for '{}', so there is nothing to cluster.", repository);
            LOGGER.error("  {}", Embeddings.ABSENT_HINT);
            return 1;
        }
        if (embedder == null && vectorMap.size() < issues.size()) {
            LOGGER.warn(
                    "No local model — clustering the {} of {} issue(s) already indexed.",
                    vectorMap.size(),
                    issues.size());
            LOGGER.warn("  {}", Embeddings.ABSENT_HINT);
        }

        if (cacheUpdated) {
            List<IssueEmbedding> newCacheList = new ArrayList<>();
            vectorMap.forEach((k, v) -> newCacheList.add(new IssueEmbedding(repository, k, v)));
            SqliteStorage.saveEmbeddings(repository, newCacheList, Embeddings.MODEL);
            LOGGER.info("  ↳ Local embeddings database updated.");
        }

        int size = issues.size();
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            adj.add(new ArrayList<>());
        }

        LOGGER.info("Analyzing similarities and clustering issues...");
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                double[] vecA = vectorMap.get(issues.get(i).number());
                double[] vecB = vectorMap.get(issues.get(j).number());
                if (vecA != null && vecB != null) {
                    double sim = cosineSimilarity(vecA, vecB);
                    if (sim >= threshold) {
                        adj.get(i).add(j);
                        adj.get(j).add(i);
                    }
                }
            }
        }

        boolean[] visited = new boolean[size];
        List<List<Issue>> clusters = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            if (!visited[i]) {
                List<Issue> cluster = new ArrayList<>();
                Queue<Integer> queue = new LinkedList<>();

                visited[i] = true;
                queue.add(i);

                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    cluster.add(issues.get(current));

                    for (int neighbor : adj.get(current)) {
                        if (!visited[neighbor]) {
                            visited[neighbor] = true;
                            queue.add(neighbor);
                        }
                    }
                }

                if (cluster.size() > 1) {
                    clusters.add(cluster);
                }
            }
        }

        LOGGER.info("\nDuplicate Issue Clusters Report");
        LOGGER.info("===============================\n");

        if (clusters.isEmpty()) {
            LOGGER.error("No duplicate groups detected above the threshold.");
        } else {
            for (int k = 0; k < clusters.size(); k++) {
                List<Issue> cluster = clusters.get(k);
                LOGGER.info("Cluster {} (Size: {})", k + 1, cluster.size());

                for (Issue issue : cluster) {
                    LOGGER.info("  #{}: {}", issue.number(), issue.title());
                }

                double[] vecA = vectorMap.get(cluster.get(0).number());
                double[] vecB = vectorMap.get(cluster.get(1).number());
                if (vecA != null && vecB != null) {
                    LOGGER.info(
                            "  ↳ Representative Pair Similarity: {}",
                            String.format("%.2f", cosineSimilarity(vecA, vecB)));
                }
                LOGGER.info("");
            }
        }

        return 0;
    }

    private double cosineSimilarity(double[] vecA, double[] vecB) {
        if (vecA.length != vecB.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
