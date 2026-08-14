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

import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import com.osscli.storage.ChatSessionStore;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Turns a conversation into the one line you recognise it by, and folds the old part of it away
 * when it gets too long to send.
 *
 * <p>Both jobs are better with a language model and neither may require one. A history listing that
 * is blank until somebody installs Ollama is a history listing that does not work, so the fallback
 * here is not an apology -- it is the floor, and it is what most users will see. What the model adds
 * is phrasing, not the feature.
 */
public final class SessionDigest {

    private static final Logger LOGGER = LogManager.getLogger(SessionDigest.class);

    /** Long enough to be recognisable in a list, short enough to leave room for the columns beside it. */
    private static final int OVERVIEW_MAX = 72;

    /**
     * The whole prompt, not the transcript alone.
     *
     * <p>Small local models commonly run an 8k-token window, which is roughly this many characters
     * of English. Overridable with {@code chat.context.chars} for a model with more room.
     */
    private static final int PROMPT_BUDGET_CHARS = 32_000;

    /** Left unspent so the model has somewhere to write its answer. */
    private static final int RESERVED_FOR_ANSWER_CHARS = 4_000;

    /** How much of the tail is kept verbatim when folding. The recent turns are the ones being worked on. */
    private static final int KEEP_TAIL_CHARS = 6_000;

    private SessionDigest() {}

    // ==========================================
    // Overview
    // ==========================================

    /**
     * The line {@code oss history} shows for a session.
     *
     * <p>Never throws and never blocks for long: this runs once per session in a list that may hold
     * fifty of them, so a model that is slow or absent costs a plainer line, not a hung command.
     */
    public static String overview(ChatSession session, List<ChatTurn> turns) {
        String extractive = extractiveOverview(session, turns);
        String generated = generatedOverview(turns);
        return generated == null ? extractive : generated;
    }

    /**
     * The honest fallback: what you actually asked first.
     *
     * <p>It is not a summary and does not pretend to be. In practice the opening question is what
     * people remember a conversation by, so this is right far more often than its cost suggests.
     */
    public static String extractiveOverview(ChatSession session, List<ChatTurn> turns) {
        for (ChatTurn t : turns) {
            if (t.role() == ChatTurn.Role.USER
                    && t.content() != null
                    && !t.content().isBlank()) {
                return clip(firstSentence(t.content()));
            }
        }
        if (session != null
                && session.issueTitle() != null
                && !session.issueTitle().isBlank()) {
            return clip(session.issueTitle());
        }
        return "(no question yet)";
    }

    /** Asks the attached model for a better line. Returns null whenever there isn't one, or it misbehaves. */
    private static String generatedOverview(List<ChatTurn> turns) {
        if (turns.isEmpty()) {
            return null;
        }
        try {
            com.osscli.llm.OllamaClient client = guidanceClient();
            if (client == null) {
                return null;
            }
            String prompt = """
                    Summarise what this conversation was about in ONE line of at most 12 words.
                    No preamble, no quotes, no trailing full stop. Output the line and nothing else.

                    %s
                    """.formatted(clipTo(ChatSessionStore.transcript(null, turns), 6_000));

            String raw = client.generateText(prompt);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String line = raw.strip().lines().findFirst().orElse("").strip();
            // Small models like to answer a request for one line with a preamble and then the line.
            line = line.replaceFirst("^(?i)(sure|here'?s?|summary)\\b[:,-]?\\s*", "")
                    .replaceAll("^[\"'`]+|[\"'`.]+$", "")
                    .strip();
            return line.isBlank() ? null : clip(line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // A missing or unreachable model is the normal case, not an error worth a line on screen.
            LOGGER.debug("No generated overview: {}", e.getMessage());
            return null;
        }
    }

    // ==========================================
    // Compaction
    // ==========================================

    /** How many characters the transcript itself occupies, summary included. */
    public static int used(ChatSession session, List<ChatTurn> turns) {
        return ChatSessionStore.transcript(session, turns).length();
    }

    /**
     * How much room the transcript actually has, given what else is in the prompt.
     *
     * <p>This used to be a bare constant, and that was the defect: the transcript folded at 16,000
     * characters while the retrieved notes independently spent up to 6,000 tokens beside it. Two
     * budgets that do not know about each other are not a budget, and together they could still
     * overflow the window neither of them had exceeded alone.
     *
     * <p>Never returns less than {@link #KEEP_TAIL_CHARS}: the tail is kept verbatim whatever
     * happens, so a floor below it would only promise a fold that cannot be performed.
     */
    public static int budgetChars(int otherPromptChars) {
        int whole = configuredInt("chat.context.chars", PROMPT_BUDGET_CHARS);
        return Math.max(KEEP_TAIL_CHARS, whole - RESERVED_FOR_ANSWER_CHARS - Math.max(0, otherPromptChars));
    }

    /**
     * True once the transcript no longer fits beside the rest of the prompt.
     *
     * @param otherPromptChars everything else being sent -- the issue, the retrieved notes, the
     *     instructions. Pass 0 when the transcript is genuinely all there is.
     */
    public static boolean needsCompaction(ChatSession session, List<ChatTurn> turns, int otherPromptChars) {
        return used(session, turns) > budgetChars(otherPromptChars);
    }

    /** As above, for callers with nothing else in the prompt to declare. */
    public static boolean needsCompaction(ChatSession session, List<ChatTurn> turns) {
        return needsCompaction(session, turns, 0);
    }

    /** A config integer, falling back to the default for anything missing or unreadable. */
    private static int configuredInt(String key, int fallback) {
        String raw;
        try {
            raw = SqliteStorage.loadConfig(key);
        } catch (java.sql.SQLException e) {
            return fallback;
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            LOGGER.warn("  ⚠ {} is set to '{}', which is not a number. Using {}.", key, raw, fallback);
            return fallback;
        }
    }

    /**
     * Folds everything but the recent tail into prose, and says which way it went.
     *
     * <p>Two outcomes and they are not the same, so they do not print the same. With a model, the
     * older turns become a summary that still carries what was decided. Without one, they are
     * dropped -- and that is said out loud, because silently forgetting the first half of somebody's
     * conversation while continuing to answer confidently is precisely the failure this repository
     * keeps warning about.
     *
     * @return the turns to keep sending verbatim; the fold itself is written to the session
     */
    public static List<ChatTurn> compact(ChatSession session, List<ChatTurn> turns) {
        List<ChatTurn> tail = tail(turns);
        List<ChatTurn> older = turns.subList(0, turns.size() - tail.size());
        if (older.isEmpty()) {
            // Everything is already inside the tail. Either the conversation is short -- in which
            // case there is simply nothing to do -- or one single turn is larger than the whole
            // budget, which folding cannot fix and which the user should hear about rather than
            // watch fail as a timeout.
            if (turns.size() == 1 && used(session, turns) > budgetChars(0)) {
                LOGGER.warn("  ⚠ A single turn is larger than the whole context budget.");
                LOGGER.warn("    Folding cannot shrink it. Raise chat.context.chars, or start a");
                LOGGER.warn("    fresh conversation for this part of the work.");
            }
            return turns;
        }

        String existing = session.summary() == null ? "" : session.summary();
        String folded = fold(existing, older);

        try {
            if (folded == null) {
                LOGGER.warn("  ⚠ This conversation is too long to send in full.");
                LOGGER.warn("    The oldest {} turns have been dropped from what the model sees.", older.size());
                LOGGER.warn("    Attach a local model and they would be summarised instead, not lost:");
                LOGGER.warn("    the full transcript stays in `oss history` either way.");
                ChatSessionStore.setSummary(
                        session.id(),
                        (existing.isBlank() ? "" : existing + "\n")
                                + "[" + older.size() + " earlier turns omitted: no model was available to summarise"
                                + " them. They are still in `oss history --show " + session.id() + "`.]");
            } else {
                LOGGER.info("  ↳ Folded the oldest {} turns into a summary to stay within context.", older.size());
                ChatSessionStore.setSummary(session.id(), folded);
            }
        } catch (java.sql.SQLException e) {
            LOGGER.warn("  ⚠ Could not store the summary: {}", e.getMessage());
        }
        return tail;
    }

    /** Asks the model to fold older turns into the running summary. Null when no model answered. */
    private static String fold(String existingSummary, List<ChatTurn> older) {
        try {
            com.osscli.llm.OllamaClient client = guidanceClient();
            if (client == null) {
                return null;
            }
            String prompt = """
                    You are compacting a technical conversation so it can be continued later.
                    Keep every decision, file path, error message, command and conclusion.
                    Drop pleasantries and restated context. Write prose, not bullets.

                    --- SUMMARY SO FAR ---
                    %s

                    --- TURNS TO FOLD IN ---
                    %s
                    """.formatted(
                            existingSummary.isBlank() ? "(none)" : existingSummary,
                            clipTo(ChatSessionStore.transcript(null, older), 24_000));

            String out = client.generateText(prompt);
            return out == null || out.isBlank() ? null : out.strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.debug("Compaction unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** The most recent turns fitting in {@link #KEEP_TAIL_CHARS}, always at least one. */
    private static List<ChatTurn> tail(List<ChatTurn> turns) {
        List<ChatTurn> kept = new ArrayList<>();
        int budget = KEEP_TAIL_CHARS;
        for (int i = turns.size() - 1; i >= 0; i--) {
            ChatTurn t = turns.get(i);
            int cost = t.content() == null ? 0 : t.content().length();
            if (!kept.isEmpty() && cost > budget) {
                break;
            }
            kept.add(0, t);
            budget -= cost;
        }
        return kept;
    }

    // ==========================================
    // Shared
    // ==========================================

    /**
     * The configured local model, or null when nothing is attached.
     *
     * <p>Reachability is checked rather than assumed, because the alternative is every history
     * listing paying a connection timeout per session before printing the fallback anyway.
     */
    private static com.osscli.llm.OllamaClient guidanceClient() {
        String model;
        try {
            model = SqliteStorage.loadConfig("ollama.model.guidance");
        } catch (java.sql.SQLException e) {
            model = null;
        }
        if (model == null || model.isBlank()) {
            model = com.osscli.Defaults.GUIDANCE_MODEL;
        }
        com.osscli.llm.OllamaClient client = new com.osscli.llm.OllamaClient(model);
        return client.isServerReachable() ? client : null;
    }

    private static String firstSentence(String s) {
        String flat = s.strip().replaceAll("\\s+", " ");
        int stop = flat.indexOf(". ");
        return stop > 20 ? flat.substring(0, stop) : flat;
    }

    private static String clip(String s) {
        return clipTo(s, OVERVIEW_MAX);
    }

    private static String clipTo(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.strip();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
