package com.osscli.cli;

import com.osscli.storage.SqliteStorage;
import java.util.Scanner;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;

@Command(
        name = "setup",
        mixinStandardHelpOptions = true,
        description = "Interactive wizard to configure local system settings, models, and paths")
public class SetupCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(SetupCommand.class);

    @Override
    public Integer call() throws Exception {
        Scanner scanner = new Scanner(System.in);

        LOGGER.info("==================================================");
        LOGGER.info("          oss-cli Interactive Setup Wizard        ");
        LOGGER.info("==================================================");

        // 1. Configure GitHub Username
        String currentUsername = SqliteStorage.loadConfig("github.username");
        LOGGER.info("Current GitHub Username: [ {} ]", currentUsername == null ? "(none)" : currentUsername);
        LOGGER.info("Enter new Username (or press Enter to keep current):");
        String inputUsername = scanner.nextLine().trim();
        if (!inputUsername.isEmpty()) {
            SqliteStorage.saveConfig("github.username", inputUsername);
            currentUsername = inputUsername;
            LOGGER.info("  ↳ Updated GitHub Username to: {}", currentUsername);
        }

        // 1.5 Configure Primary Target Repository
        String currentDefaultRepo = SqliteStorage.loadConfig("default.repository");
        LOGGER.info("Current Primary Repository: [ {} ]", currentDefaultRepo == null ? "(none)" : currentDefaultRepo);
        LOGGER.info("Enter new Primary Repository (owner/name) or press Enter to keep current:");
        String inputRepo = scanner.nextLine().trim();
        if (!inputRepo.isEmpty()) {
            SqliteStorage.saveConfig("default.repository", inputRepo);
            currentDefaultRepo = inputRepo;
            LOGGER.info("  ↳ Updated Primary Repository to: {}", currentDefaultRepo);
        }

        // 2. Configure Triage Model
        String currentTriageModel = SqliteStorage.loadConfig("ollama.model.triage");
        LOGGER.info("Current AI Triage Model: [ {} ]", currentTriageModel == null ? "(none)" : currentTriageModel);
        LOGGER.info("Enter new Triage Model (or press Enter to keep current):");
        String inputTriage = scanner.nextLine().trim();
        if (!inputTriage.isEmpty()) {
            SqliteStorage.saveConfig("ollama.model.triage", inputTriage);
            currentTriageModel = inputTriage;
            LOGGER.info("  ↳ Updated AI Triage Model to: {}", currentTriageModel);
        }

        // 3. Configure Embedding Model
        String currentEmbeddingModel = SqliteStorage.loadConfig("ollama.model.embedding");
        LOGGER.info(
                "Current Vector Embedding Model: [ {} ]",
                currentEmbeddingModel == null ? "(none)" : currentEmbeddingModel);
        LOGGER.info("Enter new Embedding Model (or press Enter to keep current):");
        String inputEmbedding = scanner.nextLine().trim();
        if (!inputEmbedding.isEmpty()) {
            SqliteStorage.saveConfig("ollama.model.embedding", inputEmbedding);
            currentEmbeddingModel = inputEmbedding;
            LOGGER.info("  ↳ Updated Vector Embedding Model to: {}", currentEmbeddingModel);
        }

        // 4. Configure Guidance Model
        String currentGuidanceModel = SqliteStorage.loadConfig("ollama.model.guidance");
        LOGGER.info(
                "Current Deep Guidance Model: [ {} ]", currentGuidanceModel == null ? "(none)" : currentGuidanceModel);
        LOGGER.info("Enter new Guidance Model (or press Enter to keep current):");
        String inputGuidance = scanner.nextLine().trim();
        if (!inputGuidance.isEmpty()) {
            SqliteStorage.saveConfig("ollama.model.guidance", inputGuidance);
            currentGuidanceModel = inputGuidance;
            LOGGER.info("  ↳ Updated Deep Guidance Model to: {}", currentGuidanceModel);
        }

        // 5. Configure Cloud Agent (Gemini Model)
        String currentGeminiModel = SqliteStorage.loadConfig("gemini.model");
        LOGGER.info(
                "Current Cloud Agent Model (Gemini): [ {} ]",
                currentGeminiModel == null ? "(none)" : currentGeminiModel);
        LOGGER.info(
                "Enter new Gemini Model (e.g., gemini-1.5-flash-latest, gemini-pro) or press Enter to keep current:");
        String inputGemini = scanner.nextLine().trim();
        if (!inputGemini.isEmpty()) {
            SqliteStorage.saveConfig("gemini.model", inputGemini);
            currentGeminiModel = inputGemini;
            LOGGER.info("  ↳ Updated Cloud Agent Model to: {}", currentGeminiModel);
        }

        // 6. Configure Cloud Agent (OpenAI Model)
        String currentOpenAiModel = SqliteStorage.loadConfig("openai.model");
        LOGGER.info(
                "Current Cloud Agent Model (OpenAI): [ {} ]",
                currentOpenAiModel == null ? "(none)" : currentOpenAiModel);
        LOGGER.info("Enter new OpenAI Model (e.g., gpt-4o, gpt-4-turbo) or press Enter to keep current:");
        String inputOpenAi = scanner.nextLine().trim();
        if (!inputOpenAi.isEmpty()) {
            SqliteStorage.saveConfig("openai.model", inputOpenAi);
            currentOpenAiModel = inputOpenAi;
            LOGGER.info("  ↳ Updated Cloud Agent Model to: {}", currentOpenAiModel);
        }

        // 7. Configure Cloud Agent (Claude Model)
        String currentClaudeModel = SqliteStorage.loadConfig("claude.model");
        LOGGER.info(
                "Current Cloud Agent Model (Claude): [ {} ]",
                currentClaudeModel == null ? "(none)" : currentClaudeModel);
        LOGGER.info("Enter new Claude Model (e.g., claude-3-5-sonnet-20240620) or press Enter to keep current:");
        String inputClaude = scanner.nextLine().trim();
        if (!inputClaude.isEmpty()) {
            SqliteStorage.saveConfig("claude.model", inputClaude);
            currentClaudeModel = inputClaude;
            LOGGER.info("  ↳ Updated Cloud Agent Model to: {}", currentClaudeModel);
        }

        // 8. Configure Google Drive Locations
        String currentDrivePaths = SqliteStorage.loadConfig("drive.paths");
        LOGGER.info("Current Google Drive Paths: [ {} ]", currentDrivePaths == null ? "(none)" : currentDrivePaths);
        LOGGER.info("Enter new Google Drive Paths (comma-separated, or press Enter to keep current):");
        String inputDrive = scanner.nextLine().trim();
        if (!inputDrive.isEmpty()) {
            SqliteStorage.saveConfig("drive.paths", inputDrive);
            currentDrivePaths = inputDrive;
            LOGGER.info("  ↳ Updated Google Drive Paths to: {}", currentDrivePaths);
        }

        // 9. Configure Automated Backup Location
        String currentBackupPath = SqliteStorage.loadConfig("backup.path");
        LOGGER.info("Current Automated Backup Path: [ {} ]", currentBackupPath == null ? "(none)" : currentBackupPath);
        LOGGER.info("Enter new Backup Directory Path (or press Enter to keep current):");
        String inputBackup = scanner.nextLine().trim();
        if (!inputBackup.isEmpty()) {
            SqliteStorage.saveConfig("backup.path", inputBackup);
            currentBackupPath = inputBackup;
            LOGGER.info("  ↳ Updated Backup Path to: {}", currentBackupPath);
        }

        // 10. Security Credential Check (Multi-OS Support)
        LOGGER.info("\nChecking secure credentials on this host...");
        String githubToken = System.getenv("GITHUB_TOKEN");
        String geminiToken = System.getenv("GEMINI_API_KEY");
        String openaiToken = System.getenv("OPENAI_API_KEY");
        String claudeToken = System.getenv("ANTHROPIC_API_KEY");

        boolean hasGithubKeychain = false;
        boolean hasGeminiKeychain = false;
        boolean hasOpenAiKeychain = false;
        boolean hasClaudeKeychain = false;

        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) {
                hasGithubKeychain = checkMacKeychain("github_token");
                hasGeminiKeychain = checkMacKeychain("gemini_api_key");
                hasOpenAiKeychain = checkMacKeychain("openai_api_key");
                hasClaudeKeychain = checkMacKeychain("anthropic_api_key");
            }
        } catch (Exception ignored) {
        }

        // Log GitHub Token Status
        if (githubToken != null && !githubToken.trim().isEmpty()) {
            LOGGER.info("  ✔ GITHUB_TOKEN detected in active environment variables.");
        } else if (hasGithubKeychain) {
            LOGGER.info("  ✔ GITHUB_TOKEN detected securely inside macOS Keychain.");
        } else {
            LOGGER.warn("  ⚠ WARNING: No GITHUB_TOKEN found!");
            LOGGER.warn("    Run: security add-generic-password -a \"$USER\" -s github_token -w \"<YOUR_TOKEN>\" -U");
        }

        // Log Gemini API Key Status
        if (geminiToken != null && !geminiToken.trim().isEmpty()) {
            LOGGER.info("  ✔ GEMINI_API_KEY detected in active environment variables.");
        } else if (hasGeminiKeychain) {
            LOGGER.info("  ✔ GEMINI_API_KEY detected securely inside macOS Keychain.");
        } else {
            LOGGER.info("  ℹ NOTE: No GEMINI_API_KEY found (Required if using --gemini for Cloud Triage).");
            LOGGER.info("    Run: security add-generic-password -a \"$USER\" -s gemini_api_key -w \"<YOUR_KEY>\" -U");
        }

        // Log OpenAI API Key Status
        if (openaiToken != null && !openaiToken.trim().isEmpty()) {
            LOGGER.info("  ✔ OPENAI_API_KEY detected in active environment variables.");
        } else if (hasOpenAiKeychain) {
            LOGGER.info("  ✔ OPENAI_API_KEY detected securely inside macOS Keychain.");
        } else {
            LOGGER.info("  ℹ NOTE: No OPENAI_API_KEY found (Required if using --openai for Cloud Triage).");
            LOGGER.info("    Run: security add-generic-password -a \"$USER\" -s openai_api_key -w \"<YOUR_KEY>\" -U");
        }

        // Log Anthropic Claude API Key Status
        if (claudeToken != null && !claudeToken.trim().isEmpty()) {
            LOGGER.info("  ✔ ANTHROPIC_API_KEY detected in active environment variables.");
        } else if (hasClaudeKeychain) {
            LOGGER.info("  ✔ ANTHROPIC_API_KEY detected securely inside macOS Keychain.");
        } else {
            LOGGER.info("  ℹ NOTE: No ANTHROPIC_API_KEY found (Required if using --claude for Cloud Triage).");
            LOGGER.info(
                    "    Run: security add-generic-password -a \"$USER\" -s anthropic_api_key -w \"<YOUR_KEY>\" -U");
        }

        LOGGER.info("==================================================");
        LOGGER.info("Configuration successfully updated in local SQLite!");
        LOGGER.info("==================================================");

        return 0;
    }

    private boolean checkMacKeychain(String serviceName) throws Exception {
        Process process = Runtime.getRuntime().exec(new String[] {
            "sh", "-c", "security find-generic-password -s " + serviceName + " -w 2>/dev/null || true"
        });
        try (java.io.BufferedReader reader =
                new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            return line != null && !line.trim().isEmpty();
        }
    }
}
