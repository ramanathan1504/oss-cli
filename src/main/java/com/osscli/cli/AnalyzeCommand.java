package com.osscli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.llm.OllamaClient;
import com.osscli.model.AiAnalysisResult;
import com.osscli.model.Issue;
import com.osscli.storage.SqliteStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "analyze",
        mixinStandardHelpOptions = true,
        description = "Perform batch AI Severity Analysis on open issues via local Ollama")
public class AnalyzeCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(AnalyzeCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-m", "--model"},
            description = "Ollama model name to use")
    private String modelName;

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
        // 1. Resolve model dynamically from SQLite config
        if (modelName == null) {
            modelName = SqliteStorage.loadConfig("ollama.model.triage");
            if (modelName == null) {
                modelName = "qwen2.5:0.5b"; // Safe ultimate fallback
            }
        }

        List<Issue> issues = SqliteStorage.loadIssues(repository);
        if (issues.isEmpty()) {
            LOGGER.error("No local issues found for '{}'. Please run 'sync' first.", repository);
            return 1;
        }

        // 2. Load existing AI analyses to PREVENT redundant re-analysis
        List<AiAnalysisResult> existingResults = SqliteStorage.loadAiAnalysis(repository);
        Set<Long> alreadyAnalyzed =
                existingResults.stream().map(AiAnalysisResult::issueNumber).collect(Collectors.toSet());

        LOGGER.info("Starting AI Severity Analysis for '{}' using model '{}'...", repository, modelName);

        OllamaClient client = new OllamaClient(modelName);
        if (!client.isModelAvailable()) {
            LOGGER.error("Ollama model '{}' is not available. Please start Ollama or pull the model.", modelName);
            return 1;
        }

        List<AiAnalysisResult> newResults = new ArrayList<>();

        for (Issue issue : issues) {
            // THE SHIELD: If we already analyzed this issue, skip it instantly!
            if (alreadyAnalyzed.contains(issue.number())) {
                continue;
            }

            LOGGER.info("Analyzing Issue #{}: {}", issue.number(), issue.title());

            String prompt = String.format(
                    """
                    You are an expert maintainer for the '%s' open-source repository.
                    Classify the severity of the following GitHub issue.

                    Issue Title: %s
                    Issue Body: %s

                    Classify: Critical, High, Medium, Low
                    Determine:
                    - Severity
                    - Confidence (0.0 to 1.0)
                    - Impact

                    You MUST respond ONLY with a valid JSON object matching this exact schema:
                    {
                      "severity": "Critical",
                      "confidence": 0.91,
                      "reason": "Potential deadlock affecting production systems."
                    }
                    """,
                    issue.title(), issue.body());

            try {
                String rawJson = client.generateJson(prompt);
                AiAnalysisResult rawResult = MAPPER.readValue(rawJson, AiAnalysisResult.class);

                AiAnalysisResult finalResult = new AiAnalysisResult(
                        issue.number(), rawResult.severity(), rawResult.confidence(), rawResult.reason());

                newResults.add(finalResult);
                LOGGER.info("  ↳ Predicted: {} (Confidence: {})", finalResult.severity(), finalResult.confidence());

            } catch (IOException | InterruptedException e) {
                LOGGER.warn("  ↳ [Warning] Failed to analyze #{}: {}", issue.number(), e.getMessage());
            }
        }

        // Only save to DB if we actually generated new analyses
        if (!newResults.isEmpty()) {
            SqliteStorage.saveAiAnalysis(repository, newResults);
            LOGGER.info(
                    "AI Analysis completed. {} new results saved to SQLite for '{}'.", newResults.size(), repository);
        } else {
            LOGGER.info("AI Analysis completed. All issues were already analyzed. Zero redundant calls made.");
        }

        return 0;
    }
}
