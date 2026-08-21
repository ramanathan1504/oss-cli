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
package com.osscli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The transcripts already on this machine.
 *
 * <p>Claude Code, codex and gemini each write every session to disk and keep it. That is the half of
 * the record that never touches GitHub and never appears in an export: the reasoning, the wrong turn
 * taken first, the command that finally worked. {@code harvest} fetched none of it — and the class
 * documentation said it did, which is worse than not having the feature, because a reader who
 * believed it never went looking.
 *
 * <p><b>Budgeted, because the last thing that read a corpus was not.</b> A single session file here
 * reaches several megabytes and there are hundreds of them. {@code digest} learned this by producing
 * a 23 MB document; the rule is the same one {@code MemoryContext} follows — take the most, say what
 * was left, never render everything and hope.
 *
 * <p><b>Nothing is uploaded and nothing is deleted.</b> These files belong to the tool that wrote
 * them. This reads them, redacts what looks like a secret, and writes markdown of its own.
 */
public final class Sessions {

    /** Turns kept from one session. Past this, a transcript is a log, not a note. */
    static final int MAX_TURNS = 30;

    /** Characters kept from one turn. A pasted stack trace is not the thing worth remembering. */
    static final int MAX_TURN_CHARS = 1_200;

    /** Sessions read in one harvest, newest first, so a first run on a big machine still finishes. */
    static final int MAX_SESSIONS = 200;

    /** Below this a transcript is a session someone opened and closed. */
    static final long MIN_BYTES = 2_048;

    private Sessions() {}

    /** One transcript, reduced to the part worth keeping. */
    public record Session(String tool, String id, Path file, String when, List<String> turns) {

        public boolean worthKeeping() {
            return !turns.isEmpty();
        }
    }

    /**
     * Where each tool keeps its sessions, under a given home.
     *
     * <p>Takes the home rather than reading the property, so a test can point it at a fixture
     * directory instead of asserting against whatever happens to be on the machine running it.
     */
    public static List<Path> roots(Path home) {
        return List.of(
                home.resolve(".claude").resolve("projects"),
                home.resolve(".codex").resolve("sessions"),
                home.resolve(".gemini").resolve("tmp"));
    }

    /** Which tool wrote a file, from where it sits. Unknown paths are not guessed at. */
    public static String toolOf(Path file) {
        String p = file.toString().replace('\\', '/');
        if (p.contains("/.claude/")) {
            return "claude-code";
        }
        if (p.contains("/.codex/")) {
            return "codex";
        }
        if (p.contains("/.gemini/")) {
            return "gemini";
        }
        return "unknown";
    }

    /**
     * Every transcript worth opening, newest first.
     *
     * <p>Newest first and capped, because the alternative on a machine with a year of sessions is a
     * command that appears to hang. Whatever the cap drops is counted and reported by the caller —
     * a silent cap is the bug this repository just spent a day removing from the GitHub search.
     */
    public static List<Path> discover(Path home) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path root : roots(home)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(Sessions::looksLikeTranscript)
                        .filter(Sessions::bigEnough)
                        .forEach(found::add);
            }
        }
        found.sort((a, b) -> Long.compare(modified(b), modified(a)));
        return found;
    }

    static boolean looksLikeTranscript(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        // .jsonl is Claude Code and codex; gemini writes one .json per chat under chats/.
        return name.endsWith(".jsonl")
                || (name.startsWith("session-") && name.endsWith(".json"))
                || (name.endsWith(".json") && p.toString().replace('\\', '/').contains("/chats/"));
    }

    private static boolean bigEnough(Path p) {
        try {
            return Files.size(p) >= MIN_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Read one transcript into the turns worth keeping.
     *
     * <p>Returns a session with no turns rather than throwing when a file cannot be parsed. These
     * formats belong to three other programs and change without notice; one unreadable transcript
     * must cost that transcript, not the harvest.
     */
    public static Session read(Path file) {
        String tool = toolOf(file);
        String id = file.getFileName().toString().replaceAll("\\.jsonl?$", "");
        List<String> turns = new ArrayList<>();
        String when = "";
        try {
            if (file.getFileName().toString().endsWith(".jsonl")) {
                ObjectMapper mapper = new ObjectMapper();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || turns.size() >= MAX_TURNS) {
                        continue;
                    }
                    JsonNode node;
                    try {
                        node = mapper.readTree(line);
                    } catch (IOException bad) {
                        // A truncated last line is normal in a file still being written to.
                        continue;
                    }
                    if (when.isEmpty()) {
                        when = node.path("timestamp").asText("");
                    }
                    String turn = turnOf(node);
                    if (turn != null) {
                        turns.add(turn);
                    }
                }
            } else {
                JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
                when = root.path("startTime").asText("");
                for (JsonNode m : root.path("messages")) {
                    if (turns.size() >= MAX_TURNS) {
                        break;
                    }
                    String turn = turnOf(m);
                    if (turn != null) {
                        turns.add(turn);
                    }
                }
            }
        } catch (IOException e) {
            return new Session(tool, id, file, when, List.of());
        }
        return new Session(tool, id, file, when, turns);
    }

    /**
     * One entry of a transcript as a line of prose, or null when it carries none.
     *
     * <p>Only the human's words and the model's prose. Tool calls, results and attachments are the
     * bulk of a transcript by size and none of it is what anyone wants to read a year later — and
     * tool output is where the file contents, keys and command lines are.
     */
    static String turnOf(JsonNode node) {
        String type = node.path("type").asText("");
        boolean fromUser = "user".equals(type);
        // Three tools, three names for the same speaker. gemini writes "gemini", the Gemini API
        // writes "model", Claude Code and codex write "assistant" -- and a reader that knew only
        // the last of those dropped every gemini answer on the floor while still counting the
        // file as read. Found by running it: one real transcript, zero turns, reported as "no
        // prose worth keeping".
        boolean fromAssistant = "assistant".equals(type) || "gemini".equals(type) || "model".equals(type);
        if (!fromUser && !fromAssistant) {
            return null;
        }
        JsonNode content = node.has("message") ? node.path("message").path("content") : node.path("content");
        String text = textOf(content);
        if (text.isBlank()) {
            return null;
        }
        String clipped = text.strip();
        if (clipped.length() > MAX_TURN_CHARS) {
            clipped = clipped.substring(0, MAX_TURN_CHARS) + "…";
        }
        return (fromUser ? "**you:** " : "**assistant:** ") + clipped.replace("\n", "\n> ");
    }

    /** Content is a string in one shape and an array of typed blocks in the other. Both happen. */
    static String textOf(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block.isTextual()) {
                sb.append(block.asText()).append('\n');
            } else if (block.hasNonNull("text")) {
                // Keyed off the field, not off a "type" label beside it: gemini's blocks are a
                // bare {"text": "..."} with no type at all, so a check for type == "text" read
                // every one of them as empty.
                sb.append(block.path("text").asText()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * A stable file name for one session.
     *
     * <p>Stable so a second harvest rewrites the note it already wrote. Timestamping instead is how
     * one review ended up in an archive six times, each copy embedded and each competing to answer
     * the same question.
     */
    public static String nameFor(Session s) {
        String id = s.id().replaceAll("[^A-Za-z0-9._-]", "-");
        return "session-" + s.tool() + "-" + id + ".md";
    }

    /** The note, with secrets replaced rather than the passage dropped. */
    public static String noteFor(Session s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(s.tool()).append(" session ").append(s.id()).append("\n\n");
        sb.append("- tool: ").append(s.tool()).append('\n');
        if (!s.when().isBlank()) {
            sb.append("- started: ").append(s.when()).append('\n');
        }
        sb.append("- transcript: ").append(s.file()).append('\n');
        sb.append("\n## What was said\n\n");
        for (String turn : s.turns()) {
            sb.append("> ").append(turn).append("\n\n");
        }
        if (s.turns().size() >= MAX_TURNS) {
            sb.append("_First ").append(MAX_TURNS).append(" turns. The whole transcript is at the path above._\n");
        }
        return com.osscli.util.Redactor.redact(sb.toString()).text();
    }
}
