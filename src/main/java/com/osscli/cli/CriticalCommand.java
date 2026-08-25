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

import com.osscli.analyzer.IssueAnalysis;
import com.osscli.analyzer.Severity;
import com.osscli.analyzer.SeverityAnalyzer;
import com.osscli.model.Issue;
import com.osscli.model.Label;
import com.osscli.storage.SqliteStorage;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "critical",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "Find critical issues using local data")
public class CriticalCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(CriticalCommand.class);

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

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
        // Load issues specifically for this repository
        // Silent for seconds on a real store before this. A status line is not decoration
        // when the alternative is a person wondering whether the command is running.
        List<Issue> issues;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("scoring the backlog for severity")) {
            issues = SqliteStorage.loadIssues(repository);
            live.done(issues.size() + " issue(s) read");
        }
        if (issues.isEmpty()) {
            LOGGER.error("No local data found for '{}'. Please run the 'sync' command first.", repository);
            return 1;
        }

        SeverityAnalyzer analyzer = new SeverityAnalyzer();

        List<IssueAnalysis> analyses = issues.stream()
                .filter(issue -> !issue.isPullRequest())
                .map(analyzer::analyze)
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .toList();

        long critical =
                analyses.stream().filter(a -> a.severity() == Severity.CRITICAL).count();
        long high = analyses.stream().filter(a -> a.severity() == Severity.HIGH).count();
        long medium =
                analyses.stream().filter(a -> a.severity() == Severity.MEDIUM).count();
        long low = analyses.stream().filter(a -> a.severity() == Severity.LOW).count();

        LOGGER.info("Repository: {} (Offline Mode)", repository);
        LOGGER.info("");

        LOGGER.info("Critical: {}", critical);
        LOGGER.info("High: {}", high);
        LOGGER.info("Medium: {}", medium);
        LOGGER.info("Low: {}", low);

        LOGGER.info("");
        LOGGER.info("CRITICAL");
        analyses.stream().filter(a -> a.severity() == Severity.CRITICAL).forEach(this::printIssue);

        LOGGER.info("");
        LOGGER.info("HIGH");
        LOGGER.info("====");
        analyses.stream().filter(a -> a.severity() == Severity.HIGH).limit(10).forEach(this::printIssue);

        LOGGER.info("");
        LOGGER.info("MEDIUM");
        LOGGER.info("====");
        analyses.stream().filter(a -> a.severity() == Severity.MEDIUM).limit(10).forEach(this::printIssue);

        return 0;
    }

    private void printIssue(IssueAnalysis a) {
        String labels = a.issue().labels() == null
                ? "[]"
                : a.issue().labels().stream().map(Label::name).toList().toString();

        LOGGER.info(
                "#{} Score={} Labels={} [{}] {}",
                a.issue().number(),
                a.score(),
                labels,
                a.reason(),
                a.issue().title());
    }
}
