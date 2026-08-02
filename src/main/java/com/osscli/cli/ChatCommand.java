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
        name = "chat",
        mixinStandardHelpOptions = true,
        description = "Open an interactive REPL chat session with local alignment and multi-cloud escalation")
public class ChatCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ChatCommand.class);

    @Parameters(index = "0", description = "The issue or PR number to chat about")
    private long issueNumber;

    @Option(names = {"-r", "--repo"})
    private String repository;

    @Option(
            names = {"--gemini"},
            description = "Use Gemini API for cloud escalation (Default)")
    private boolean useGemini;

    @Option(
            names = {"--openai"},
            description = "Use OpenAI API for cloud escalation")
    private boolean useOpenAi;

    @Option(
            names = {"--claude"},
            description = "Use Anthropic Claude API for cloud escalation")
    private boolean useClaude;

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
        // 1. Resolve Local Configs
        String modelName = SqliteStorage.loadConfig("ollama.model.guidance");
        if (modelName == null) {
            modelName = com.osscli.Defaults.GUIDANCE_MODEL;
        }

        // 2. Load Issue Context
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        List<Issue> prs = SqliteStorage.loadPullRequests(repository);
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
            LOGGER.error("Issue #{} not found in local data for '{}'.", issueNumber, repository);
            return 1;
        }

        // 3. Load Personal Memory Context (RAG)
        List<IssueEmbedding> embeddings = SqliteStorage.loadEmbeddings(repository);
        double[] targetVector = null;
        for (IssueEmbedding emb : embeddings) {
            if (emb.issueNumber() == issueNumber) {
                targetVector = emb.vector();
                break;
            }
        }

        StringBuilder memoryContext = new StringBuilder();
        if (targetVector != null) {
            for (PrMemory prMem : SqliteStorage.loadAllPersonalPrMemories()) {
                if (prMem.vector() != null && cosineSimilarity(targetVector, prMem.vector()) >= 0.35) {
                    memoryContext
                            .append("PR #")
                            .append(prMem.prNumber())
                            .append(" Story:\n")
                            .append(prMem.generatedStory())
                            .append("\n");
                }
            }
            for (ChatMemory chatMem : SqliteStorage.loadAllPersonalChatMemories()) {
                if (chatMem.vector() != null && cosineSimilarity(targetVector, chatMem.vector()) >= 0.35) {
                    memoryContext
                            .append("Past Chat: ")
                            .append(chatMem.content())
                            .append("\n");
                }
            }
        }

        // 4. Initialize Local AI
        OllamaClient localClient = new OllamaClient(modelName);
        if (!localClient.isModelAvailable()) {
            LOGGER.error("Ollama model '{}' is not available. Please start Ollama.", modelName);
            return 1;
        }

        // 5. Initialize Cloud AI Clients based on Flags
        GeminiClient geminiClient = null;
        OpenAiClient openAiClient = null;
        ClaudeClient claudeClient = null;
        String cloudProviderName = "Google Gemini";

        if (useOpenAi) {
            String key = retrieveKey("OPENAI_API_KEY", "openai_api_key");
            if (key == null) {
                LOGGER.error("OpenAI API Key missing.");
                return 1;
            }
            String model = SqliteStorage.loadConfig("openai.model");
            openAiClient = new OpenAiClient(model == null ? "gpt-4o" : model);
            cloudProviderName = "OpenAI GPT";
        } else if (useClaude) {
            String key = retrieveKey("ANTHROPIC_API_KEY", "anthropic_api_key");
            if (key == null) {
                LOGGER.error("Anthropic API Key missing.");
                return 1;
            }
            String model = SqliteStorage.loadConfig("claude.model");
            claudeClient = new ClaudeClient(model == null ? "claude-3-5-sonnet-20240620" : model);
            cloudProviderName = "Anthropic Claude";
        } else {
            // Default to Gemini
            String key = retrieveKey("GEMINI_API_KEY", "gemini_api_key");
            if (key == null) {
                LOGGER.error("Gemini API Key missing.");
                return 1;
            }
            String model = SqliteStorage.loadConfig("gemini.model");
            geminiClient = new GeminiClient(model == null ? "gemini-1.5-flash-latest" : model);
        }

        // 6. The REPL (Read-Eval-Print Loop)
        Scanner scanner = new Scanner(System.in);
        StringBuilder conversationHistory = new StringBuilder();
        String lastUserPrompt = "";

        LOGGER.info("==================================================");
        LOGGER.info(" INTERACTIVE COPILOT SESSION: Issue #{}", issueNumber);
        LOGGER.info(" Backend: Local First ({}) -> Escalation ({})", modelName, cloudProviderName);
        LOGGER.info(" Type 'exit' to save and end session.");
        LOGGER.info("==================================================\n");

        while (true) {
            LOGGER.info("> Type your response (or type 'y' to escalate previous prompt to Cloud):");
            String userInput = scanner.nextLine().trim();

            if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                LOGGER.info("Ending chat session. Saving and indexing memory...");
                saveAndIndexChatHistory(conversationHistory.toString(), target);
                LOGGER.info("Goodbye!");
                break;
            }
            if (userInput.isEmpty()) continue;

            // --- ESCALATION & ALIGNMENT FLOW ---
            if (userInput.equalsIgnoreCase("y") && !lastUserPrompt.isEmpty()) {
                LOGGER.info("Bridging request to {} (Cloud Agent)...", cloudProviderName);

                String cloudPrompt = String.format(
                        """
                        You are an expert maintainer for the '%s' open-source repository.
                        We are actively resolving Issue #%d: %s
                        Body: %s

                        --- CONVERSATION HISTORY ---
                        %s

                        --- NEW PROMPT ---
                        %s

                        Provide a highly technical, expert code resolution for the new prompt.
                        """,
                        issueNumber, target.title(), target.body(), conversationHistory.toString(), lastUserPrompt);

                try {
                    String cloudOutput = "";
                    if (useOpenAi) cloudOutput = openAiClient.generateText(cloudPrompt);
                    else if (useClaude) cloudOutput = claudeClient.generateText(cloudPrompt);
                    else cloudOutput = geminiClient.generateText(cloudPrompt);

                    LOGGER.info("Received Cloud Response. Aligning with your local memory profile...");

                    String alignmentPrompt = String.format(
                            """
                            You are a personal developer copilot.
                            An online expert AI provided this code solution:
                            %s

                            Here is my personal development memory (my past PRs and edits):
                            %s

                            Compare the expert solution with my memory. Output a final response that includes:
                            1. The expert solution.
                            2. ALIGNMENT CHECK: Does this solution match my past coding patterns?
                            3. Which specific local files from my past PRs should I apply this to?
                            """,
                            cloudOutput,
                            memoryContext.toString().isEmpty() ? "(No local memory found)" : memoryContext.toString());

                    String finalAlignedOutput = localClient.generateText(alignmentPrompt);

                    LOGGER.info("\n================ EXPERT ALIGNED RESPONSE ================");
                    LOGGER.info("\n{}\n", finalAlignedOutput);
                    LOGGER.info("=========================================================\n");

                    conversationHistory
                            .append("User escalated to Cloud.\nAI (Hybrid): ")
                            .append(finalAlignedOutput)
                            .append("\n\n");

                } catch (Exception e) {
                    LOGGER.error("Cloud escalation failed: {}", e.getMessage());
                }

            }
            // --- LOCAL OFFLINE FLOW ---
            else {
                lastUserPrompt = userInput;
                conversationHistory.append("User: ").append(userInput).append("\n");

                LOGGER.info("Thinking locally...");
                String localPrompt = String.format(
                        """
                        You are an expert maintainer for the '%s' acting as a live coding pair-programmer.
                        We are actively resolving Issue #%d: %s

                        --- RELEVANT PAST EXPERIENCES ---
                        %s

                        --- CONVERSATION HISTORY ---
                        %s

                        Please respond directly to the User's last message. Provide code snippets if requested.
                        """,
                        issueNumber,
                        target.title(),
                        memoryContext.toString().isEmpty() ? "(None)" : memoryContext.toString(),
                        conversationHistory.toString());

                try {
                    String localOutput = localClient.generateText(localPrompt);

                    LOGGER.info("\n================ LOCAL RESPONSE ================");
                    LOGGER.info("\n{}\n", localOutput);
                    LOGGER.info("================================================\n");

                    conversationHistory
                            .append("AI (Local): ")
                            .append(localOutput)
                            .append("\n\n");

                } catch (Exception e) {
                    LOGGER.error("Local generation failed: {}", e.getMessage());
                }
            }
        }

        return 0;
    }

    private String retrieveKey(String envName, String keychainName) {
        String key = System.getenv(envName);
        if (key != null && !key.trim().isEmpty()) return key;

        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                "sh", "-c", "security find-generic-password -s " + keychainName + " -w 2>/dev/null || true"
            });
            try (java.io.BufferedReader reader =
                    new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) return line.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private double cosineSimilarity(double[] vecA, double[] vecB) {
        if (vecA.length != vecB.length) return 0.0;
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += vecA[i] * vecA[i];
            normB += vecB[i] * vecB[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Files the session through the shared writer so it lands under its topic and is chunk-indexed.
     *
     * <p>This used to write to the first configured note folder, which is that folder's root -- above every topic
     * directory, where no topic-scoped browse or search reaches it. It also stored one vector for the whole
     * transcript, so a session matching in a single paragraph was diluted by the rest of its own text.
     */
    private void saveAndIndexChatHistory(String chatContent, Issue target) {
        LOGGER.info("  ↳ Recording this session into your knowledge base...");
        com.osscli.knowledge.ResolutionWriter.record(
                repository, issueNumber, target == null ? null : target.title(), "chat", null, chatContent);
    }
}
