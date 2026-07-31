package com.osscli.cli;

import com.osscli.analyzer.IssueAnalysis;
import com.osscli.analyzer.SeverityAnalyzer;
import com.osscli.model.AiAnalysisResult;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.JiraBridgeLink;
import com.osscli.model.Label;
import com.osscli.storage.SqliteStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "triage", description = "Perform a consolidated automated triage audit on a specific issue")
public class TriageCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(TriageCommand.class);

    @Parameters(index = "0", description = "The issue or PR number to triage")
    private long issueNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository (owner/name)")
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
        // 1. Load Datasets
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        List<Issue> prs = SqliteStorage.loadPullRequests(repository);
        List<AiAnalysisResult> aiResults = SqliteStorage.loadAiAnalysis(repository);
        List<IssueEmbedding> embeddings = SqliteStorage.loadEmbeddings(repository);
        List<JiraBridgeLink> jiraBridges = SqliteStorage.loadJiraBridges(repository);

        // Find target issue or pull request
        Issue target = null;
        for (Issue i : issues) {
            if (i.number() == issueNumber) {
                target = i;
                break;
            }
        }
        if (target == null) {
            for (Issue p : prs) {
                if (p.number() == issueNumber) {
                    target = p;
                    break;
                }
            }
        }

        if (target == null) {
            LOGGER.error(
                    "Issue #{} not found in local data for '{}'. Please run 'sync' first.", issueNumber, repository);
            return 1;
        }

        LOGGER.info("==================================================");
        LOGGER.info("Triage Report: {} #{} ({})", target.isPullRequest() ? "PR" : "Issue", issueNumber, repository);
        LOGGER.info("==================================================");

        // A. Metadata Output
        String labelsStr = target.labels() == null || target.labels().isEmpty()
                ? "(none)"
                : target.labels().stream().map(Label::name).collect(Collectors.joining(", "));

        String authorName = target.user() != null ? target.user().login() : "unknown";
        String memberBadge = target.isOrgMember() ? " [Member]" : "";

        LOGGER.info("[METADATA]%n");
        LOGGER.info("  Title:      {}", target.title());
        LOGGER.info("  Author:     {}{}", authorName, memberBadge);
        LOGGER.info("  Labels:     {}", labelsStr);
        LOGGER.info("  Comments:   {}", target.comments());

        // B. Severity Assessments
        SeverityAnalyzer severityAnalyzer = new SeverityAnalyzer();
        IssueAnalysis v1Analysis = severityAnalyzer.analyze(target);

        AiAnalysisResult targetAi = null;
        for (AiAnalysisResult ai : aiResults) {
            if (ai.issueNumber() == issueNumber) {
                targetAi = ai;
                break;
            }
        }

        LOGGER.info("[SEVERITY ASSESSMENT]%n");
        LOGGER.info("  • V1 Rule Score: {} ({})", v1Analysis.score(), v1Analysis.severity());
        if (targetAi != null) {
            LOGGER.info(
                    "  • AI Severity:   {} (Confidence: {})",
                    targetAi.severity(),
                    String.format("%.2f", targetAi.confidence()));
            LOGGER.info("  • AI Reason:     {}", targetAi.reason());
        } else {
            LOGGER.info("  • AI Severity:   (No AI evaluation found. Run 'analyze' first.)");
        }

        // C. Backlog Overlap & Duplicates (Semantic Similarity)
        double[] targetVector = null;
        for (IssueEmbedding emb : embeddings) {
            if (emb.issueNumber() == issueNumber) {
                targetVector = emb.vector();
                break;
            }
        }

        LOGGER.info("[BACKLOG OVERLAP]%n");
        if (targetVector == null) {
            LOGGER.info("  No vector embedding found. Run 'duplicates' first to check for overlaps.");
        } else {
            List<String> similarIssues = new ArrayList<>();
            for (IssueEmbedding emb : embeddings) {
                if (emb.issueNumber() != issueNumber) {
                    double sim = cosineSimilarity(targetVector, emb.vector());
                    if (sim >= 0.70) {
                        // Find title of similar issue
                        String title = "Unknown Title";
                        for (Issue i : issues) {
                            if (i.number() == emb.issueNumber()) {
                                title = i.title();
                                break;
                            }
                        }
                        for (Issue p : prs) {
                            if (p.number() == emb.issueNumber()) {
                                title = p.title();
                                break;
                            }
                        }
                        similarIssues.add(String.format("#%d - %s (%.2f Similarity)", emb.issueNumber(), title, sim));
                    }
                }
            }
            if (similarIssues.isEmpty()) {
                LOGGER.info("  No duplicate groups detected above the 70% threshold.");
            } else {
                LOGGER.info("  Potential duplicates/related issues detected:");
                for (String line : similarIssues) {
                    LOGGER.info("    - {}", line);
                }
            }
        }

        // D. Ecosystem / JIRA Bridges
        LOGGER.info("[ECOSYSTEM / JIRA BRIDGES]%n");
        List<JiraBridgeLink> filteredBridges =
                jiraBridges.stream().filter(b -> b.localNumber() == issueNumber).toList();

        if (filteredBridges.isEmpty()) {
            LOGGER.info("  No ecosystem connections or JIRA bridge matches found.");
        } else {
            for (JiraBridgeLink b : filteredBridges) {
                LOGGER.info(
                        "  • Connection: Matches {}#{} via JIRA Key [{}]",
                        b.externalRepo(),
                        b.externalNumber(),
                        b.jiraKey());
            }
        }

        // E. Action Log & Recommendation Logic
        LOGGER.info("[RECOMMENDED ACTION LOG]%n");
        List<String> actions = new ArrayList<>();

        // Logic check: Hidden Critical
        if (targetAi != null) {
            boolean isCriticalAi = "Critical".equalsIgnoreCase(targetAi.severity());
            boolean isHighAi = "High".equalsIgnoreCase(targetAi.severity()) || isCriticalAi;
            boolean hasSecurityLabel = target.hasLabel("security") || target.hasLabel("security-label");
            boolean hasBugLabel = target.hasLabel("bug") || target.hasLabel("bug-label");

            if (!hasSecurityLabel && isCriticalAi) {
                actions.add(
                        "⚠ ACTION: Escalate and add SECURITY label (flagged as HIDDEN CRITICAL; AI predicted Critical but lacks security tags).");
            } else if (target.comments() > 15 && hasBugLabel && isHighAi) {
                actions.add(String.format(
                        "⚠ ACTION: Address user noise (has %d comments, bug label, and high AI prediction).",
                        target.comments()));
            }
        }

        // Logic check: Stale
        long daysSinceUpdate = ChronoUnit.DAYS.between(Instant.parse(target.updated_at()), Instant.now());
        if (daysSinceUpdate > 30) {
            actions.add(
                    String.format("⚠ ACTION: Check stale status (last activity was %d days ago).", daysSinceUpdate));
        }

        // Logic check: Missing reviewer (Review needed)
        if (target.isPullRequest() && target.comments() == 0) {
            actions.add("⚠ ACTION: Request code review (PR has had zero comments or interactions).");
        }

        if (actions.isEmpty()) {
            LOGGER.info("  ✔ No immediate actions required. Backlog state is clean.");
        } else {
            for (String act : actions) {
                LOGGER.info(" {} ", act);
            }
        }
        LOGGER.info("==================================================");

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
