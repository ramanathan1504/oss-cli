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
import com.osscli.storage.SqliteStorage;
import com.osscli.ui.Out;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "prs",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "Analyze cached open pull requests for stale status, reviews, and critical fixes")
public class PrsCommand implements Callable<Integer> {
    private static final Logger LOGGER = LogManager.getLogger(PrsCommand.class);

    @CommandLine.Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    private static final Pattern ISSUE_REF_PATTERN = Pattern.compile("#(\\d+)");

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
        List<Issue> prs = SqliteStorage.loadPullRequests(repository);
        // Seconds of silence on a real store before this, which reads as a hang rather than
        // as work. Live also carries the elapsed time, so a long wait can be judged.
        List<Issue> issues;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("reading the pull requests this machine knows")) {
            issues = SqliteStorage.loadIssues(repository);
            live.done(issues.size() + " read");
        }

        if (prs.isEmpty()) {
            LOGGER.error("No local pull request data found. Please run 'sync' first.");
            return 1;
        }

        // 1. Identify all Critical Issues locally to check for critical fixes
        SeverityAnalyzer severityAnalyzer = new SeverityAnalyzer();
        Set<Integer> criticalIssueNumbers = new HashSet<>();

        if (issues != null) {
            issues.stream()
                    .map(severityAnalyzer::analyze)
                    .filter(analysis -> analysis.severity() == Severity.CRITICAL)
                    .forEach(analysis ->
                            criticalIssueNumbers.add((int) analysis.issue().number()));
        }

        Out.title("pull requests");
        LOGGER.info("================================\n");

        List<Issue> stalePrs = new ArrayList<>();
        List<Issue> reviewsNeeded = new ArrayList<>();
        List<String> criticalFixes = new ArrayList<>();

        Instant now = Instant.now();

        for (Issue pr : prs) {
            Instant createdAt = Instant.parse(pr.created_at());
            Instant updatedAt = Instant.parse(pr.updated_at());

            long daysOpen = ChronoUnit.DAYS.between(createdAt, now);
            long daysSinceUpdate = ChronoUnit.DAYS.between(updatedAt, now);

            // A. Check for Stale status (> 30 days of inactivity)
            if (daysSinceUpdate > 30) {
                stalePrs.add(pr);
            }

            // B. Check if Review is Needed
            // (Using comments == 0 as a compile-safe proxy since reviews are not in the model)
            if (pr.comments() == 0) {
                reviewsNeeded.add(pr);
            }

            // C. Check for Critical Fix references
            Set<Integer> linkedIssueNumbers = extractLinkedIssues(pr.title() + " " + pr.body());
            for (Integer num : linkedIssueNumbers) {
                if (criticalIssueNumbers.contains(num)) {
                    criticalFixes.add(String.format(
                            "PR #%d  Fixes: Issue #%d  Severity: Critical  Waiting: %d days",
                            pr.number(), num, daysOpen));
                }
            }
        }

        // Print Stale PRs
        Out.section("stale — no activity in 30 days");
        if (stalePrs.isEmpty()) {
            LOGGER.info("('none')");
        } else {
            for (Issue pr : stalePrs) {
                long daysSinceUpdate = ChronoUnit.DAYS.between(Instant.parse(pr.updated_at()), now);
                LOGGER.info("#{}  Title: {}  Last Activity: {} days ago", pr.number(), pr.title(), daysSinceUpdate);
            }
        }

        // Print Reviews Needed
        Out.section("review needed — no comments yet");
        if (reviewsNeeded.isEmpty()) {
            LOGGER.info("(none)");
        } else {
            for (Issue pr : reviewsNeeded) {
                long daysOpen = ChronoUnit.DAYS.between(Instant.parse(pr.created_at()), now);
                LOGGER.info("#{}  Title: {}  Waiting: {} days", pr.number(), pr.title(), daysOpen);
            }
        }

        // Print Critical Fixes
        LOGGER.info("CRITICAL FIXES (PRs linked to Critical Issues)");
        if (criticalFixes.isEmpty()) {
            LOGGER.info("(none)");
        } else {
            for (String line : criticalFixes) {
                LOGGER.info(line);
            }
        }

        return 0;
    }

    private Set<Integer> extractLinkedIssues(String text) {
        Set<Integer> numbers = new HashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = ISSUE_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore parsing errors
            }
        }
        return numbers;
    }
}
