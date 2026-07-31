package com.osscli.cli;

import com.osscli.model.AiAnalysisResult;
import com.osscli.model.Issue;
import com.osscli.storage.SqliteStorage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "hidden-critical", description = "Find critical issues that maintainers may have underestimated")
public class HiddenCriticalCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(HiddenCriticalCommand.class);

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
        // Load issues and AI analysis results specifically for this repository context
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        List<AiAnalysisResult> aiResults = SqliteStorage.loadAiAnalysis(repository);

        if (issues.isEmpty() || aiResults.isEmpty()) {
            LOGGER.error(
                    "Required local data is missing for '{}'. Please run 'sync' followed by 'analyze' first.",
                    repository);
            return 1;
        }

        // Map AI results by issue number for fast lookup
        Map<Long, AiAnalysisResult> aiMap =
                aiResults.stream().collect(Collectors.toMap(AiAnalysisResult::issueNumber, result -> result));

        LOGGER.info("Hidden Critical Issues Report for '{}'", repository);
        LOGGER.info("====================================================");

        int count = 0;

        for (Issue issue : issues) {
            AiAnalysisResult aiResult = aiMap.get(issue.number());
            if (aiResult == null) {
                continue;
            }

            boolean isCriticalAi = "Critical".equalsIgnoreCase(aiResult.severity());
            boolean isHighAi = "High".equalsIgnoreCase(aiResult.severity()) || isCriticalAi;

            boolean hasSecurityLabel = issue.hasLabel("security") || issue.hasLabel("security-label");
            boolean hasBugLabel = issue.hasLabel("bug") || issue.hasLabel("bug-label");

            boolean isHiddenCritical = false;
            String detectionReason = "";

            // Rule 1: Label != Security AND AI Severity == Critical
            if (!hasSecurityLabel && isCriticalAi) {
                isHiddenCritical = true;
                detectionReason = "Predicted as Critical by AI but lacks security labeling.";
            }
            // Rule 2: Many comments (> 15) + Bug label + High AI severity
            else if (issue.comments() > 15 && hasBugLabel && isHighAi) {
                isHiddenCritical = true;
                detectionReason = String.format(
                        "Has %d comments, bug label, and predicted severity is %s.",
                        issue.comments(), aiResult.severity());
            }

            if (isHiddenCritical) {
                count++;

                String labelsStr = issue.labels() == null || issue.labels().isEmpty()
                        ? "[]"
                        : issue.labels().stream().map(label -> label.name()).collect(Collectors.joining(", "));

                LOGGER.info("Issue #{}", issue.number());
                LOGGER.info("  Title: {}", issue.title());
                LOGGER.info("  Current Labels: {}", labelsStr);
                LOGGER.info(
                        "  AI Severity: {} (Confidence: {})",
                        aiResult.severity(),
                        String.format("%.2f", aiResult.confidence()));
                LOGGER.info("  AI Reason: {}", aiResult.reason());
                LOGGER.info("  Detection Rule: {}", detectionReason);
            }
        }

        if (count == 0) {
            LOGGER.info("No hidden critical issues detected in this database snapshot for '{}'.", repository);
        } else {
            LOGGER.info("Detection completed. Found {} potential hidden critical issues for '{}'.", count, repository);
        }

        return 0;
    }
}
