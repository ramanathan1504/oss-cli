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

import com.osscli.safety.UpstreamGuard;
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

    /** Raised when the wizard is asked a question and there is nobody there to answer it. */
    private static final class NoOneThere extends RuntimeException {}

    /**
     * One answer from the operator, or a refusal.
     *
     * <p>{@code scanner.nextLine()} on a closed stdin throws {@link java.util.NoSuchElementException},
     * and this wizard called it eleven times. Run from a script, a pipe, or anything without a
     * terminal, the first prompt printed and then the user got
     * {@code java.util.NoSuchElementException: No line found} over six frames of picocli — from the
     * very first command a new install is told to run.
     *
     * <p>Returning "" instead would be worse, not better: every one of the eleven prompts means
     * "keep the current value" when empty, so the wizard would run to completion, change nothing,
     * and report success. A setup that silently configures nothing is the failure this whole
     * codebase is built to avoid.
     */
    private static String ask(Scanner scanner) {
        if (!scanner.hasNextLine()) {
            throw new NoOneThere();
        }
        return scanner.nextLine().trim();
    }

    @Override
    public Integer call() throws Exception {
        try {
            return wizard(new Scanner(System.in));
        } catch (NoOneThere e) {
            LOGGER.error("");
            LOGGER.error("  oss setup asks questions, and there is no terminal here to answer them.");
            LOGGER.error("  Nothing was changed.");
            LOGGER.error("");
            LOGGER.error("  Run it from a terminal, or set values directly:");
            LOGGER.error("    oss sync --add owner/name        the repository to follow");
            LOGGER.error("    oss model --fetch                the embedding model");
            LOGGER.error("    export GITHUB_TOKEN=$(gh auth token)");
            LOGGER.error("");
            return 1;
        }
    }

    private Integer wizard(Scanner scanner) throws Exception {
        LOGGER.info("==================================================");
        LOGGER.info("            oss Interactive Setup Wizard          ");
        LOGGER.info("==================================================");

        // 1. Configure GitHub Username
        String currentUsername = SqliteStorage.loadConfig("github.username");
        LOGGER.info("Current GitHub Username: [ {} ]", currentUsername == null ? "(none)" : currentUsername);
        LOGGER.info("Enter new Username (or press Enter to keep current):");
        String inputUsername = ask(scanner);
        if (!inputUsername.isEmpty()) {
            SqliteStorage.saveConfig("github.username", inputUsername);
            currentUsername = inputUsername;
            LOGGER.info("  ↳ Updated GitHub Username to: {}", currentUsername);
        }

        // 1.5 Configure Primary Target Repository
        String currentDefaultRepo = SqliteStorage.loadConfig("default.repository");
        LOGGER.info("Current Primary Repository: [ {} ]", currentDefaultRepo == null ? "(none)" : currentDefaultRepo);
        LOGGER.info("Enter new Primary Repository (owner/name) or press Enter to keep current:");
        String inputRepo = ask(scanner);
        if (!inputRepo.isEmpty()) {
            SqliteStorage.saveConfig("default.repository", inputRepo);
            currentDefaultRepo = inputRepo;
            LOGGER.info("  ↳ Updated Primary Repository to: {}", currentDefaultRepo);
        }

        // 2. Configure Triage Model
        String currentTriageModel = SqliteStorage.loadConfig("ollama.model.triage");
        LOGGER.info("Current AI Triage Model: [ {} ]", currentTriageModel == null ? "(none)" : currentTriageModel);
        LOGGER.info("Enter new Triage Model (or press Enter to keep current):");
        String inputTriage = ask(scanner);
        if (!inputTriage.isEmpty()) {
            SqliteStorage.saveConfig("ollama.model.triage", inputTriage);
            currentTriageModel = inputTriage;
            LOGGER.info("  ↳ Updated AI Triage Model to: {}", currentTriageModel);
        }

        // 3. Report the Embedding Model
        // Not a question, because there is nothing to answer. The embedder runs inside this process
        // and ships with the tool, so there is no endpoint to point at and no name to get wrong.
        // Asking used to imply otherwise, and a wrong answer here silently produced vectors that
        // nothing else could be compared against.
        LOGGER.info("Vector Embedding Model: [ {} ] (built in, runs in-process)", com.osscli.Defaults.EMBEDDING_MODEL);
        if (com.osscli.retrieval.Embeddings.isReady()) {
            LOGGER.info("  ↳ Present. Search and pick answer by meaning.");
        } else {
            LOGGER.info("  ↳ Not fetched yet. Search answers by shared terms until it is.");
            LOGGER.info("    {}", com.osscli.retrieval.Embeddings.ABSENT_HINT);
        }

        // 4. Configure Guidance Model
        String currentGuidanceModel = SqliteStorage.loadConfig("ollama.model.guidance");
        LOGGER.info(
                "Current Deep Guidance Model: [ {} ]", currentGuidanceModel == null ? "(none)" : currentGuidanceModel);
        LOGGER.info("Enter new Guidance Model (or press Enter to keep current):");
        String inputGuidance = ask(scanner);
        if (!inputGuidance.isEmpty()) {
            SqliteStorage.saveConfig("ollama.model.guidance", inputGuidance);
            currentGuidanceModel = inputGuidance;
            LOGGER.info("  ↳ Updated Deep Guidance Model to: {}", currentGuidanceModel);
        }

        // 4b. Where Ollama is
        // Asked because it is answerable: the daemon does not have to be on this machine, and a
        // laptop borrowing a desktop's GPU is the ordinary reason to move it. The key existed and
        // was honoured nowhere, so there was no supported way to say this at all.
        String currentOllamaUrl = SqliteStorage.loadConfig("ollama.url");
        LOGGER.info(
                "Current Ollama address: [ {} ]",
                currentOllamaUrl == null ? com.osscli.Defaults.OLLAMA_URL : currentOllamaUrl);
        LOGGER.info("Enter new Ollama address (e.g. http://gpu-box.local:11434) or press Enter to keep current:");
        String inputOllamaUrl = ask(scanner);
        if (!inputOllamaUrl.isEmpty()) {
            SqliteStorage.saveConfig("ollama.url", inputOllamaUrl);
            LOGGER.info("  ↳ Updated Ollama address to: {}", inputOllamaUrl);
        }

        // 5. Configure Cloud Agent (Gemini Model)
        String currentGeminiModel = SqliteStorage.loadConfig("gemini.model");
        LOGGER.info(
                "Current Cloud Agent Model (Gemini): [ {} ]",
                currentGeminiModel == null ? "(none)" : currentGeminiModel);
        LOGGER.info(
                "Enter new Gemini Model (e.g., gemini-1.5-flash-latest, gemini-pro) or press Enter to keep current:");
        String inputGemini = ask(scanner);
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
        String inputOpenAi = ask(scanner);
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
        String inputClaude = ask(scanner);
        if (!inputClaude.isEmpty()) {
            SqliteStorage.saveConfig("claude.model", inputClaude);
            currentClaudeModel = inputClaude;
            LOGGER.info("  ↳ Updated Cloud Agent Model to: {}", currentClaudeModel);
        }

        // 8. Configure the note folders
        String currentDrivePaths = SqliteStorage.loadConfig("drive.paths");
        LOGGER.info(
                "Current note folders (drive.paths): [ {} ]", currentDrivePaths == null ? "(none)" : currentDrivePaths);
        LOGGER.info("Enter new note folders (comma-separated, or press Enter to keep current):");
        String inputDrive = ask(scanner);
        if (!inputDrive.isEmpty()) {
            SqliteStorage.saveConfig("drive.paths", inputDrive);
            currentDrivePaths = inputDrive;
            LOGGER.info("  ↳ Updated note folders to: {}", currentDrivePaths);
        }

        // 9. Configure Automated Backup Location
        String currentBackupPath = SqliteStorage.loadConfig("backup.path");
        LOGGER.info("Current Automated Backup Path: [ {} ]", currentBackupPath == null ? "(none)" : currentBackupPath);
        LOGGER.info("Enter new Backup Directory Path (or press Enter to keep current):");
        String inputBackup = ask(scanner);
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

        // 11. Optional capabilities — and for almost everyone, nothing to do here.
        //
        // Both capabilities this used to ask you to attach now ship inside the tool: the matrix
        // engine is `oss run`, and memory is `oss memory`. Asking for a path without saying that
        // reads as a missing dependency, and someone answers it by pasting the nearest repository
        // they have. Enter is the ordinary answer; a path is the exception.
        //
        // A pack must not be pasted here. It is data the built-in engine reads off disk, with no
        // program to call and nothing to register -- `oss ext add` on one fails, correctly.
        LOGGER.info("\n--- Optional capabilities (press Enter to skip any) ---");
        LOGGER.info("Nothing here is required, and both capabilities are already built in:");
        LOGGER.info("  running    oss run      the matrix engine ships inside oss");
        LOGGER.info("  memory     oss memory   files and indexes notes with nothing attached");
        LOGGER.info("Answer only if you have written your own program to do one of these.");
        LOGGER.info("A pack is not one of them: run it with `oss run --pack <dir>`, do not add it here.");

        registerOptionalExtension(
                scanner,
                com.osscli.ext.Extension.Kind.RUNNER,
                "your own program that RUNS things — not a pack, and not needed for `oss run`");
        registerOptionalExtension(
                scanner,
                com.osscli.ext.Extension.Kind.MEMORY,
                "your own program that REMEMBERS — not needed for `oss memory`");

        // 12. Upstream writes.
        //
        // Nothing to configure, deliberately. There is no setting here that permits an outward
        // write, because a setting is switched on once and then forgotten -- after which the
        // protection exists only in the belief that it exists. Approval is per invocation, names
        // its target, and is confirmed at the terminal every time.
        LOGGER.info("\n--- Upstream writes ---");
        LOGGER.info("  Refused by default, everywhere, and there is no setting to change that.");
        LOGGER.info("  To permit ONE write, name the repository on the command line:");
        LOGGER.info("    oss run hub {} owner/name", UpstreamGuard.APPROVE_FLAG);
        LOGGER.info("  You are still asked to confirm, every time, at the terminal.");

        // 13. What is on, and what is simply not configured.
        LOGGER.info("\n==================================================");
        LOGGER.info("Configuration successfully updated in local SQLite!");
        LOGGER.info("==================================================");
        LOGGER.info("Optional, and their state now:");
        LOGGER.info(
                "  ollama    {}",
                currentTriageModel == null ? "not set (offline AI features stay off)" : currentTriageModel);
        LOGGER.info(
                "  claude    {}",
                currentClaudeModel == null ? "not set (cloud escalation stays off)" : currentClaudeModel);
        for (com.osscli.ext.Extension e : com.osscli.ext.ExtensionRegistry.all()) {
            LOGGER.info("  {}<{}>  {}", e.kind().lower(), e.getName(), e.getRoot());
        }
        if (com.osscli.ext.ExtensionRegistry.all().isEmpty()) {
            LOGGER.info("  runner/memory  none registered, and none needed (oss run, oss memory)");
        }
        LOGGER.info(
                "  upstream  refused unless {} names the repo, and confirmed each time", UpstreamGuard.APPROVE_FLAG);

        return 0;
    }

    /**
     * Offer to register one optional extension.
     *
     * <p>Failure here is reported and stepped over rather than aborting: someone half way through
     * the wizard with a mistyped path should not lose the model and token settings they already
     * entered.
     */
    private void registerOptionalExtension(Scanner scanner, com.osscli.ext.Extension.Kind kind, String hint) {
        LOGGER.info("\nRegister a {} extension? {}", kind.lower(), hint);
        LOGGER.info("Path to the repo (or press Enter to skip):");
        String path = ask(scanner);
        if (path.isEmpty()) {
            LOGGER.info("  ↳ skipped");
            return;
        }
        // Shell tilde expansion never happened -- this came from a Scanner, not a shell.
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        try {
            com.osscli.ext.Extension ext = com.osscli.ext.ExtensionRegistry.readManifest(java.nio.file.Path.of(path));
            if (ext.kind() != kind) {
                LOGGER.warn(
                        "  ⚠ that repo declares kind '{}', not '{}' — registering it anyway",
                        ext.kind().lower(),
                        kind.lower());
            }
            com.osscli.ext.ExtensionRegistry.add(ext);
            LOGGER.info(
                    "  ↳ registered {} ({}) — verbs: {}",
                    ext.getName(),
                    ext.kind().lower(),
                    String.join(", ", ext.getVerbs().keySet()));
        } catch (RuntimeException e) {
            LOGGER.warn("  ⚠ not registered: {}", e.getMessage());
            LOGGER.warn("    The rest of your setup is unaffected; add it later with: oss ext add {}", path);
        }
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
