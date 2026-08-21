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
package com.osscli.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.storage.SqliteStorage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Turning notes into vectors — the step that decides whether writing something down was any use.
 *
 * <p>This lived inside {@code sync --me} and nowhere else, which is the whole reason it is here
 * now. {@code memory harvest} writes markdown; so does {@code memory file}, so does an archive
 * extension, so does a Claude Code session filing a PR review. None of it reaches {@code chat},
 * {@code guide}, {@code pick} or {@code prompt} until this runs — and the daily job runs
 * {@code harvest} and stops. Measured on a real store: 23 PR reviews on disk, 19 embedded, and the
 * four newest — the ones you would actually ask about — invisible to every command that answers.
 *
 * <p>So the code moved rather than being copied. Two implementations of "read a folder of notes,
 * scrub it, embed it, store the passages" is precisely the kind of second copy this repository has
 * paid for before: the redaction rule, the empty-note rule and the model-provenance rule would each
 * have to be got right twice.
 */
public final class NoteIndexer {

    private static final Logger LOGGER = LogManager.getLogger(NoteIndexer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NoteIndexer() {}

    /** What an indexing run scrubbed, so the caller can report it in its own words. */
    public record Result(Map<String, Integer> byFile, Map<String, Integer> byKind) {

        public boolean redactedAnything() {
            return !byKind.isEmpty();
        }
    }

    /**
     * Index every note under each folder.
     *
     * <p>Unchanged behaviour, moved: a file is skipped if it is binary by extension, unreadable,
     * blank, or already stored with the same content and the same embedding model. Secrets are
     * scrubbed before anything downstream sees the text, because a vector built from an unredacted
     * note carries the secret into the index.
     */
    public static Result index(List<String> folders, LocalEmbedder embedder, String embedModel) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        LOGGER.info("Scanning your note folders ({}) recursively...", folders.size());
        List<String> paths = folders;

        for (String path : paths) {
            java.nio.file.Path localPath = java.nio.file.Paths.get(path.trim());
            if (!java.nio.file.Files.exists(localPath)) {
                LOGGER.warn("Note folder does not exist locally: {}", localPath.toAbsolutePath());
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
                        lastModified =
                                java.nio.file.Files.getLastModifiedTime(file).toMillis();
                        fileBytes = java.nio.file.Files.readAllBytes(file);
                    } catch (java.io.IOException e) {
                        LOGGER.warn("    ↳ [Warning] Could not read '{}': {}", fileName, e.getMessage());
                        LOGGER.warn("      Skipped; the rest of this folder continues. Re-run to pick it up.");
                        continue;
                    }
                    String rawContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);

                    // An empty note is not a note. Embedding one produces the vector of the
                    // empty string -- a fixed value, identical for every such file -- so a
                    // handful of 0-byte placeholders become a cluster that matches every query
                    // at the same score and crowds real passages out of the results. Six of
                    // them were sitting in a real corpus, indistinguishable in the table from
                    // notes with something in them.
                    //
                    // ResolutionWriter already refuses to file a blank note. This is the same
                    // rule on the way in.
                    if (!worthIndexing(rawContent)) {
                        LOGGER.debug("    ↳ Skipping '{}': no content to index.", fileName);
                        continue;
                    }

                    // Scrub BEFORE anything else touches it. Everything downstream -- the
                    // cache comparison, the embedding, the passages, the stored row -- must
                    // see only redacted text, or a secret ends up encoded in a vector or
                    // written to disk even though the visible content looks clean.
                    com.osscli.util.Redactor.Result scrubbed = com.osscli.util.Redactor.redact(rawContent);
                    String content = scrubbed.text();
                    if (scrubbed.redactedAnything()) {
                        LOGGER.warn("    ⚠ Redacted from '{}': {}", fileName, scrubbed.summary());
                        tally.merge(fileName, 1, Integer::sum);
                        scrubbed.counts().forEach((k, v) -> totals.merge(k, v, Integer::sum));
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

                    if (cachedContent == null || !content.equals(cachedContent) || modelChanged || passagesMissing) {
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
                                                    "      ⚠ Redacted from '{}': {}", title, nodeScrubbed.summary());
                                            nodeScrubbed.counts().forEach((k, v) -> totals.merge(k, v, Integer::sum));
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
                                LOGGER.warn("      ↳ Not a valid JSON Array, falling back to standard text ingestion.");
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
                            SqliteStorage.savePersonalChatChunks(absolutePath, passages, passageVectors, embedModel);
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
                        "Failed to scan note folder recursively '{}': {}", localPath.toAbsolutePath(), e.getMessage());
            }
        }
        return new Result(tally, totals);
    }

    /**
     * Whether a file has anything worth embedding.
     *
     * <p>The vector of an empty string is a fixed value, so every empty note embeds to the same
     * point and the whole set matches any query at one score. Six 0-byte files were doing exactly
     * that in a real corpus, sitting in the table looking like notes.
     */
    public static boolean worthIndexing(String content) {
        return content != null && !content.isBlank();
    }
}
