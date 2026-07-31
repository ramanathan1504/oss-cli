package com.osscli.cli;

import com.osscli.llm.OllamaClient;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.RepoIssue;
import com.osscli.storage.SqliteStorage;
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

@Command(name = "search", description = "Search the local issue backlog semantically using vector embeddings")
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
            LOGGER.error("No vector embeddings found in the database. Please run 'duplicates' first to generate them.");
            return 1;
        }

        LOGGER.info("Generating semantic vector for query: \"{}\" (Model: {})...", query, modelName);
        OllamaClient client = new OllamaClient(modelName);
        double[] queryVector;

        try {
            queryVector = client.generateEmbedding(query);
        } catch (IOException | InterruptedException e) {
            LOGGER.error("  ↳ [Error] Failed to generate embedding: {}", e.getMessage());
            return 1;
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
}
