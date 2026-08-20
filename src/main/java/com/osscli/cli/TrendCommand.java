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

import com.osscli.analyzer.Severity;
import com.osscli.analyzer.SeverityAnalyzer;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.TrendSnapshot;
import com.osscli.storage.SqliteStorage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        name = "trend",
        mixinStandardHelpOptions = true,
        description = "Track and visualize weekly project health trends")
public class TrendCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(TrendCommand.class);

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-s", "--save"},
            description = "Compute and save a new historical trend snapshot for today")
    private boolean save;

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

        if (save) {
            saveTodaySnapshot();
        }

        // Load snapshots specifically for this repository
        List<TrendSnapshot> snapshots = SqliteStorage.loadTrendSnapshots(repository);

        if (snapshots.isEmpty()) {
            LOGGER.info("No historical trend data found for '{}'.", repository);
            System.out.printf("Run with 'trend --save -r %s' first to create your first snapshot.%n", repository);
            return 0;
        }

        LOGGER.info("Project Health Trends Dashboard for '{}'", repository);
        LOGGER.info("=====================================================");

        LOGGER.info(String.format(
                "%-12s | %-15s | %-13s | %-18s | %-18s",
                "Date", "Critical Issues", "High Priority", "Stale PRs", "Duplicate Clusters"));
        LOGGER.info("-------------+-----------------+---------------+--------------------+-------------------");

        for (TrendSnapshot s : snapshots) {
            LOGGER.info(String.format(
                    "%-12s | %-15d | %-13d | %-18d | %-18d",
                    s.date(), s.criticalIssues(), s.highPriority(), s.stalePrs(), s.duplicateClusters()));
        }

        if (snapshots.size() > 1) {
            TrendSnapshot first = snapshots.get(0);
            TrendSnapshot latest = snapshots.get(snapshots.size() - 1);

            LOGGER.info("Trend Analysis (Comparison with oldest snapshot on {} ):", first.date());

            int criticalDiff = latest.criticalIssues() - first.criticalIssues();
            int highDiff = latest.highPriority() - first.highPriority();
            int stalePrDiff = latest.stalePrs() - first.stalePrs();
            int duplicateDiff = latest.duplicateClusters() - first.duplicateClusters();

            printTrendMessage("Critical Issues", criticalDiff);
            printTrendMessage("High Priority Issues", highDiff);
            printTrendMessage("Stale PRs", stalePrDiff);
            printTrendMessage("Duplicate Clusters", duplicateDiff);
        }

        return 0;
    }

    private void printTrendMessage(String metric, int diff) {
        if (diff < 0) {
            LOGGER.info("  ✔ {} decreased by {}", metric, Math.abs(diff));
        } else if (diff > 0) {
            LOGGER.info("  ⚠ {} increased by {}", metric, diff);
        } else {
            LOGGER.info("  • {} remained unchanged", metric);
        }
    }

    private void saveTodaySnapshot() throws Exception {
        // Load data specifically for this repository
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        List<Issue> prs = SqliteStorage.loadPullRequests(repository);
        List<IssueEmbedding> embeddings = SqliteStorage.loadEmbeddings(repository);

        if (issues.isEmpty()) {
            LOGGER.error(
                    "No local issues found to save today's snapshot for '{}'. Please run 'sync' first.", repository);
            return;
        }

        SeverityAnalyzer severityAnalyzer = new SeverityAnalyzer();
        long criticalCount = issues.stream()
                .map(severityAnalyzer::analyze)
                .filter(a -> a.severity() == Severity.CRITICAL)
                .count();

        long highCount = issues.stream()
                .map(severityAnalyzer::analyze)
                .filter(a -> a.severity() == Severity.HIGH)
                .count();

        Instant now = Instant.now();
        long staleCount = prs.stream()
                .filter(pr -> {
                    try {
                        return ChronoUnit.DAYS.between(Instant.parse(pr.updated_at()), now) > 30;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .count();

        int dupCount = calculateDuplicateGroupsCount(issues, embeddings);

        String today = LocalDate.now().toString();
        TrendSnapshot snapshot =
                new TrendSnapshot(today, (int) criticalCount, (int) highCount, (int) staleCount, dupCount);

        // Save snapshot specifically for this repository
        SqliteStorage.saveTrendSnapshot(repository, snapshot);
        LOGGER.info("  ↳ Saved snapshot for today ({}) on '{}':", today, repository);
        LOGGER.info(
                "    Critical: {}, High: {}, Stale PRs: {}, Duplicates: {}",
                criticalCount,
                highCount,
                staleCount,
                dupCount);
    }

    private int calculateDuplicateGroupsCount(List<Issue> issues, List<IssueEmbedding> embeddings) {
        if (embeddings.isEmpty()) {
            return 0;
        }

        Map<Long, double[]> vectorMap = new HashMap<>();
        for (IssueEmbedding emb : embeddings) {
            vectorMap.put(emb.issueNumber(), emb.vector());
        }

        int size = issues.size();
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            adj.add(new ArrayList<>());
        }

        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                double[] vecA = vectorMap.get(issues.get(i).number());
                double[] vecB = vectorMap.get(issues.get(j).number());
                if (vecA != null && vecB != null) {
                    double sim = cosineSimilarity(vecA, vecB);
                    if (sim >= 0.70) {
                        adj.get(i).add(j);
                        adj.get(j).add(i);
                    }
                }
            }
        }

        boolean[] visited = new boolean[size];
        int duplicateGroupsCount = 0;

        for (int i = 0; i < size; i++) {
            if (!visited[i]) {
                List<Integer> component = new ArrayList<>();
                Queue<Integer> queue = new LinkedList<>();

                visited[i] = true;
                queue.add(i);

                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    component.add(current);
                    for (int neighbor : adj.get(current)) {
                        if (!visited[neighbor]) {
                            visited[neighbor] = true;
                            queue.add(neighbor);
                        }
                    }
                }

                if (component.size() > 1) {
                    duplicateGroupsCount++;
                }
            }
        }
        return duplicateGroupsCount;
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
