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

import com.osscli.ext.Attachments;
import com.osscli.model.PromptContextChunk;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Your own past work, as much of it as will fit in the prompt.
 *
 * <p>{@code chat} and {@code guide} each grew their own version of this, and both had the same
 * defect: append the <b>entire text</b> of every note scoring above 0.35, with no cap. On a real
 * corpus that is not a slightly-too-long prompt, it is a different order of magnitude — 592 notes
 * totalling 34 MB, 332 of them matching, produced a prompt of roughly <b>19 MB</b> for a model
 * configured with a 6,000-token window. About eight hundred times what it could accept. The request
 * timed out, which looked like a slow machine rather than a prompt that was never going to work.
 *
 * <p>{@link ContextRetriever} already solved this for {@code prompt}: rank everything by relevance,
 * fill a token budget in that order, and record what was dropped. So this is not a third
 * implementation — it is the two wrong ones being replaced by a call to the right one.
 *
 * <p>What it adds is the rendering: turning ranked chunks into the prose block those two commands
 * put in their prompts, and a line saying how much of what matched actually went in. That number
 * matters. "332 matched" and "12 included" are very different statements about an answer, and the
 * user should not have to guess which they got.
 */
public final class MemoryContext {

    private static final Logger LOGGER = LogManager.getLogger(MemoryContext.class);

    private MemoryContext() {}

    /**
     * The budgeted context block for an issue, or an empty string when nothing is relevant.
     *
     * <p>Never throws: a command that cannot read the corpus should answer with less rather than
     * fail, exactly as it would for a user who has connected no notes at all.
     */
    public static String forIssue(long issueNumber, String repository) {
        // Orientation first, and it survives every failure below. What is attached to this machine
        // is true whether or not the corpus can be read, and a model that has never been told a
        // bench exists cannot offer to run one -- it can only guess, or stay silent and look as if
        // there was nothing to offer.
        String attached = attached();

        List<PromptContextChunk> chunks;
        try {
            chunks = ContextRetriever.retrieve(issueNumber, repository);
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Could not retrieve past work: {}", e.getMessage());
            LOGGER.warn("    Answering from the issue alone.");
            return attached;
        }

        List<PromptContextChunk> included = new ArrayList<>();
        for (PromptContextChunk c : chunks) {
            if (c.included()) {
                included.add(c);
            }
        }

        report(chunks.size(), included);
        return attached + render(included);
    }

    /**
     * What is attached, or an empty string.
     *
     * <p>Never throws, for the same reason nothing else here does: a decoration that can fail the
     * command it decorates is worse than one that is absent. Bounded by {@link Attachments} itself
     * -- an unbudgeted block is what produced a 19 MB prompt, and this one is on the same side of
     * that lesson as the rest of this class.
     */
    private static String attached() {
        try {
            return Attachments.forPrompt();
        } catch (Exception e) {
            LOGGER.debug("attachment block skipped: {}", e.toString());
            return "";
        }
    }

    /**
     * Says how much of what matched was actually used.
     *
     * <p>Silence here would be the same failure in a quieter form: an answer built from twelve of
     * three hundred matches is a different answer, and nothing else on screen would say so.
     */
    private static void report(int found, List<PromptContextChunk> included) {
        if (found == 0) {
            LOGGER.info("  ↳ No past work matched this issue.");
            return;
        }
        int tokens = 0;
        for (PromptContextChunk c : included) {
            tokens += c.tokenCount();
        }
        if (included.size() < found) {
            LOGGER.info(
                    "  ↳ {} of {} matching passages included (~{} tokens; the rest did not fit).",
                    included.size(),
                    found,
                    tokens);
        } else {
            LOGGER.info("  ↳ {} matching passages included (~{} tokens).", included.size(), tokens);
        }
    }

    /** Renders chunks as the labelled block these prompts expect. */
    private static String render(List<PromptContextChunk> included) {
        if (included.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (PromptContextChunk c : included) {
            b.append("--- ")
                    .append(label(c.sourceType()))
                    .append(c.sourceRef() == null || c.sourceRef().isBlank() ? "" : " (" + c.sourceRef() + ")")
                    .append(" ---\n")
                    .append(c.content())
                    .append("\n\n");
        }
        return b.toString();
    }

    /**
     * A readable heading per source.
     *
     * <p>{@code reference} is called out because it is material the user collected rather than wrote,
     * and a model told everything is the user's own work will speak about somebody else's discussion
     * as though the reader had been in it.
     */
    private static String label(String sourceType) {
        if (sourceType == null) {
            return "CONTEXT";
        }
        return switch (sourceType) {
            case "pr_memory" -> "YOUR PAST PULL REQUEST";
            case "chat_memory" -> "YOUR NOTE";
            case "reference" -> "COLLECTED DISCUSSION (not your own work)";
            case "referenced_issue" -> "STATED REFERENCE";
            case "cross_repo" -> "CROSS-REPOSITORY LINK";
            case "issue" -> "THE ISSUE";
            case "stack_trace" -> "STACK TRACE";
            case "jira" -> "JIRA";
            default -> sourceType.toUpperCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }
}
