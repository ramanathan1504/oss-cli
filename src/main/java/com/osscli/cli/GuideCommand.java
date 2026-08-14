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

import com.osscli.llm.ClaudeClient;
import com.osscli.llm.GeminiClient;
import com.osscli.llm.ModelFit;
import com.osscli.llm.OllamaClient;
import com.osscli.llm.OpenAiClient;
import com.osscli.model.Issue;
import com.osscli.storage.SqliteStorage;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "guide",
        mixinStandardHelpOptions = true,
        description = "Generate a personalized resolution blueprint using local memory and Omni-Cloud escalation")
public class GuideCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(GuideCommand.class);

    @Parameters(index = "0", description = "The issue or PR number to analyze")
    private long issueNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository in 'owner/repo' format")
    private String repository;

    @Option(
            names = {"-m", "--model"},
            description = "Local Ollama model to use")
    private String modelName;

    @Option(
            names = {"--gemini"},
            description = "Escalate to Google Gemini")
    private boolean useGemini;

    @Option(
            names = {"--openai"},
            description = "Escalate to OpenAI GPT-4o")
    private boolean useOpenAi;

    @Option(
            names = {"--claude"},
            description = "Escalate to Anthropic Claude")
    private boolean useClaude;

    @Override
    public Integer call() throws Exception {
        // 1. Resolve configurations
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error("No target repository specified. Run 'setup' to set a default.");
                return 1;
            }
        }
        if (modelName == null) {
            modelName = SqliteStorage.loadConfig("ollama.model.guidance");
            if (modelName == null) {
                modelName = com.osscli.Defaults.GUIDANCE_MODEL;
            }
        }

        // 2. Load the target issue details
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        List<Issue> prs = SqliteStorage.loadPullRequests(repository);
        Issue target = issues.stream()
                .filter(i -> i.number() == issueNumber)
                .findFirst()
                .orElse(null);
        if (target == null) {
            target = prs.stream()
                    .filter(p -> p.number() == issueNumber)
                    .findFirst()
                    .orElse(null);
        }

        if (target == null) {
            LOGGER.error("Issue #{} not found in local data for '{}'.", issueNumber, repository);
            return 1;
        }

        // 3. The user's own past work, budgeted to fit the prompt.
        //
        // This used to append the whole text of every note scoring above 0.35, with no cap. On a
        // corpus of 592 notes totalling 34 MB it matched 332 of them and built a prompt of roughly
        // 19 MB -- for a model configured with a 6,000-token window, about eight hundred times what
        // it could take. The request timed out, which looks like a slow machine rather than a prompt
        // that was never going to work. ContextRetriever already ranks and fills a token budget for
        // `prompt`; this is that same code rather than a third copy of the idea.
        String retrieved = com.osscli.retrieval.MemoryContext.forIssue(issueNumber, repository);
        String memorySection = retrieved.isEmpty()
                ? "No specific personal past experience found. Provide expert generic resolution for " + repository
                        + "."
                : retrieved;

        // --- TIER 1: Local generation, when there is a local model ---
        //
        // This used to return here the moment Ollama was unreachable -- before it had even read
        // --gemini, a flag that exists precisely to bypass the local model. So the one command-line
        // option documented as "route immediately to the cloud" could not be used by anybody who had
        // taken the documentation at its word and not installed Ollama.
        boolean forceCloud = useGemini || useOpenAi || useClaude;

        OllamaClient localOllama = new OllamaClient(modelName);
        if (!localOllama.isModelAvailable()) {
            if (!forceCloud) {
                LOGGER.error("guide needs a model that writes, and none is connected.");
                LOGGER.error("");
                LOGGER.error("  Local:  '{}' is not available at {}", modelName, localOllama.endpoint());
                LOGGER.error("          ollama serve, then: ollama pull {}", modelName);
                LOGGER.error("  Cloud:  pass --gemini, --openai or --claude with the matching key set");
                LOGGER.error("");
                LOGGER.error("  Either one is enough.");
                LOGGER.error("  oss prompt {} assembles the same context with no model at all.", issueNumber);
                return 1;
            }
            localOllama = null;
        }

        // Installed is not the same as runnable. Ollama loads a model too large for the machine
        // rather than refusing it, the operating system swaps, and everything stops responding for
        // minutes -- which cannot even be cancelled, let alone read. Checked here, before anything
        // is loaded.
        if (localOllama != null) {
            ModelFit.Verdict fit = ModelFit.check(localOllama, modelName);
            if (fit.shouldRefuse()) {
                LOGGER.warn("  ⚠ Not running '{}' locally — it does not fit in memory right now.", modelName);
                for (String line : fit.explain()) {
                    LOGGER.warn("    {}", line);
                }
                if (!forceCloud) {
                    LOGGER.warn("    Pass --gemini, --openai or --claude to answer without a local model.");
                    return 1;
                }
                LOGGER.warn("    Continuing with the cloud model alone; the local passes are skipped.");
                localOllama = null;
            }
        }

        String localOutput;
        try {
            localOutput = draft(localOllama, modelName, repository, memorySection, target);
        } catch (java.io.IOException e) {
            // OllamaClient has already said what went wrong and what to try. Letting this out of
            // call() would add picocli's stack trace underneath that explanation, which is how a
            // clear message gets buried -- and a stack trace is not a message.
            LOGGER.error("  No blueprint was produced.");
            return 1;
        }
        return escalate(forceCloud, localOllama, localOutput, memorySection, issueNumber);
    }

    /** The local first pass, or a placeholder when there is no local model to make one. */
    private String draft(
            OllamaClient localOllama,
            String modelName,
            String repository,
            String memorySection,
            com.osscli.model.Issue target)
            throws Exception {
        if (localOllama == null) {
            // No draft to refine, so the cloud is asked for the blueprint itself rather than an
            // improvement on something that does not exist.
            LOGGER.info("No local model — going straight to the cloud for the blueprint.");
            return "(no local draft: no local model was available)";
        }
        return localDraft(localOllama, modelName, repository, memorySection, target);
    }

    /** The local first pass, when a local model is present. */
    private String localDraft(
            OllamaClient localOllama,
            String modelName,
            String repository,
            String memorySection,
            com.osscli.model.Issue target)
            throws Exception {
        LOGGER.info("Synthesizing initial blueprint using local model '{}'...", modelName);
        String localPrompt = String.format("""
                You are an expert maintainer for the '%s' repository.
                Help the developer write a step-by-step code resolution plan for this new issue.

                --- REFERENCE MEMORY ---
                %s

                --- NEW ISSUE ---
                Title: %s
                Body: %s

                Your output MUST be a structured markdown guide containing:
                1. ANALYSIS: A concise technical explanation of the root cause.
                2. HISTORICAL MATCH: How this relates to the past work provided in the reference memory.
                3. STEP-BY-STEP PLAN: A concrete, file-by-file coding blueprint.
                """, repository, memorySection.trim(), target.title(), target.body());

        String localOutput = localOllama.generateText(localPrompt);
        LOGGER.info(
                "\n================ LOCAL AI DRAFT ================\n{}\n================================================",
                localOutput);
        return localOutput;
    }

    /** Tier 2: refine with a cloud model, and align the result when a local model can do it. */
    private Integer escalate(
            boolean forceCloud, OllamaClient localOllama, String localOutput, String memorySection, long issueNumber)
            throws Exception {

        Scanner scanner = new Scanner(System.in);
        String tweak = "";

        if (!forceCloud) {
            LOGGER.info("\nWould you like to refine this with a Cloud Expert? (Type your tweak or 'n' to exit):");
            tweak = scanner.nextLine().trim();
        }

        if (forceCloud || (!tweak.isEmpty() && !tweak.equalsIgnoreCase("n"))) {
            String cloudOutput;
            String provider;

            String cloudPrompt = String.format("""
                    You are an expert maintainer. Refine this resolution for issue #%d.
                    Memory Context: %s
                    Local Draft: %s
                    User Instructions: %s
                    """, issueNumber, memorySection, localOutput, tweak);

            try {
                if (useOpenAi) {
                    provider = "OpenAI GPT-4o";
                    cloudOutput = new OpenAiClient(SqliteStorage.loadConfig("openai.model")).generateText(cloudPrompt);
                } else if (useClaude) {
                    provider = "Anthropic Claude 3.5";
                    cloudOutput = new ClaudeClient(SqliteStorage.loadConfig("claude.model")).generateText(cloudPrompt);
                } else {
                    provider = "Google Gemini";
                    cloudOutput = new GeminiClient(SqliteStorage.loadConfig("gemini.model")).generateText(cloudPrompt);
                }

                // Alignment reads the cloud's plan back against the user's own past work, and only a
                // local model can: sending that history to the same API that produced the plan would
                // undo the reason the two steps are separate. Without one the plan stands unchecked,
                // and that is said rather than absorbed -- an unverified blueprint looks exactly like
                // a verified one.
                if (localOllama == null) {
                    LOGGER.info(
                            "\n================ CLOUD BLUEPRINT ({}) ================\n{}\n============================================================",
                            provider,
                            cloudOutput);
                    LOGGER.info("  Not verified against your own past work — that step needs a local model.");
                    LOGGER.info("  Attach one and the blueprint comes back checked: ollama serve");
                    return 0;
                }

                LOGGER.info("Received {} response. Aligning with your local memory profile...", provider);
                String alignmentPrompt = String.format("""
                        An online expert AI provided this code solution:
                        %s

                        Verify this against my personal memory:
                        %s

                        Output the final, verified solution that matches my coding style and file patterns.
                        """, cloudOutput, memorySection);

                String finalOutput = localOllama.generateText(alignmentPrompt);
                LOGGER.info(
                        "\n================ FINAL ALIGNED BLUEPRINT ({}) ================\n{}\n============================================================",
                        provider,
                        finalOutput);

            } catch (Exception e) {
                LOGGER.error("Cloud escalation or alignment failed: {}", e.getMessage());
                return 1;
            }
        }

        return 0;
    }
}
