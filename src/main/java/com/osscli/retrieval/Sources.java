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

import com.osscli.model.PromptContextChunk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What went into an answer, and what did not.
 *
 * <p>{@code review} has said this for a long time — a closing block naming every layer it used and,
 * for each one it did not, the reason. It is the most-read part of the output, because a thin
 * review and a clean one look identical until something says which this was.
 *
 * <p>{@code chat} and {@code guide} said none of it. With a cloud key attached they would answer
 * confidently having retrieved nothing, or having retrieved a great deal, and the two read exactly
 * the same. An answer built from six of your own passages and an answer built from the issue title
 * alone deserve different amounts of trust, and the user was left to guess which they had.
 *
 * <p>So this reports three things in order: what you already had, what the model added, and what
 * would make the next answer better. The last one matters most — an absence with no remedy beside
 * it is a complaint.
 */
public final class Sources {

    private static final Logger LOGGER = LogManager.getLogger(Sources.class);

    private Sources() {}

    /**
     * The retrieval, counted rather than concatenated.
     *
     * @param matched everything that scored above the relevance threshold
     * @param included what actually fitted in the token budget
     * @param tokens what those cost
     * @param byKind how many included passages came from each kind of source
     */
    public record Retrieved(int matched, int included, int tokens, Map<String, Integer> byKind) {

        static Retrieved of(List<PromptContextChunk> chunks) {
            int tokens = 0;
            int included = 0;
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (PromptContextChunk c : chunks) {
                if (!c.included()) {
                    continue;
                }
                included++;
                tokens += c.tokenCount();
                byKind.merge(readable(c.sourceType()), 1, Integer::sum);
            }
            return new Retrieved(chunks.size(), included, tokens, byKind);
        }

        public boolean any() {
            return included > 0;
        }

        /** "6 passages (~480 tokens) of 23 that matched", or the shorter form when nothing was dropped. */
        public String summary() {
            String head = included + " passage" + (included == 1 ? "" : "s") + " (~" + tokens + " tokens)";
            return included < matched ? head + " of " + matched + " that matched" : head;
        }

        /** "2 past pull requests · 3 notes · 1 collected discussion" */
        public String breakdown() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : byKind.entrySet()) {
                if (b.length() > 0) {
                    b.append(" · ");
                }
                b.append(e.getValue()).append(' ').append(e.getKey()).append(e.getValue() == 1 ? "" : "s");
            }
            return b.toString();
        }
    }

    /** Plain words for the source types the retriever stores. */
    private static String readable(String sourceType) {
        if (sourceType == null) {
            return "passage";
        }
        return switch (sourceType) {
            case "pr_memory" -> "past pull request";
            case "chat_memory" -> "note";
            case "reference" -> "collected discussion";
            case "referenced_issue" -> "stated reference";
            case "cross_repo" -> "cross-repository link";
            case "issue" -> "issue";
            case "stack_trace" -> "stack trace";
            case "jira" -> "Jira item";
            default -> sourceType.replace('_', ' ');
        };
    }

    /** Counts a retrieval without rendering it, for callers that want the ledger as well as the text. */
    public static Retrieved count(long issueNumber, String repository) {
        try {
            return Retrieved.of(ContextRetriever.retrieve(issueNumber, repository));
        } catch (Exception e) {
            LOGGER.debug("Could not count retrieval: {}", e.getMessage());
            return new Retrieved(0, 0, 0, Map.of());
        }
    }

    /**
     * Prints the ledger.
     *
     * @param answeredBy the model that produced the answer, e.g. "Gemini (gemini-2.5-flash)"
     * @param aligned whether a local model read the answer back against the user's own work
     * @param whyNotAligned the reason it did not, when it did not
     */
    public static void report(
            String repository,
            long issueNumber,
            Retrieved retrieved,
            String answeredBy,
            boolean aligned,
            String whyNotAligned) {

        LOGGER.info("");
        LOGGER.info("── What went into this answer ──");
        mark(true, "The issue as filed", "#" + issueNumber + " in " + repository);

        if (retrieved.any()) {
            mark(true, "Your own prior work", retrieved.summary());
            if (!retrieved.breakdown().isEmpty()) {
                LOGGER.info("        {}", retrieved.breakdown());
            }
        } else if (retrieved.matched() > 0) {
            // Matched but nothing fitted. Different from "you have nothing", and the remedy differs
            // too, so it must not be reported as an absence.
            mark(false, "Your own prior work", retrieved.matched() + " matched but none fitted the budget");
        } else {
            mark(false, "Your own prior work", "nothing indexed matched this issue");
        }

        mark(true, "Answered by", answeredBy);
        mark(aligned, "Read back against your history", aligned ? "checked before you saw it" : whyNotAligned);

        List<String> next = new java.util.ArrayList<>();
        if (!aligned) {
            next.add("attach a local model that fits — then a cloud answer is checked against your own work");
        }
        if (retrieved.matched() == 0) {
            next.add("oss sync -r " + repository + " — this project's history is what the answer draws on");
            next.add("oss sync --me — your own pull requests and notes, which is what makes it yours");
        }
        if (!next.isEmpty()) {
            LOGGER.info("");
            LOGGER.info("── What would make the next one better ──");
            for (String n : next) {
                LOGGER.info("  · {}", n);
            }
        }
        LOGGER.info("");
    }

    private static void mark(boolean present, String what, String detail) {
        // Aligned columns, because the point of the block is to be scanned rather than read.
        LOGGER.info("  {} {}{}", present ? "✔" : "✗", pad(what), detail == null ? "" : detail);
    }

    private static String pad(String s) {
        return s.length() >= 32 ? s + "  " : s + " ".repeat(32 - s.length());
    }
}
