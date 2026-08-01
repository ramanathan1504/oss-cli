package com.osscli.cli;

import com.osscli.model.PromptContextChunk;
import com.osscli.retrieval.ContextRetriever;
import com.osscli.storage.SqliteStorage;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "inspect",
        description =
                "Show all context retrieved for an issue and preview whether Ollama will answer locally or escalate to an expert prompt.")
public class InspectCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(InspectCommand.class);

    @Parameters(index = "0", description = "The issue number to inspect")
    private long issueNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository (owner/name)")
    private String repository;

    @Override
    public Integer call() throws Exception {
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error("No target repository specified. Use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        int contextLimit = parseConfigInt("ollama.context_limit", 4096);
        double confidenceThreshold = parseConfigDouble("ollama.confidence_threshold", 0.70);

        LOGGER.info("Inspecting context for issue #{} in '{}'...", issueNumber, repository);

        List<PromptContextChunk> chunks = ContextRetriever.retrieve(issueNumber, repository);

        if (chunks.isEmpty()) {
            LOGGER.error("No local data found for issue #{} in '{}'. Run 'sync' first.", issueNumber, repository);
            return 1;
        }

        int includedTokens = ContextRetriever.totalTokens(chunks);
        int totalChunks = chunks.size();
        long includedCount =
                chunks.stream().filter(PromptContextChunk::included).count();
        long excludedCount = totalChunks - includedCount;

        LOGGER.info("");
        LOGGER.info("══════════════════════════════════════════════════════════");
        LOGGER.info(" CONTEXT INSPECTOR  |  Issue #{}  |  {}", issueNumber, repository);
        LOGGER.info("══════════════════════════════════════════════════════════");
        LOGGER.info("");

        // Print each chunk as a table row
        LOGGER.info("  {:<14} {:<30} {:>8}  {:>7}  {}", "SOURCE TYPE", "REFERENCE", "RELEVANCE", "TOKENS", "STATUS");
        LOGGER.info("  {}", "─".repeat(74));

        for (PromptContextChunk chunk : chunks) {
            String status = chunk.included() ? "✔ included" : "✗ excluded";
            String ref =
                    chunk.sourceRef().length() > 28 ? chunk.sourceRef().substring(0, 25) + "..." : chunk.sourceRef();
            LOGGER.info(
                    "  {:<14} {:<30} {:>7}%  {:>7}  {}",
                    chunk.sourceType(),
                    ref,
                    String.format("%.0f", chunk.relevanceScore() * 100),
                    chunk.tokenCount(),
                    status);
        }

        LOGGER.info("  {}", "─".repeat(74));
        LOGGER.info("");
        LOGGER.info("  Total chunks   : {}", totalChunks);
        LOGGER.info("  Included       : {}", includedCount);
        LOGGER.info("  Excluded       : {} (over token budget)", excludedCount);
        LOGGER.info("  Included tokens: ~{}", includedTokens);
        LOGGER.info("  Context limit  : {} tokens  (ollama.context_limit)", contextLimit);
        LOGGER.info("  Conf threshold : {}         (ollama.confidence_threshold)", confidenceThreshold);
        LOGGER.info("");

        // Decision preview
        if (includedTokens <= contextLimit) {
            LOGGER.info("  ┌─────────────────────────────────────────────────────────┐");
            LOGGER.info(
                    "  │  ✔  Ollama WILL answer locally  (~{} / {} tokens)    │",
                    String.format("%-5d", includedTokens),
                    contextLimit);
            LOGGER.info(
                    "  │     Confidence threshold: {}  — if below, will escalate  │",
                    String.format("%.2f", confidenceThreshold));
            LOGGER.info("  └─────────────────────────────────────────────────────────┘");
        } else {
            LOGGER.info("  ┌─────────────────────────────────────────────────────────┐");
            LOGGER.info("  │  ⚠  Context TOO LARGE — expert prompt WILL be built    │");
            LOGGER.info(
                    "  │     Tokens: ~{}  >  limit: {}  (overflow: ~{})    │",
                    String.format("%-5d", includedTokens),
                    contextLimit,
                    String.format("%-5d", includedTokens - contextLimit));
            LOGGER.info("  │     Run: oss-cli prompt {} --force-prompt               │", issueNumber);
            LOGGER.info("  └─────────────────────────────────────────────────────────┘");
        }
        LOGGER.info("");

        // Print a short preview of the most relevant chunk
        chunks.stream()
                .filter(PromptContextChunk::included)
                .max((a, b) -> Double.compare(a.relevanceScore(), b.relevanceScore()))
                .ifPresent(top -> {
                    String preview =
                            top.content().length() > 300 ? top.content().substring(0, 300) + "..." : top.content();
                    LOGGER.info("  TOP CHUNK PREVIEW  [{}  |  {}]", top.sourceType(), top.sourceRef());
                    LOGGER.info("  {}", "─".repeat(60));
                    LOGGER.info("  {}", preview.replace("\n", "\n  "));
                    LOGGER.info("");
                });

        return 0;
    }

    private int parseConfigInt(String key, int defaultValue) {
        try {
            String val = SqliteStorage.loadConfig(key);
            return val != null ? Integer.parseInt(val.trim()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double parseConfigDouble(String key, double defaultValue) {
        try {
            String val = SqliteStorage.loadConfig(key);
            return val != null ? Double.parseDouble(val.trim()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
