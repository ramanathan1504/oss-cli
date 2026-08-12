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

import com.osscli.llm.OllamaClient;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.RepoIssue;
import com.osscli.storage.SqliteStorage;
import com.osscli.ui.NextSteps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "search",
        mixinStandardHelpOptions = true,
        description = "Search the local issue backlog semantically using vector embeddings")
public class SearchCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(SearchCommand.class);

    @Parameters(index = "0", description = "The plain text search query (wrap in quotes if it contains spaces)")
    private String query;

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-g", "--global"},
            description = "Perform a global search across all repositories in the database")
    private boolean global;

    @Option(
            names = {"-m", "--model"},
            description = "Ollama embedding model to use")
    private String modelName;

    @Option(
            names = {"-n", "--limit"},
            description = "Number of top search results to return",
            defaultValue = "5")
    private int limit;

    @Override
    public Integer call() throws Exception {
        // Resolve dynamic embedding model
        if (modelName == null) {
            modelName = SqliteStorage.loadConfig("ollama.model.embedding");
            if (modelName == null) {
                modelName = "all-minilm";
            }
        }

        Map<String, Issue> issueMap = new HashMap<>();
        List<IssueEmbedding> embeddings;

        if (global) {
            LOGGER.info("Loading global issues and pull requests from SQLite...");
            List<RepoIssue> allIssues = SqliteStorage.loadAllIssues();
            List<RepoIssue> allPrs = SqliteStorage.loadAllPullRequests();
            embeddings = SqliteStorage.loadAllEmbeddings();

            allIssues.forEach(
                    ri -> issueMap.put(ri.repository() + "_" + ri.issue().number(), ri.issue()));
            allPrs.forEach(ri -> issueMap.put(ri.repository() + "_" + ri.issue().number(), ri.issue()));
        } else {
            if (repository == null) {
                repository = SqliteStorage.loadConfig("default.repository");
                if (repository == null || repository.trim().isEmpty()) {
                    LOGGER.error("No target repository specified. Use '-r owner/name' or run 'setup'.");
                    return 1;
                }
            }
            List<Issue> issues = SqliteStorage.loadIssues(repository);
            List<Issue> prs = SqliteStorage.loadPullRequests(repository);
            embeddings = SqliteStorage.loadEmbeddings(repository);

            issues.forEach(i -> issueMap.put(repository + "_" + i.number(), i));
            prs.forEach(p -> issueMap.put(repository + "_" + p.number(), p));
        }

        if (embeddings.isEmpty()) {
            // No embeddings means no model has run -- but the ISSUES are right here, and refusing
            // to search data you already have is the wrong answer to "you have not installed
            // Ollama". Falling back keeps finding working; a model, when present, still wins on
            // meaning and this becomes the floor rather than the ceiling.
            return searchWithoutAModel(issueMap);
        }

        LOGGER.info("Generating semantic vector for query: \"{}\" (Model: {})...", query, modelName);
        OllamaClient client = new OllamaClient(modelName);
        double[] queryVector;

        try {
            queryVector = client.generateEmbedding(query);
        } catch (IOException | InterruptedException | RuntimeException e) {
            // Stored vectors are useless without one for the QUERY, so a stopped model server made
            // search fail outright even though every issue was sitting in SQLite. That is the
            // common case -- embeddings from a previous run, no model running now -- and failing
            // there taught people that search needs a server. It does not; it only searches better
            // with one.
            LOGGER.info("Model server unreachable ({}). Falling back to text search.", e.getMessage());
            return searchWithoutAModel(issueMap);
        }

        LOGGER.info("Scanning vectors and calculating cosine similarity...");
        List<SearchResult> results = new ArrayList<>();

        for (IssueEmbedding emb : embeddings) {
            String compositeKey = emb.repository() + "_" + emb.issueNumber();
            Issue matchedIssue = issueMap.get(compositeKey);
            if (matchedIssue != null) {
                double similarity = cosineSimilarity(queryVector, emb.vector());
                results.add(new SearchResult(emb.repository(), matchedIssue, similarity));
            }
        }

        results.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));

        LOGGER.info("Top {} Global Semantic Search Results:", Math.min(limit, results.size()));
        LOGGER.info("==========================================================================");

        for (int i = 0; i < Math.min(limit, results.size()); i++) {
            SearchResult res = results.get(i);
            String typeBadge = res.issue().isPullRequest() ? "[PR]" : "[Issue]";
            LOGGER.info(
                    "{}. {} {}#{}  Similarity: {}",
                    i + 1,
                    typeBadge,
                    res.repoName(),
                    res.issue().number(),
                    String.format("%.2f", res.similarity()));
            LOGGER.info("   Title: {}", res.issue().title());
        }

        // The moment a search finishes is the moment "which of these is worth my
        // time" becomes the real question. Answering it should not require going
        // back to --help to rediscover that `pick` exists.
        NextSteps.suggest(NextSteps.After.SEARCH, null);
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

    private static record SearchResult(String repoName, Issue issue, double similarity) {}

    /**
     * Rank by term overlap, using what is already in SQLite.
     *
     * <p>TF-IDF, in memory, no server and no network. It answers a different question from a vector
     * search -- which words two texts share, weighted by how rare those words are -- and that is
     * enough to surface related work rather than only exact matches: "database manager" reaches
     * AbstractDatabaseManager because identifiers are indexed split as well as whole.
     */
    private Integer searchWithoutAModel(Map<String, Issue> issueMap) {
        if (issueMap.isEmpty()) {
            LOGGER.error("Nothing indexed yet. Run 'sync' first.");
            return 1;
        }
        LOGGER.info("No embeddings found — searching {} item(s) by text instead.", issueMap.size());
        LOGGER.info("(A local model would add search by meaning; this finds by shared terms.)");

        com.osscli.retrieval.TextIndex index = new com.osscli.retrieval.TextIndex();
        issueMap.forEach((key, issue) -> index.add(key, issue.title(), issue.body()));
        index.build();

        List<com.osscli.retrieval.TextIndex.Hit> hits = index.search(query, limit);
        if (hits.isEmpty()) {
            LOGGER.info("Nothing shares a meaningful term with that query.");
            return 0;
        }
        int rank = 1;
        for (com.osscli.retrieval.TextIndex.Hit hit : hits) {
            Issue issue = issueMap.get(hit.id());
            LOGGER.info(
                    "{}. [{}] {}  (score {})",
                    rank++,
                    hit.id(),
                    issue == null ? hit.title() : issue.title(),
                    String.format("%.2f", hit.score()));
        }
        return 0;
    }
}
