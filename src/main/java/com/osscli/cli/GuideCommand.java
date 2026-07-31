package com.osscli.cli;

import com.osscli.llm.ClaudeClient;
import com.osscli.llm.GeminiClient;
import com.osscli.llm.OllamaClient;
import com.osscli.llm.OpenAiClient;
import com.osscli.model.ChatMemory;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.PrMemory;
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

        // 3. Load target issue vector and extract memory contexts
        List<IssueEmbedding> embeddings = SqliteStorage.loadEmbeddings(repository);
        double[] targetVector = null;
        for (IssueEmbedding emb : embeddings) {
            if (emb.issueNumber() == issueNumber) {
                targetVector = emb.vector();
                break;
            }
        }

        StringBuilder contextBlock = new StringBuilder();
        int matchedCount = 0;

        if (targetVector != null) {
            LOGGER.info("Retrieving memory contexts from SQLite...");
            for (PrMemory prMem : SqliteStorage.loadAllPersonalPrMemories()) {
                if (prMem.vector() != null) {
                    double similarity = cosineSimilarity(targetVector, prMem.vector());
                    if (similarity >= 0.35) {
                        matchedCount++;
                        contextBlock
                                .append("--- REFERENCE DEVELOPMENT NOTE (PR #")
                                .append(prMem.prNumber())
                                .append(") ---\n");
                        contextBlock
                                .append("Files Changed: ")
                                .append(prMem.filesChanged())
                                .append("\n");
                        contextBlock
                                .append("Story:\n")
                                .append(prMem.generatedStory())
                                .append("\n\n");
                    }
                }
            }

            for (ChatMemory chatMem : SqliteStorage.loadAllPersonalChatMemories()) {
                if (chatMem.vector() != null) {
                    double similarity = cosineSimilarity(targetVector, chatMem.vector());
                    if (similarity >= 0.35) {
                        matchedCount++;
                        contextBlock
                                .append("--- REFERENCE DISCUSSION NOTE (File: ")
                                .append(chatMem.fileName())
                                .append(") ---\n");
                        contextBlock
                                .append("Content:\n")
                                .append(chatMem.content())
                                .append("\n\n");
                    }
                }
            }
            LOGGER.info("  ↳ Retrieved {} semantic memory contexts.", matchedCount);
        }

        String memorySection = matchedCount > 0
                ? contextBlock.toString()
                : "No specific personal past experience found. Provide expert generic resolution for " + repository
                        + ".";

        // --- TIER 1: Local Ollama Generation ---
        OllamaClient localOllama = new OllamaClient(modelName);
        if (!localOllama.isModelAvailable()) {
            LOGGER.error("Ollama model '{}' is not available. Please start Ollama.", modelName);
            return 1;
        }

        LOGGER.info("Synthesizing initial blueprint using local model '{}'...", modelName);
        String localPrompt = String.format(
                """
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
                """,
                repository, memorySection.trim(), target.title(), target.body());

        String localOutput = localOllama.generateText(localPrompt);
        LOGGER.info(
                "\n================ LOCAL AI DRAFT ================\n{}\n================================================",
                localOutput);

        // --- TIER 2: Interactive Escalation ---
        boolean forceCloud = useGemini || useOpenAi || useClaude;
        Scanner scanner = new Scanner(System.in);
        String tweak = "";

        if (!forceCloud) {
            LOGGER.info("\nWould you like to refine this with a Cloud Expert? (Type your tweak or 'n' to exit):");
            tweak = scanner.nextLine().trim();
        }

        if (forceCloud || (!tweak.isEmpty() && !tweak.equalsIgnoreCase("n"))) {
            String cloudOutput;
            String provider;

            String cloudPrompt = String.format(
                    """
                    You are an expert maintainer. Refine this resolution for issue #%d.
                    Memory Context: %s
                    Local Draft: %s
                    User Instructions: %s
                    """,
                    issueNumber, memorySection, localOutput, tweak);

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

                LOGGER.info("Received {} response. Aligning with your local memory profile...", provider);
                String alignmentPrompt = String.format(
                        """
                        An online expert AI provided this code solution:
                        %s

                        Verify this against my personal memory:
                        %s

                        Output the final, verified solution that matches my coding style and file patterns.
                        """,
                        cloudOutput, memorySection);

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

    private double cosineSimilarity(double[] vecA, double[] vecB) {
        if (vecA == null || vecB == null || vecA.length != vecB.length) return 0.0;
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += Math.pow(vecA[i], 2);
            normB += Math.pow(vecB[i], 2);
        }
        return (normA == 0 || normB == 0) ? 0 : dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
