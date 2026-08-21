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
package com.osscli.knowledge;

import com.osscli.AppPaths;
import com.osscli.retrieval.PassageSplitter;
import com.osscli.storage.SqliteStorage;
import com.osscli.util.Redactor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Writes a finished investigation back into the knowledge base as an indexed note.
 *
 * <p>This is the return arc of the retrieval loop. Without it the tool reads from its corpus but never adds to it:
 * every answer, whether produced locally or by an escalated cloud model, was displayed once and discarded, so the same
 * question cost the same work every time and nothing the user had already solved was ever retrievable.
 *
 * <p>The note is written to disk AND embedded in the same step, deliberately. A file that exists but has no vector is
 * invisible to retrieval, which is indistinguishable from not having saved it at all -- the failure would only surface
 * much later, as an answer that should have been found and was not.
 */
public final class ResolutionWriter {

    private static final Logger LOGGER = LogManager.getLogger(ResolutionWriter.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Subfolder recording which tool produced the note.
     *
     * <p>Filing is topic first, provenance second: the topic is what someone browses for, while the source only says
     * what kind of evidence a note is. Keeping this tool's output in its own folder means an archive shared with other
     * capture tools stays sortable, and a bad run can be removed without touching hand-written notes.
     */
    private static final String PROVENANCE_DIR = "oss-cli";

    private ResolutionWriter() {}

    /**
     * Records a resolution and indexes it for future retrieval.
     *
     * <p>Never throws. A failure here must not discard an answer the user is already reading on screen, nor fail the
     * command that produced it -- the answer has value even when archiving it does not work.
     *
     * @param source what produced the answer, e.g. {@code "ollama"} or a cloud provider name
     * @return the file written, or null if the note could not be recorded
     */
    public static Path record(
            String repository, long issueNumber, String issueTitle, String source, String question, String answer) {
        return record(repository, issueNumber, issueTitle, source, question, answer, PROVENANCE_DIR, "resolution");
    }

    /**
     * As {@link #record}, but filed under a caller-chosen provenance folder and file label.
     *
     * <p>Reviews belong beside hand-written reviews, not beside issue resolutions: an archive already keeping
     * {@code pr-reviews/} should receive generated reviews there rather than in a second folder that splits the same
     * kind of material across two places.
     */
    public static Path record(
            String repository,
            long issueNumber,
            String issueTitle,
            String source,
            String question,
            String answer,
            String provenanceDir,
            String label) {
        return record(repository, issueNumber, issueTitle, source, question, answer, provenanceDir, label, null);
    }

    /**
     * The note this material already has, or null when there is none.
     *
     * <p>A chat keeps its own path in {@code chat_session.note_path}, because a conversation is a
     * session with an identity to hang it on. A review has none: it is one command, run again
     * whenever somebody wants a fresh opinion on the same pull request — so the note is found where
     * this writer would have put it rather than remembered.
     *
     * <p>Without this, every re-review filed another copy. Six notes accumulated for one pull
     * request, three of them for the same head commit and four of them superseded, each embedded
     * and each competing to answer the same question. That is the duplication the chat note was
     * changed to avoid, arriving through a different door.
     */
    public static Path existingNote(String repository, long issueNumber, String provenanceDir, String label) {
        try {
            Path dir = resolveTopicDir(repository).resolveSibling(provenanceDir);
            if (!Files.isDirectory(dir)) {
                return null;
            }
            String prefix = String.format("Issue-%d-%s-", issueNumber, label);
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                return files.filter(f -> f.getFileName().toString().startsWith(prefix))
                        .filter(f -> f.getFileName().toString().endsWith(".md"))
                        // Newest by name: the stamp is the rest of the file name, and it sorts.
                        .max(java.util.Comparator.comparing(f -> f.getFileName().toString()))
                        .orElse(null);
            }
        } catch (Exception e) {
            // Not finding the old note is not a reason to lose the new one; the caller files a
            // fresh copy, which is the behaviour this replaced.
            LOGGER.debug("could not look for an existing {} note: {}", label, e.getMessage());
            return null;
        }
    }

    /**
     * As {@link #record}, but rewriting {@code reuse} when a note for this material already exists.
     *
     * <p>A resumable chat is written out every time it ends, and it can end several times: talk for
     * ten minutes, stop, resume the next morning, stop again. Filing a fresh timestamped note each
     * time would leave three overlapping copies of one conversation, each a superset of the last,
     * all of them embedded and all of them competing to answer the same question. Retrieval already
     * has to work around duplicate notes; it should not be handed more on purpose.
     *
     * <p>So the caller keeps the path it was given and passes it back, and the same file is
     * rewritten in place with the fuller transcript.
     */
    public static Path record(
            String repository,
            long issueNumber,
            String issueTitle,
            String source,
            String question,
            String answer,
            String provenanceDir,
            String label,
            Path reuse) {

        if (answer == null || answer.isBlank()) {
            return null;
        }

        try {
            Path dir = resolveTopicDir(repository).resolveSibling(provenanceDir);
            Files.createDirectories(dir);

            // Reuse only a file still sitting where this writer would put it. A note the user moved
            // or deleted is their decision, and rewriting a path outside the archive on the strength
            // of a stored string is how a tool ends up writing somewhere nobody expected.
            Path file;
            if (reuse != null && reuse.getParent() != null && reuse.getParent().equals(dir) && Files.exists(reuse)) {
                file = reuse;
            } else {
                String fileName = String.format(
                        "Issue-%d-%s-%s.md",
                        issueNumber, label, LocalDateTime.now().format(STAMP));
                file = dir.resolve(fileName);
            }

            String body = buildNote(repository, issueNumber, issueTitle, source, question, answer);

            // The transcript can carry a key pasted into a prompt, and this writes to both disk and the database.
            Redactor.Result scrubbed = Redactor.redact(body);
            if (scrubbed.redactedAnything()) {
                LOGGER.warn("  ⚠ Redacted from this resolution note: {}", scrubbed.summary());
                LOGGER.warn("    Removing them here does not revoke them — rotate anything real.");
            }
            String content = scrubbed.text();

            Files.writeString(file, content, StandardCharsets.UTF_8);

            // Said separately because they are separate outcomes. Reporting "recorded and indexed" after the
            // indexing half was skipped is the exact shape of the failure this class exists to prevent: the loop
            // looks closed, and only much later does an answer that should have been found fail to appear.
            if (index(file, content)) {
                LOGGER.info("  ✔ {} recorded and indexed → {}", capitalize(label), file.toAbsolutePath());
            } else {
                LOGGER.info("  ✔ {} recorded → {}", capitalize(label), file.toAbsolutePath());
                LOGGER.info("    Not yet indexed, so it will not come back in a search until it is.");
            }
            return file;

        } catch (Exception e) {
            LOGGER.warn("  ⚠ Could not record this resolution: {}", e.getMessage());
            LOGGER.warn("    The answer above is unaffected, but it will not be searchable later.");
            return null;
        }
    }

    /**
     * Embeds the note so the next question can retrieve it. Skipped with a warning when the model is absent.
     *
     * <p>This is the return arc of the loop, and the one step whose absence is invisible: the answer is still
     * printed and the file is still written, so nothing looks wrong -- it simply never becomes part of what the
     * next question can find. So a skip says so, and says what recovers it. The note on disk is the durable
     * record; indexing is replayable from it at any later point.
     */
    private static boolean index(Path file, String content) throws Exception {
        String embedModel = com.osscli.retrieval.Embeddings.MODEL;

        com.osscli.retrieval.LocalEmbedder embedder =
                com.osscli.retrieval.Embeddings.ifPresent(m -> LOGGER.info("  {}", m));
        if (embedder == null) {
            LOGGER.warn("  ⚠ No local model — the note was saved but is not yet searchable.");
            LOGGER.warn("    {}", com.osscli.retrieval.Embeddings.ABSENT_HINT);
            LOGGER.warn("    Then 'sync --me' indexes it, along with anything else written meanwhile.");
            return false;
        }

        String path = file.toAbsolutePath().toString();
        long modified = Files.getLastModifiedTime(file).toMillis();

        SqliteStorage.savePersonalChatMemory(
                path, file.getFileName().toString(), modified, content, embedder.embed(content), embedModel);

        // Chunk as well as whole-document: retrieval ranks passages, so a long note that matches in one paragraph
        // would otherwise be diluted by the rest of its own text and lose to a shorter, weaker match.
        List<String> passages = PassageSplitter.split(content);
        List<double[]> vectors = new ArrayList<>(passages.size());
        for (String passage : passages) {
            vectors.add(embedder.embed(passage));
        }
        SqliteStorage.savePersonalChatChunks(path, passages, vectors, embedModel);
        return true;
    }

    private static String buildNote(
            String repository, long issueNumber, String issueTitle, String source, String question, String answer) {

        StringBuilder sb = new StringBuilder();
        sb.append("# ")
                .append(repository)
                .append(" — Issue #")
                .append(issueNumber)
                .append('\n');
        if (issueTitle != null && !issueTitle.isBlank()) {
            sb.append("## ").append(issueTitle).append('\n');
        }
        sb.append('\n');
        sb.append("- Repository: ").append(repository).append('\n');
        sb.append("- Issue: #").append(issueNumber).append('\n');
        sb.append("- Answered by: ").append(source == null ? "unknown" : source).append('\n');
        sb.append("- Recorded: ").append(LocalDateTime.now()).append('\n');
        sb.append("\n---\n\n## Resolution\n\n").append(answer).append('\n');

        if (question != null && !question.isBlank()) {
            sb.append("\n---\n\n## Context supplied\n\n").append(question).append('\n');
        }
        return sb.toString();
    }

    /**
     * Chooses the folder for a repository's notes, preferring one the user already keeps.
     *
     * <p>An existing folder wins whenever its name and the repository's name contain one another, so
     * {@code apache/logging-log4j2} lands in a {@code log4j/} folder that is already there rather than starting a
     * near-duplicate beside it. Matching against whatever the archive happens to contain keeps this working for any
     * naming scheme without a built-in table of repositories, which would only ever fit one user's archive.
     */
    private static Path resolveTopicDir(String repository) throws java.sql.SQLException {
        String slug = topicSlug(repository);
        Path root = resolveNotesRoot();

        Path existing = findExistingTopic(root, slug);
        return (existing == null ? root.resolve(slug) : existing).resolve(PROVENANCE_DIR);
    }

    private static Path findExistingTopic(Path root, String slug) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !name.startsWith(".")
                                && !name.startsWith("_")
                                && (name.contains(slug) || slug.contains(name));
                    })
                    // Longest name is the most specific match: prefer "spring-boot" over "spring" when both exist.
                    .max((a, b) -> Integer.compare(
                            a.getFileName().toString().length(),
                            b.getFileName().toString().length()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The repository name without its owner, lowercased — {@code apache/logging-log4j2} becomes {@code logging-log4j2}. */
    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "Note" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String topicSlug(String repository) {
        String name = repository == null ? "" : repository;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        name = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return name.isBlank() ? "misc" : name;
    }

    /**
     * Where notes are filed: an explicit setting, else the first configured note folder, else a directory beside the
     * database.
     *
     * <p>The local fallback matters because the loop must still close for a user who keeps no external archive. Writing
     * nothing in that case would silently make the tool read-only for exactly the people who have not set anything up.
     */
    private static Path resolveNotesRoot() throws java.sql.SQLException {
        String configured = SqliteStorage.loadConfig("notes.resolutions_path");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }

        String drivePaths = SqliteStorage.loadConfig("drive.paths");
        if (drivePaths != null && !drivePaths.isBlank()) {
            for (String candidate : drivePaths.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty() && Files.isDirectory(Path.of(trimmed))) {
                    return Path.of(trimmed);
                }
            }
        }
        return AppPaths.BASE_DIR.resolve("resolutions");
    }
}
