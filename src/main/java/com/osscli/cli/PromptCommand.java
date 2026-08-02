package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.llm.ClaudeClient;
import com.osscli.llm.GeminiClient;
import com.osscli.llm.OllamaClient;
import com.osscli.llm.OpenAiClient;
import com.osscli.model.PromptContextChunk;
import com.osscli.retrieval.ContextRetriever;
import com.osscli.storage.SqliteStorage;
import com.osscli.util.CredentialManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "prompt",
        mixinStandardHelpOptions = true,
        description =
                "Retrieve all local context and let Ollama answer directly. If context exceeds Ollama's limit or confidence is low, builds a ready-to-send expert prompt instead.")
public class PromptCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(PromptCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", description = "The issue number to investigate")
    private long issueNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository (owner/name)")
    private String repository;

    @Option(
            names = {"--force-prompt"},
            description = "Skip Ollama — always build the expert prompt regardless of context size")
    private boolean forcePrompt;

    @Option(
            names = {"--copy"},
            description = "Copy the generated expert prompt to clipboard (macOS pbcopy)")
    private boolean copy;

    @Option(
            names = {"--out"},
            description = "Save the generated expert prompt to a Markdown file")
    private String outFile;

    @Option(
            names = {"--send-gemini"},
            description = "Auto-send the expert prompt to Google Gemini when escalation occurs")
    private boolean sendGemini;

    @Option(
            names = {"--send-openai"},
            description = "Auto-send the expert prompt to OpenAI GPT-4o when escalation occurs")
    private boolean sendOpenAi;

    @Option(
            names = {"--send-claude"},
            description = "Auto-send the expert prompt to Anthropic Claude when escalation occurs")
    private boolean sendClaude;

    @Override
    public Integer call() throws Exception {
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error("No target repository specified. Use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        // ── Load thresholds from config ───────────────────────────────────────
        int contextLimit = parseConfigInt("ollama.context_limit", 4096);
        double confidenceThreshold = parseConfigDouble("ollama.confidence_threshold", 0.70);
        String modelName = SqliteStorage.loadConfig("ollama.model.guidance");
        if (modelName == null) modelName = com.osscli.Defaults.GUIDANCE_MODEL;

        // ── Phase 1: Retrieve all local context ───────────────────────────────
        LOGGER.info("Retrieving local context for issue #{} in '{}'...", issueNumber, repository);
        List<PromptContextChunk> chunks = ContextRetriever.retrieve(issueNumber, repository);

        if (chunks.isEmpty()) {
            LOGGER.error("No local data found for issue #{} in '{}'. Run 'sync' first.", issueNumber, repository);
            return 1;
        }

        int totalTokens = ContextRetriever.totalTokens(chunks);
        LOGGER.info("  ↳ Retrieved {} context chunks (~{} tokens)", chunks.size(), totalTokens);

        String structuredContext = buildStructuredContext(repository, issueNumber, chunks);

        // ── Phase 2: Decide — Ollama answers locally OR build expert prompt ───
        boolean needsEscalation = forcePrompt || totalTokens > contextLimit;
        String escalationReason = forcePrompt ? "forced" : (totalTokens > contextLimit ? "context_overflow" : null);

        if (!needsEscalation) {
            // Try Ollama first
            OllamaClient ollama = new OllamaClient(modelName);
            if (!ollama.isModelAvailable()) {
                LOGGER.warn("Ollama model '{}' is unavailable — falling back to expert prompt.", modelName);
                needsEscalation = true;
                escalationReason = "ollama_unavailable";
            } else {
                LOGGER.info(
                        "Context fits within Ollama limit ({} / {} tokens). Asking Ollama locally...",
                        totalTokens,
                        contextLimit);
                String jsonPrompt = String.format(
                        """
                        %s

                        ## TASK
                        Analyse ISSUE #%d and nothing else. Every section after it is
                        background material that may or may not be relevant — cite it only
                        where it genuinely bears on ISSUE #%d, and ignore the rest. If the
                        background does not explain this issue, say so and set escalate.

                        Respond in JSON with this exact structure:
                        {
                          "answer": "<your technical analysis of ISSUE #%d and recommendation>",
                          "confidence": <0.0 to 1.0>,
                          "escalate": <true if the context is insufficient for a confident answer>
                        }
                        """,
                        structuredContext, issueNumber, issueNumber, issueNumber);

                try {
                    String jsonResponse = ollama.generateJson(jsonPrompt);
                    JsonNode node = MAPPER.readTree(jsonResponse);
                    double confidence = node.path("confidence").asDouble(0.5);
                    boolean escalate = node.path("escalate").asBoolean(false);
                    String answer = node.path("answer").asText("");

                    if (!escalate && confidence >= confidenceThreshold && !answer.isEmpty()) {
                        // ✅ Ollama answered confidently
                        LOGGER.info("\n═══════════════════════════════════════════════════════");
                        LOGGER.info(
                                " ✔ LOCAL ANSWER  |  Issue #{}  |  Confidence: {}",
                                issueNumber,
                                String.format("%.0f%%", confidence * 100));
                        LOGGER.info("═══════════════════════════════════════════════════════\n");
                        LOGGER.info("{}", answer);
                        LOGGER.info("\n═══════════════════════════════════════════════════════");

                        // One row, then attach its chunks. This previously called savePromptHistory twice --
                        // once discarding the id, once nested in the chunk call -- so every local answer wrote a
                        // duplicate row and the chunks attached to only one of them.
                        long localHistoryId = SqliteStorage.savePromptHistory(
                                issueNumber, repository, true, null, answer, null, totalTokens, confidence, null);
                        SqliteStorage.savePromptContextChunks(localHistoryId, chunks);

                        recordResolution("ollama", structuredContext, answer);
                        return 0;
                    } else {
                        // Low confidence — escalate
                        LOGGER.info(
                                "  ↳ Ollama confidence too low ({}) — building expert prompt.",
                                String.format("%.0f%%", confidence * 100));
                        needsEscalation = true;
                        escalationReason = "low_confidence";
                    }
                } catch (Exception e) {
                    // Distinguish "the model ran out of time" from "the model produced junk".
                    // Collapsing both into parse_error sends anyone diagnosing this at the model
                    // and its output, when the actual cause is the clock and the fix is config.
                    boolean timedOut = e instanceof java.net.http.HttpTimeoutException
                            || (e.getMessage() != null
                                    && e.getMessage().toLowerCase().contains("timed out"));
                    if (timedOut) {
                        LOGGER.warn("  ↳ Ollama did not respond in time — building expert prompt."
                                + " Raise 'ollama.timeout_seconds' if this is routine for your hardware.");
                        escalationReason = "timeout";
                    } else {
                        LOGGER.warn(
                                "  ↳ Ollama response could not be parsed — building expert prompt. ({})",
                                e.getMessage());
                        escalationReason = "parse_error";
                    }
                    needsEscalation = true;
                }
            }
        }

        // ── Phase 3: Build expert prompt ─────────────────────────────────────
        LOGGER.info("  ↳ Escalation reason: {}. Building expert prompt...", escalationReason);
        String expertPrompt = buildExpertPrompt(repository, issueNumber, chunks);

        LOGGER.info("\n╔══════════════════════════════════════════════════════════╗");
        LOGGER.info("║  EXPERT PROMPT  |  Issue #{}  |  ~{} tokens             ║", issueNumber, totalTokens);
        LOGGER.info("╚══════════════════════════════════════════════════════════╝\n");
        LOGGER.info("{}", expertPrompt);
        LOGGER.info("\n══════════════════════════════════════════════════════════");
        LOGGER.info("  Copy to ChatGPT, Gemini, or Claude for expert resolution.");
        LOGGER.info("══════════════════════════════════════════════════════════");

        // ── Handle output options ─────────────────────────────────────────────
        if (copy) {
            copyToClipboard(expertPrompt);
        }

        if (outFile != null) {
            Path outPath = Paths.get(outFile);
            Files.createDirectories(outPath.getParent() == null ? Paths.get(".") : outPath.getParent());
            Files.writeString(outPath, expertPrompt, StandardCharsets.UTF_8);
            LOGGER.info("  ✔ Expert prompt saved → {}", outPath.toAbsolutePath());
        }

        // ── Auto-send to cloud if requested ──────────────────────────────────
        String cloudResponse = null;
        String providerSent = null;
        if (sendGemini || sendOpenAi || sendClaude) {
            cloudResponse = sendToCloud(expertPrompt);
            providerSent = sendOpenAi ? "openai" : (sendClaude ? "claude" : "gemini");
            if (cloudResponse != null) {
                LOGGER.info(
                        "\n═══════════════════ CLOUD RESPONSE ({}) ═══════════════════", providerSent.toUpperCase());
                LOGGER.info("{}", cloudResponse);
                LOGGER.info("═══════════════════════════════════════════════════════════");
            }
        }

        // ── Log to SQLite ─────────────────────────────────────────────────────
        // The cloud answer is stored, not just the prompt that produced it. Recording only the outgoing prompt
        // left escalation one-way: the expensive half of the exchange arrived, was printed, and was lost.
        long historyId = SqliteStorage.savePromptHistory(
                issueNumber,
                repository,
                false,
                escalationReason,
                cloudResponse,
                expertPrompt,
                totalTokens,
                0.0,
                providerSent);
        SqliteStorage.savePromptContextChunks(historyId, chunks);

        if (cloudResponse != null) {
            recordResolution(providerSent, expertPrompt, cloudResponse);
        }

        return 0;
    }

    /**
     * Feeds a finished answer back into the searchable corpus.
     *
     * <p>Called for both the local and the escalated path, because the loop is only closed if it closes regardless of
     * which model happened to answer. An escalated answer is the more valuable of the two -- it is the one that cost
     * a cloud call -- so losing it was the more expensive omission.
     */
    private void recordResolution(String source, String context, String answer) {
        String title = null;
        try {
            title = SqliteStorage.loadIssues(repository).stream()
                    .filter(i -> i.number() == issueNumber)
                    .map(com.osscli.model.Issue::title)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // A missing title is cosmetic; the note is still worth writing without it.
            LOGGER.debug("Could not load issue title for #{}: {}", issueNumber, e.getMessage());
        }
        com.osscli.knowledge.ResolutionWriter.record(repository, issueNumber, title, source, context, answer);
    }

    /**
     * The structured context both paths share: role, the issue under investigation, then each
     * background source under its own heading.
     *
     * <p>The local path used to send a flat list where every chunk carried an identical
     * {@code ## SOURCE_TYPE [ref]} heading, so the issue -- roughly 300 of 6000 tokens -- looked no
     * different from the two dozen background chunks around it. With nothing marking which section
     * was the question, models answered about whichever chunk read as most salient: a Dependabot PR,
     * an unrelated Kafka note. The escalation path had always structured this correctly; only the
     * local prompt was flat. Building both from one method is what stops that divergence returning.
     */
    private String buildStructuredContext(String repo, long issueNum, List<PromptContextChunk> chunks) {
        return buildExpertPrompt(repo, issueNum, chunks, false);
    }

    private String buildExpertPrompt(String repo, long issueNum, List<PromptContextChunk> chunks) {
        return buildExpertPrompt(repo, issueNum, chunks, true);
    }

    private String buildExpertPrompt(
            String repo, long issueNum, List<PromptContextChunk> chunks, boolean includeExpertTask) {
        String issueBlock = chunks.stream()
                .filter(c -> c.included() && "issue".equals(c.sourceType()))
                .map(PromptContextChunk::content)
                .findFirst()
                .orElse("(Issue data not available)");

        String stackBlock = chunks.stream()
                .filter(c -> c.included() && "stack_trace".equals(c.sourceType()))
                .map(PromptContextChunk::content)
                .collect(Collectors.joining("\n"));

        String relatedBlock = chunks.stream()
                .filter(c -> c.included() && "related_issue".equals(c.sourceType()))
                .map(c -> "- " + c.sourceRef() + ": " + c.content())
                .collect(Collectors.joining("\n"));

        String prBlock = chunks.stream()
                .filter(c -> c.included() && "pr_memory".equals(c.sourceType()))
                .map(c -> "### " + c.sourceRef() + " (similarity: " + String.format("%.0f%%", c.relevanceScore() * 100)
                        + ")\n" + c.content())
                .collect(Collectors.joining("\n\n"));

        String chatBlock = chunks.stream()
                .filter(c -> c.included() && "chat_memory".equals(c.sourceType()))
                .map(c -> "**" + c.sourceRef() + "**\n" + c.content())
                .collect(Collectors.joining("\n\n"));

        String jiraBlock = chunks.stream()
                .filter(c -> c.included() && "jira".equals(c.sourceType()))
                .map(PromptContextChunk::content)
                .collect(Collectors.joining("\n"));

        StringBuilder prompt = new StringBuilder();
        prompt.append("## ROLE\n");
        prompt.append("You are an expert maintainer for the `").append(repo).append("` open-source repository.\n\n");

        prompt.append("## ISSUE #").append(issueNum).append("\n");
        prompt.append(issueBlock).append("\n\n");

        if (!stackBlock.isEmpty()) {
            prompt.append("## STACK TRACE\n```\n").append(stackBlock).append("\n```\n\n");
        }
        if (!relatedBlock.isEmpty()) {
            prompt.append("## RELATED ISSUES\n").append(relatedBlock).append("\n\n");
        }
        if (!prBlock.isEmpty()) {
            prompt.append("## SIMILAR PAST FIXES (Personal Memory)\n")
                    .append(prBlock)
                    .append("\n\n");
        }
        if (!chatBlock.isEmpty()) {
            prompt.append("## RELEVANT PAST AI CONVERSATIONS\n")
                    .append(chatBlock)
                    .append("\n\n");
        }
        if (!jiraBlock.isEmpty()) {
            prompt.append("## JIRA REFERENCES\n").append(jiraBlock).append("\n\n");
        }

        if (includeExpertTask) {
            prompt.append("## TASK\n");
            prompt.append("Based on all the context above, please provide:\n");
            prompt.append("1. **Root Cause Analysis** — What is causing this issue?\n");
            prompt.append("2. **Fix Strategy** — Specific files and code changes recommended.\n");
            prompt.append("3. **Risk Assessment** — What could go wrong with the proposed fix?\n");
            prompt.append("4. **Test Plan** — How to verify the fix is correct.\n");
        }

        return prompt.toString().strip();
    }

    private void copyToClipboard(String text) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {"pbcopy"});
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            process.waitFor();
            LOGGER.info("  ✔ Expert prompt copied to clipboard.");
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Could not copy to clipboard: {}", e.getMessage());
        }
    }

    private String sendToCloud(String prompt) {
        try {
            if (sendOpenAi) {
                String model = SqliteStorage.loadConfig("openai.model");
                return new OpenAiClient(model == null ? "gpt-4o" : model).generateText(prompt);
            } else if (sendClaude) {
                String model = SqliteStorage.loadConfig("claude.model");
                return new ClaudeClient(CredentialManager.getClaudeKey(), model).generateText(prompt);
            } else {
                String model = SqliteStorage.loadConfig("gemini.model");
                return new GeminiClient(model == null ? "gemini-2.0-flash" : model).generateText(prompt);
            }
        } catch (Exception e) {
            LOGGER.error("Cloud send failed: {}", e.getMessage());
            return null;
        }
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
