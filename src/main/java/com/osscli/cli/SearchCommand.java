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
import com.osscli.model.RepoIssue;
import com.osscli.retrieval.Embeddings;
import com.osscli.storage.SqliteStorage;
import com.osscli.ui.NextSteps;
import com.osscli.ui.Out;
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
    String query;

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-g", "--global"},
            description = "Perform a global search across all repositories in the database")
    private boolean global;

    @Option(
            names = {"-n", "--limit"},
            description = "Number of top search results to return",
            defaultValue = "5")
    private int limit;

    @Override
    public Integer call() throws Exception {
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
            // A status line, because this is not instant and used to look like nothing happening:
            // loading every embedding for a repository is tens of thousands of 384-float rows, and
            // on a real store it is seconds of silence before the first output.
            try (com.osscli.ui.Live live = com.osscli.ui.Live.start("reading what this machine knows")) {
                List<Issue> issues = SqliteStorage.loadIssues(repository);
                live.step(issues.size() + " issue(s)");
                List<Issue> prs = SqliteStorage.loadPullRequests(repository);
                live.step(issues.size() + " issue(s), " + prs.size() + " pull request(s)");
                embeddings = SqliteStorage.loadEmbeddings(repository);
                live.step("comparing against " + embeddings.size() + " stored vector(s)");

                issues.forEach(i -> issueMap.put(repository + "_" + i.number(), i));
                prs.forEach(p -> issueMap.put(repository + "_" + p.number(), p));
                live.done(
                        embeddings.isEmpty()
                                ? "nothing indexed yet — searching by text"
                                : "searching " + embeddings.size() + " by meaning");
            }
        }

        if (embeddings.isEmpty()) {
            // No embeddings means nothing has been indexed yet -- but the ISSUES are right here, and
            // refusing to search data you already have is the wrong answer to "you have not fetched
            // the model". Falling back keeps finding working; the model, when present, still wins on
            // meaning and this becomes the floor rather than the ceiling.
            return searchWithoutAModel(issueMap);
        }

        // A number is a number, not a sentence.
        //
        // Typing "4226" means "show me 4226". Embedding it asks the model what a bare integer is
        // ABOUT, which it cannot know -- the answer came back as five unrelated issues at 0.13 to
        // 0.26 similarity, none of them 4226, while 4226 itself sat in the same store with a title
        // on it. That is worse than no result: five confident wrong ones, reported as a success.
        java.util.Optional<Issue> exact = byNumber(issueMap);
        if (exact.isPresent()) {
            Issue hit = exact.get();
            LOGGER.info("#{} — {}", hit.number(), hit.title());
            LOGGER.info("");
            LOGGER.info("  oss followup {}   what you decided, and what has happened since", hit.number());
            LOGGER.info("  oss triage {}     everything known about it at once", hit.number());
            LOGGER.info("");
            LOGGER.info("  By meaning instead:  oss search \"{}\"", hit.title());
            return 0;
        }

        LOGGER.info("Generating semantic vector for query: \"{}\" (Model: {})...", query, Embeddings.MODEL);
        double[] queryVector;

        try {
            queryVector = Embeddings.embed(query, m -> LOGGER.info("  {}", m));
        } catch (IOException | RuntimeException e) {
            LOGGER.info("Could not embed the query ({}). Falling back to text search.", e.getMessage());
            return searchWithoutAModel(issueMap);
        }

        if (queryVector == null) {
            // Stored vectors are useless without one for the QUERY. This is the common case after
            // the model is removed -- vectors from a previous run, no model now -- and failing here
            // would teach people that search needs one. It does not; it only searches better with it.
            LOGGER.info("No local model — searching {} item(s) by shared terms instead.", issueMap.size());
            LOGGER.info("  {}", Embeddings.ABSENT_HINT);
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

        // What was actually searched. "Global" was hardcoded, so a search scoped to one repository
        // -- which is the default, and what -r asks for -- was labelled as covering all of them.
        // The results were correctly scoped; only the sentence above them was wrong, which is the
        // worse way round: nothing in the output contradicts it.
        Out.title((global ? "all repositories" : repository) + "  " + Out.faint("· " + query));

        for (int i = 0; i < Math.min(limit, results.size()); i++) {
            SearchResult res = results.get(i);
            String kind = res.issue().isPullRequest() ? "PR" : "issue";
            // Number first and coloured, because it is the thing you type next. The score is dim:
            // it decides the order and is almost never the reason anybody reads the line.
            Out.item(String.format(
                    "%s  %-5s %-26s %s",
                    Out.cmd(String.format("#%-6d", res.issue().number())),
                    Out.faint(kind),
                    res.repoName(),
                    Out.faint(String.format("%.2f", res.similarity()))));
            Out.item("  " + res.issue().title());
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
    /**
     * The issue this query names, when the query is only a number.
     *
     * <p>Deliberately exact and deliberately narrow: an all-digit query, and only when that number
     * is one this store actually has. A query that merely contains a number -- "4226 startup delay"
     * -- is still a search, because that one really is a sentence.
     */
    java.util.Optional<Issue> byNumber(Map<String, Issue> issueMap) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
            return java.util.Optional.empty();
        }
        long wanted;
        try {
            wanted = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return java.util.Optional.empty(); // longer than a long is not an issue number
        }
        for (Issue candidate : issueMap.values()) {
            if (candidate.number() == wanted) {
                return java.util.Optional.of(candidate);
            }
        }
        return java.util.Optional.empty();
    }

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
