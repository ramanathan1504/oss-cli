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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.llm.OllamaClient;
import com.osscli.model.Issue;
import com.osscli.storage.SqliteStorage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "sync",
        mixinStandardHelpOptions = true,
        description = "Pull live GitHub issues and PRs and save to local SQLite tables")
public class SyncCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(SyncCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Vectors are committed this often so an interrupted first index resumes instead of restarting. */
    private static final int EMBED_BATCH_SIZE = 50;

    @Option(
            names = {"--me"},
            description = "Dynamically sync and build your personal contribution profile from GitHub")
    private boolean me;

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"-a", "--all"},
            description = "Sequentially synchronize all active repositories seeded in SQLite")
    private boolean all;

    @Option(
            names = {"--add"},
            description = "Add a new GitHub repository to the local monitoring database")
    private String addRepo;

    @Option(
            names = {"--remove"},
            description = "Remove a GitHub repository from the local monitoring database")
    private String removeRepo;

    @Option(
            names = {"--no-embed"},
            description = "Skip building the local vector index (faster sync; disables semantic retrieval "
                    + "for newly synced issues)")
    private boolean noEmbed;

    @Override
    public Integer call() throws Exception {
        if (me) {
            return syncPersonalProfile();
        }

        // A. Handle Registry: Add Repository
        if (addRepo != null) {
            SqliteStorage.saveMonitoredRepository(addRepo, true);
            LOGGER.info("Successfully registered '{}' in SQLite. You can now sync it anytime!", addRepo);

            // Profile at registration: this is the moment the user is asking "what is this project?", and the answer
            // is what every later review compares against. Failure is not fatal -- 'profile --rebuild' retries.
            try {
                LOGGER.info("  ↳ Building repository profile...");
                SqliteStorage.saveRepoProfile(com.osscli.profile.RepoProfileBuilder.build(addRepo));
                LOGGER.info("  ✔ Profile built. Run 'profile -r {}' to see it.", addRepo);
            } catch (Exception e) {
                LOGGER.warn("  ⚠ Could not build the profile now: {}", e.getMessage());
                LOGGER.warn("    The repository is registered; run 'profile -r {}' to build it later.", addRepo);
            }
            return 0;
        }

        // B. Handle Registry: Remove Repository
        if (removeRepo != null) {
            SqliteStorage.deleteMonitoredRepository(removeRepo);
            LOGGER.info("Successfully removed '{}' from SQLite monitoring database.", removeRepo);
            return 0;
        }

        // C. Batch Sync All Enabled Repositories
        if (all) {
            List<String> activeRepos = SqliteStorage.loadMonitoredRepositories();
            if (activeRepos.isEmpty()) {
                LOGGER.warn("No active monitored repositories found in your local SQLite registry.");
                return 0;
            }
            LOGGER.info("Starting batch sync for {} active repositories...", activeRepos.size());
            int failed = 0;
            for (String repo : activeRepos) {
                LOGGER.info("==================================================");
                LOGGER.info("Syncing: {}", repo);
                LOGGER.info("==================================================");
                try {
                    syncRepository(repo);
                } catch (Exception e) {
                    failed++;
                    LOGGER.error("  ↳ [Error] Failed to sync '{}': {}", repo, e.getMessage());
                }
            }
            LOGGER.info("==================================================");
            if (failed > 0) {
                // Reporting success here regardless of outcome is what made a fully broken sync look like a
                // working one -- the per-repo errors scroll past and the last line is the one that is believed.
                LOGGER.error(
                        "Batch synchronization finished with errors: {} of {} repositories failed.",
                        failed,
                        activeRepos.size());
                return 1;
            }
            LOGGER.info("Batch synchronization completed successfully.");
            LOGGER.info("==================================================");
            return 0;
        }

        // D. Fallback to standard single sync (Check for default repo ONLY here)
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error(
                        "No target repository specified. Please use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        return syncRepository(repository);
    }

    private int syncRepository(String targetRepo) throws Exception {
        String[] parts = targetRepo.split("/");
        if (parts.length != 2) {
            LOGGER.error("Invalid repository format '{}'. Please use 'owner/name'.", targetRepo);
            return 1;
        }
        String owner = parts[0];
        String repoName = parts[1];

        // 1. Check SQLite for previous sync time
        String since = SqliteStorage.loadLastSyncedAt(targetRepo);
        Instant startRunTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        if (since != null) {
            LOGGER.info("  ↳ Performing delta sync (fetching changes since {})...", since);
        } else {
            LOGGER.info("  ↳ Performing full sync...");
        }

        GitHubClient client = new GitHubClient();

        // 2. Query GitHub passing the dynamic "since" timestamp
        List<Issue> allIssues = client.getOpenIssues(owner, repoName, since);

        List<Issue> realIssues =
                allIssues.stream().filter(issue -> !issue.isPullRequest()).toList();

        List<Issue> pullRequests =
                allIssues.stream().filter(Issue::isPullRequest).toList();

        // 3. Save delta records (new issues insert; modified issues overwrite automatically!)
        SqliteStorage.saveIssues(targetRepo, realIssues);
        SqliteStorage.saveIssues(targetRepo, pullRequests);

        // 4. Update the sync timestamp in SQLite
        SqliteStorage.updateLastSyncedAt(targetRepo, startRunTime.toString());

        LOGGER.info("Repository: {}", targetRepo);
        LOGGER.info("Open Issues Saved: {}", realIssues.size());
        LOGGER.info("Open PRs Saved: {}", pullRequests.size());

        printMostCommented(realIssues);
        printOldest(realIssues);
        printRecentlyUpdated(realIssues);

        // 5. Bring the vector index up to date for whatever was just stored.
        if (!noEmbed) {
            embedMissingIssues(targetRepo);
        }
        LOGGER.info("");

        return 0;
    }

    /**
     * Generates embeddings for issues in {@code repository} that do not yet have one.
     *
     * <p>Runs for every synced repository. Vectors used to appear only as a side effect of {@code duplicates}, so
     * retrieval worked for whichever repository the user happened to run that command on and silently returned nothing
     * everywhere else -- an empty result is indistinguishable from "no related issues exist".
     *
     * <p>Never fails the sync. Ollama is optional for users who only want issue tracking, so an unreachable daemon
     * downgrades this to a warning: the GitHub data is already committed and is worth keeping regardless.
     *
     * <p>Progress is saved in batches, which also makes the work resumable. First-time indexing of a large repository
     * is thousands of model calls; if it is interrupted, the next sync continues from what survived rather than
     * starting over.
     */
    private void embedMissingIssues(String repository) {
        try {
            String embedModel = com.osscli.retrieval.Embeddings.MODEL;

            List<Issue> stored = SqliteStorage.loadIssues(repository);
            java.util.Set<Long> alreadyEmbedded = SqliteStorage.loadEmbeddedIssueNumbers(repository, embedModel);

            List<Issue> pending = stored.stream()
                    .filter(issue -> !alreadyEmbedded.contains(issue.number()))
                    .toList();

            if (pending.isEmpty()) {
                LOGGER.info(
                        "  ↳ Vector index up to date ({} issues indexed with '{}').",
                        alreadyEmbedded.size(),
                        embedModel);
                return;
            }

            // Presence, not acquisition: sync will not pull 22 MB on your behalf mid-command. It
            // says what the model would add and carries on, because the issue data is the point of
            // sync and search still answers by shared terms without any of this.
            com.osscli.retrieval.LocalEmbedder embedder =
                    com.osscli.retrieval.Embeddings.ifPresent(m -> LOGGER.info("    {}", m));
            if (embedder == null) {
                LOGGER.warn(
                        "  ⚠ No local model — {} issue(s) in '{}' have no vector, so search answers by shared terms.",
                        pending.size(),
                        repository);
                LOGGER.warn("    {}", com.osscli.retrieval.Embeddings.ABSENT_HINT);
                return;
            }

            LOGGER.info("  ↳ Building vector index: {} new issue(s) with '{}'...", pending.size(), embedModel);

            List<com.osscli.model.IssueEmbedding> batch = new ArrayList<>();
            int done = 0;
            int failed = 0;

            // Thousands of model calls on a first index. Without a live line this is the longest
            // stretch of silence in a sync, and silence is indistinguishable from a hang.
            try (com.osscli.ui.Live live = com.osscli.ui.Live.start("indexing " + repository)) {
                for (Issue issue : pending) {
                    String content =
                            "Title: " + issue.title() + "\nBody: " + (issue.body() == null ? "" : issue.body());
                    try {
                        batch.add(new com.osscli.model.IssueEmbedding(
                                repository, issue.number(), embedder.embed(content)));
                    } catch (Exception e) {
                        // One malformed or oversized issue must not abandon the rest of the repository.
                        failed++;
                        LOGGER.debug("    embedding failed for #{}: {}", issue.number(), e.getMessage());
                    }

                    done++;
                    if (batch.size() >= EMBED_BATCH_SIZE) {
                        SqliteStorage.saveEmbeddings(repository, batch, embedModel);
                        batch.clear();
                        live.step(done + "/" + pending.size() + " embedded");
                    }
                }
                live.done(done + " of " + pending.size() + " embedded");
            }
            SqliteStorage.saveEmbeddings(repository, batch, embedModel);

            if (failed > 0) {
                LOGGER.warn("  ↳ Vector index updated: {} embedded, {} skipped after errors.", done - failed, failed);
            } else {
                LOGGER.info("  ✔ Vector index updated: {} issue(s) embedded.", done);
            }
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Could not update the vector index for '{}': {}", repository, e.getMessage());
            LOGGER.warn("    Issue data was saved; retrieval for this repository may be incomplete.");
        }
    }

    private void printMostCommented(List<Issue> issues) {
        LOGGER.info("--- Top 10 Most Commented Issues ---");
        issues.stream()
                .sorted(Comparator.comparingInt(Issue::comments).reversed())
                .limit(10)
                .forEach(issue -> LOGGER.info("#{} ({} comments) {}", issue.number(), issue.comments(), issue.title()));
    }

    private void printOldest(List<Issue> issues) {
        LOGGER.info("--- Oldest Open Issues ---");
        issues.stream()
                .sorted(Comparator.comparing(issue -> Instant.parse(issue.created_at())))
                .limit(10)
                .forEach(issue -> LOGGER.info("#{} {}", issue.number(), issue.title()));
    }

    private void printRecentlyUpdated(List<Issue> issues) {
        LOGGER.info("--- Recently Updated ---");
        issues.stream()
                .sorted(Comparator.comparing((Issue issue) -> Instant.parse(issue.updated_at()))
                        .reversed())
                .limit(10)
                .forEach(issue -> LOGGER.info("#{} {}", issue.number(), issue.title()));
    }

    private final java.util.Map<String, Integer> redactionTally = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Integer> redactionTotals = new java.util.LinkedHashMap<>();

    private int syncPersonalProfile() throws Exception {
        String username = SqliteStorage.loadConfig("github.username");
        String embedModel = com.osscli.retrieval.Embeddings.MODEL;
        String guidanceModel = SqliteStorage.loadConfig("ollama.model.guidance");
        String drivePathsStr = SqliteStorage.loadConfig("drive.paths");

        // Load the last personal sync time from SQLite config
        String lastSyncedMe = SqliteStorage.loadConfig("developer.last_synced_at");
        Instant startRunTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        if (username == null || username.trim().isEmpty()) {
            LOGGER.error("No GitHub username configured. Please run 'setup' first.");
            return 1;
        }
        if (guidanceModel == null) {
            guidanceModel = com.osscli.Defaults.GUIDANCE_MODEL;
        }

        // --- PRE-FLIGHT VERIFICATION ---
        // Everything this command produces is a vector, so the embedder is the one hard requirement
        // -- and it is in this process, so the check is whether a file exists rather than whether a
        // daemon is up. It is still not fetched here: 22 MB arriving in the middle of a sync is the
        // surprise the tool promises not to spring.
        com.osscli.retrieval.LocalEmbedder embedder =
                com.osscli.retrieval.Embeddings.ifPresent(m -> LOGGER.info("  {}", m));
        if (embedder == null) {
            LOGGER.error("No local model, and 'sync --me' builds nothing but vectors.");
            LOGGER.error("  {}", com.osscli.retrieval.Embeddings.ABSENT_HINT);
            return 1;
        }

        // The guidance model writes the development stories, and it is the one part of this that
        // still wants Ollama. Optional, therefore: an absent daemon costs the narrative summaries
        // and nothing else, so it is reported once here rather than failing the whole sync.
        OllamaClient guideOllama = new OllamaClient(guidanceModel);
        // Asked for, not assumed. A daemon that happens to be running is not a request, so a plain
        // `oss sync --me` indexes everything and writes no narrative -- and says which it was.
        boolean engineNamed = com.osscli.llm.Ai.engines().contains(com.osscli.llm.Ai.Engine.OLLAMA);
        boolean canNarrate = engineNamed && guideOllama.isModelAvailable();
        if (!canNarrate) {
            if (!engineNamed) {
                LOGGER.info("  ○ Development stories skipped — no engine named ('oss llm sync --me' writes them).");
            } else {
                LOGGER.warn(
                        "  ⚠ Guidance model '{}' unavailable — development stories will be skipped.", guidanceModel);
                LOGGER.warn("    Everything else is indexed as usual. 'ollama pull {}' adds them.", guidanceModel);
            }
        }
        LOGGER.info("  ✔ Pre-flight verification successful (embedder '{}' ready).", embedModel);

        String searchQuery;
        if (lastSyncedMe != null && !lastSyncedMe.trim().isEmpty()) {
            // Incremental Delta Sync: Query only PRs merged since our last run
            searchQuery = String.format("author:%s type:pr is:merged merged:>=%s", username, lastSyncedMe);
            LOGGER.info(
                    "Starting incremental Personal Sync for '{}' (fetching changes merged since {})...",
                    username,
                    lastSyncedMe);
        } else {
            // Initial Full Sync: Query all merged PRs from the last 365 days
            String sinceDate = LocalDate.now().minusYears(1).toString() + "T00:00:00Z";
            searchQuery = String.format("author:%s type:pr is:merged created:>=%s", username, sinceDate);
            LOGGER.info("Starting initial Personal Sync for '{}' (Timeline: >= {})...", username, sinceDate);
        }

        LOGGER.info("Querying GitHub Search API for merged contributions...");

        GitHubClient client = new GitHubClient();
        List<Issue> mergedPrs = client.searchIssuesAndPrs(searchQuery);

        // A. Crawl Diffs and Generate PR Summaries
        if (mergedPrs.isEmpty()) {
            LOGGER.info("No new merged pull requests found since the last execution.");
        } else {
            LOGGER.info("  ↳ Found {} merged pull requests on GitHub.", mergedPrs.size());
            StringBuilder experienceDoc = new StringBuilder();

            for (Issue pr : mergedPrs) {
                LOGGER.info("  Processing PR #{}...", pr.number());
                experienceDoc.append("Title: ").append(pr.title()).append("\n");

                if (pr.body() != null) {
                    String trimmedBody = pr.body().trim();
                    if (trimmedBody.length() > 500) {
                        trimmedBody = trimmedBody.substring(0, 500) + "...";
                    }
                    experienceDoc.append("Body: ").append(trimmedBody).append("\n\n");
                }

                String sourceRepo = pr.getRepositoryOwnerAndName();
                if (sourceRepo == null) {
                    // Used to be impossible, because the model invented a repository rather than
                    // admit it could not tell. Skipping is the only correct move: the alternative
                    // is asking GitHub for this pull request's files in some other project.
                    LOGGER.warn("    ↳ [Skipped] PR #{} does not say which repository it is from.", pr.number());
                    continue;
                }
                String[] repoParts = sourceRepo.split("/");
                String owner = repoParts[0];
                String repoName = repoParts[1];
                List<String> modifiedFiles = new ArrayList<>();

                try {
                    modifiedFiles = client.getPullRequestFiles(owner, repoName, pr.number());
                    SqliteStorage.savePersonalCodeFootprint(sourceRepo, pr.number(), modifiedFiles);
                    LOGGER.info("    ↳ Logged {} modified file paths in '{}'.", modifiedFiles.size(), sourceRepo);
                } catch (Exception e) {
                    LOGGER.warn(
                            "    ↳ [Warning] Could not extract changed files from {}: {}", sourceRepo, e.getMessage());
                }

                // Generate PR Story Note if it does not exist in SQLite
                try {
                    if (canNarrate && !SqliteStorage.hasPersonalPrMemory(sourceRepo, pr.number())) {
                        LOGGER.info(
                                "    Generating automated Development Story for PR #{} using model '{}'...",
                                pr.number(),
                                guidanceModel);

                        String summaryPrompt =
                                String.format("""
                                You are an maintainer.
                                Summarize the following pull request as a personal development story.
                                Explain:
                                1. What problem was solved.
                                2. What files were changed and why.
                                3. What feedback was addressed during code review.

                                PR Title: %s
                                PR Description: %s
                                Files Changed: %s

                                Keep the story concise and technical.
                                """, pr.title(), pr.body(), String.join(", ", modifiedFiles));

                        String generatedStory = guideOllama.generateJson(summaryPrompt);
                        double[] storyVector = embedder.embed(generatedStory);

                        SqliteStorage.savePersonalPrMemory(
                                sourceRepo,
                                pr.number(),
                                String.join(", ", modifiedFiles),
                                generatedStory,
                                storyVector,
                                embedModel);
                        LOGGER.info("    ↳ Saved PR #{} Development Story to local SQLite memory.", pr.number());
                    }
                } catch (Exception e) {
                    LOGGER.warn(
                            "    ↳ [Warning] Could not generate AI development story for PR #{}: {}",
                            pr.number(),
                            e.getMessage());
                }
            }

            // Generate Semantic Developer Expertise Vector
            LOGGER.info("Generating semantic Developer Expertise Vector using model '{}'...", embedModel);
            try {
                double[] vector = embedder.embed(experienceDoc.toString().trim());
                String jsonVector = MAPPER.writeValueAsString(vector);
                SqliteStorage.saveConfig("developer.vector", jsonVector);
                // Provenance travels with it. The vector tables carry an embedding_model column for
                // this reason; this one lives in config, so the model is written beside it by hand.
                SqliteStorage.saveConfig("developer.vector.model", embedModel);
                LOGGER.info("  ↳ Personal Developer Expertise Vector successfully saved to SQLite.");
            } catch (Exception e) {
                LOGGER.error("  ↳ [Error] Failed to generate embedding vector: {}", e.getMessage());
                return 1;
            }
        }

        // B. Sync Google Drive AI Studio Logs (The Ingestion Engine - Always Runs)
        if (drivePathsStr != null && !drivePathsStr.trim().isEmpty()) {
            LOGGER.info("Scanning Google Drive paths recursively for AI Studio logs...");
            String[] paths = drivePathsStr.split(",");

            for (String path : paths) {
                java.nio.file.Path localPath = java.nio.file.Paths.get(path.trim());
                if (!java.nio.file.Files.exists(localPath)) {
                    LOGGER.warn("Google Drive directory does not exist locally: {}", localPath.toAbsolutePath());
                    continue;
                }

                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(localPath)) {
                    List<java.nio.file.Path> files = stream.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> {
                                String name = p.toString().toLowerCase();
                                return !name.endsWith(".png")
                                        && !name.endsWith(".pdf")
                                        && !name.endsWith(".zip")
                                        && !name.endsWith(".jpg")
                                        && !name.endsWith(".jpeg")
                                        && !name.endsWith(".gif")
                                        && !name.endsWith(".jar")
                                        && !name.endsWith(".ds_store")
                                        && !name.endsWith(".docx")
                                        && !name.endsWith(".class");
                            })
                            .toList();

                    LOGGER.info(
                            "  ↳ Found {} total active discussion files inside '{}' and its subfolders.",
                            files.size(),
                            localPath.getFileName());

                    for (java.nio.file.Path file : files) {
                        String fileName = file.getFileName().toString();
                        String absolutePath = file.toAbsolutePath().toString();
                        long lastModified;
                        byte[] fileBytes;

                        // Reading is its own failure, separate from embedding, and it must not end
                        // the folder. These files commonly live in a cloud-synced directory where
                        // the bytes are fetched on demand, so one file whose download stalls throws
                        // where every other file would have been fine. That exception used to
                        // escape to the per-directory catch below, abandoning every remaining file
                        // in the folder -- a partial index reported as a single timeout line, which
                        // is indistinguishable from a folder that was simply small.
                        try {
                            lastModified = java.nio.file.Files.getLastModifiedTime(file)
                                    .toMillis();
                            fileBytes = java.nio.file.Files.readAllBytes(file);
                        } catch (java.io.IOException e) {
                            LOGGER.warn("    ↳ [Warning] Could not read '{}': {}", fileName, e.getMessage());
                            LOGGER.warn("      Skipped; the rest of this folder continues. Re-run to pick it up.");
                            continue;
                        }
                        String rawContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);

                        // Scrub BEFORE anything else touches it. Everything downstream -- the
                        // cache comparison, the embedding, the passages, the stored row -- must
                        // see only redacted text, or a secret ends up encoded in a vector or
                        // written to disk even though the visible content looks clean.
                        com.osscli.util.Redactor.Result scrubbed = com.osscli.util.Redactor.redact(rawContent);
                        String content = scrubbed.text();
                        if (scrubbed.redactedAnything()) {
                            LOGGER.warn("    ⚠ Redacted from '{}': {}", fileName, scrubbed.summary());
                            redactionTally.merge(fileName, 1, Integer::sum);
                            scrubbed.counts().forEach((k, v) -> redactionTotals.merge(k, v, Integer::sum));
                        }

                        // Clean Content-Based Comparison
                        String cachedContent = SqliteStorage.loadPersonalChatContent(absolutePath);

                        // A cached vector is only reusable if the SAME model produced it.
                        // Swapping embedding models (or a row written before provenance was
                        // tracked, where the model reads null) leaves vectors that cannot be
                        // compared with newly written ones, so force a re-embed instead.
                        String cachedModel = SqliteStorage.loadPersonalChatEmbeddingModel(absolutePath);
                        boolean modelChanged = cachedContent != null && !embedModel.equals(cachedModel);
                        if (modelChanged) {
                            LOGGER.info(
                                    "    Embedding model changed ({} -> {}) — re-embedding '{}'...",
                                    cachedModel == null ? "unknown" : cachedModel,
                                    embedModel,
                                    fileName);
                        }

                        // A note cached before passage-level embedding existed has content and a
                        // note-level vector but no passages, so the content check alone would skip
                        // it forever and the chunk table would stay empty.
                        boolean passagesMissing =
                                cachedContent != null && SqliteStorage.countPersonalChatChunks(absolutePath) == 0;
                        if (passagesMissing) {
                            LOGGER.info("    No passages indexed for '{}' — building them...", fileName);
                        }

                        if (cachedContent == null
                                || !content.equals(cachedContent)
                                || modelChanged
                                || passagesMissing) {
                            LOGGER.info("    Ingesting new or modified chat log: {}...", fileName);

                            // 1. SMART JSON PARSER FOR CHATGPT / CLAUDE EXPORTS
                            if (fileName.endsWith(".json")) {
                                try {
                                    JsonNode root = MAPPER.readTree(fileBytes);
                                    if (root.isArray()) {
                                        LOGGER.info(
                                                "      ↳ Detected JSON Export Array. Splitting into individual memories...");
                                        for (int i = 0; i < root.size(); i++) {
                                            JsonNode chatNode = root.get(i);
                                            String title = chatNode.has("title")
                                                    ? chatNode.get("title").asText()
                                                    : fileName + "_Part_" + i;
                                            // This branch reads the JSON node directly rather than
                                            // the file text redacted above, so it must scrub too.
                                            com.osscli.util.Redactor.Result nodeScrubbed =
                                                    com.osscli.util.Redactor.redact(chatNode.toString());
                                            if (nodeScrubbed.redactedAnything()) {
                                                LOGGER.warn(
                                                        "      ⚠ Redacted from '{}': {}",
                                                        title,
                                                        nodeScrubbed.summary());
                                                nodeScrubbed
                                                        .counts()
                                                        .forEach((k, v) -> redactionTotals.merge(k, v, Integer::sum));
                                            }
                                            String chatContent = nodeScrubbed.text();
                                            // Truncate massively long chats to fit into embedding context window
                                            if (chatContent.length() > 5000) {
                                                chatContent = chatContent.substring(chatContent.length() - 5000);
                                            }

                                            double[] chatVector = embedder.embed(chatContent);
                                            String subFileName = title.replaceAll("[^a-zA-Z0-9-_]", "_") + ".json";
                                            // Save chunk with uniquely appended hash path
                                            SqliteStorage.savePersonalChatMemory(
                                                    absolutePath + "#" + i,
                                                    subFileName,
                                                    lastModified,
                                                    chatContent,
                                                    chatVector,
                                                    embedModel);
                                        }
                                        LOGGER.info(
                                                "      ↳ Successfully indexed {} historical JSON conversations.",
                                                root.size());
                                        continue;
                                    }
                                } catch (Exception e) {
                                    LOGGER.warn(
                                            "      ↳ Not a valid JSON Array, falling back to standard text ingestion.");
                                }
                            }

                            // 2. STANDARD TEXT PARSER (For Markdown / TXT or non-array JSON)
                            try {
                                double[] chatVector = embedder.embed(content);
                                SqliteStorage.savePersonalChatMemory(
                                        absolutePath, fileName, lastModified, content, chatVector, embedModel);

                                // Passage-level embeddings. The note-level vector above only ever
                                // describes the note's opening, because the embedder truncates at
                                // its input window; these make the whole note reachable.
                                java.util.List<String> passages = com.osscli.retrieval.PassageSplitter.split(content);
                                java.util.List<double[]> passageVectors = new java.util.ArrayList<>();
                                for (String passage : passages) {
                                    passageVectors.add(embedder.embed(passage));
                                }
                                SqliteStorage.savePersonalChatChunks(
                                        absolutePath, passages, passageVectors, embedModel);
                                LOGGER.info("    ↳ Indexed '{}' as {} passage(s).", fileName, passages.size());
                            } catch (Exception e) {
                                LOGGER.warn(
                                        "    ↳ [Warning] Could not generate embedding for '{}': {}",
                                        fileName,
                                        e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error(
                            "Failed to scan Google Drive directory recursively '{}': {}",
                            localPath.toAbsolutePath(),
                            e.getMessage());
                }
            }
        } else {
            LOGGER.info("No Google Drive paths configured for AI log ingestion. Skipping this step.");
        }

        // C. Update the sync timestamp in SQLite on success
        SqliteStorage.saveConfig("developer.last_synced_at", startRunTime.toString());

        // Report the scrub loudly. A silent redaction is worse than none: the user needs to
        // know a credential was in their source material, because removing it here does not
        // un-expose it -- only rotating it does.
        if (!redactionTotals.isEmpty()) {
            LOGGER.warn("");
            LOGGER.warn("REDACTED during ingest ({} file(s) affected):", redactionTally.size());
            redactionTotals.forEach((label, n) -> LOGGER.warn("   {}x  {}", n, label));
            LOGGER.warn("These credentials were present in your SOURCE files. Removing them here");
            LOGGER.warn("does not revoke them — rotate anything real that appears above.");
            LOGGER.warn("");
        }

        LOGGER.info(
                "Personal Sync completed successfully. Your complete developer footprint and AI logs are cached locally!");
        return 0;
    }
}
