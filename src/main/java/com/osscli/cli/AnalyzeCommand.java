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
        hidden = true,
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

        // Local-only on purpose, and now that the engine is a prefix the refusal can say which
        // prefix it wants. A cloud engine is refused rather than ignored: scoring a whole backlog
        // in a loop against a metered API is a bill nobody asked for, and silently answering
        // locally under `oss claude analyze` would hide that decision instead of stating it.
        for (com.osscli.llm.Ai.Engine e : com.osscli.llm.Ai.engines()) {
            if (e.isExternal()) {
                LOGGER.error("analyze does not use {} — it scores the whole backlog in a loop,", e.label());
                LOGGER.error("  which against a metered API would cost real money without asking first.");
                LOGGER.error("");
                LOGGER.error("  oss llm analyze     the same scoring, locally");
                LOGGER.error("  oss critical        ranks the same backlog with no model at all");
                return 2;
            }
        }
        if (!com.osscli.llm.Ai.engines().contains(com.osscli.llm.Ai.Engine.OLLAMA)) {
            LOGGER.error("analyze writes a severity judgement, so it needs an engine.");
            LOGGER.error("");
            LOGGER.error("  oss llm analyze     local Ollama");
            LOGGER.error("  oss critical        ranks the same backlog with no model at all");
            return 2;
        }

        OllamaClient client = new OllamaClient(modelName);
        if (!client.isModelAvailable()) {
            // Ollama-only by design: this scores a whole backlog in a loop, and doing that against a
            // paid API would run up a bill nobody asked for. But refusing without naming the offline
            // alternative reads as "you cannot do this", when in fact `critical` ranks the same
            // backlog by community signal with no model at all.
            LOGGER.error("analyze needs a local model, and '{}' is not available at {}.", modelName, client.endpoint());
            LOGGER.error("  ollama serve, then: ollama pull {}", modelName);
            LOGGER.error("");
            LOGGER.error("  It is local-only on purpose — this scores the whole backlog in a loop,");
            LOGGER.error("  which against a metered API would cost real money without asking first.");
            LOGGER.error("  oss critical ranks the same backlog offline, with no model at all.");
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
                    """, repository, issue.title(), issue.body() == null ? "(no description)" : issue.body());

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
