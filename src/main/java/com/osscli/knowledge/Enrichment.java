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

import java.util.List;
import java.util.Locale;

/**
 * The paragraph at the top of a session note that says what was actually worked out.
 *
 * <p>Everything else about filing a session is arithmetic: count the terms, take the longest turn,
 * read the directory. That produces a note filed in the right place with an honest title and no
 * opinion about what happened in it. This is the part that needs a reader, and a reader is the one
 * thing a term-counter cannot be.
 *
 * <h2>Three tiers, and the floor is the one that matters</h2>
 *
 * <ol>
 *   <li><b>Claude, when the CLI is on this machine.</b> Best prose, and it costs a subscription.
 *   <li><b>The local model, otherwise.</b> Ollama on this laptop: free, private, slower, and good
 *       enough for three sentences of summary.
 *   <li><b>Nothing.</b> The note keeps its extracted headings and is filed, indexed and searchable
 *       exactly as it would have been.
 * </ol>
 *
 * <p>The order is not a preference ranking, it is a fallback chain, and the third rung is load
 * bearing. An archive that only fills up while a paid CLI is installed makes a decade of somebody's
 * notes hostage to a subscription -- so nothing here may ever be required for a note to exist. What
 * a model adds is phrasing. What it must never add is a dependency.
 *
 * <p><b>Never on the hourly path by default.</b> A summary per session, twenty-four times a day,
 * against either a metered API or a laptop's CPU, is how a background job becomes the reason
 * somebody uninstalls the tool. {@code --enrich} asks for it.
 */
public final class Enrichment {

    /** How much of a transcript is worth sending. Past this, more input stops improving the answer. */
    private static final int PROMPT_BUDGET_CHARS = 12_000;

    /** How long to wait on the CLI before falling back. A summary is not worth a stalled job. */
    private static final long CLI_TIMEOUT_SECONDS = 120;

    /**
     * The first words of the prompt this class sends.
     *
     * <p>Public because {@link SessionNotes} has to recognise it. Asking a command-line tool to
     * summarise a transcript creates a session of its own, which the next hourly run reads and
     * files -- so the tool's own prompts came back as knowledge. Eighty-nine notes, every one
     * titled "below is a transcript of one working session on...", written by the machine about
     * the machine.
     *
     * <p>Shared as a constant rather than copied into the detector, because two spellings of the
     * same sentence would silently reopen the loop the first time this prompt was reworded.
     */
    public static final String PREAMBLE = "Below is a transcript of one working session on";

    private Enrichment() {}

    /**
     * Which tier answered.
     *
     * <p>Carries the provider's own name rather than a fixed set of constants, because the set is
     * not fixed: this must work with whatever is on the machine -- any of the CLIs, any of the
     * cloud engines, or the local model -- and a note that says "summarised by claude" when gemini
     * wrote it is worse than one that says nothing.
     */
    public record By(String label, boolean any) {

        /** Nothing wrote a summary. */
        public static final By NONE = new By("", false);

        public static By tool(String name) {
            return new By(name, true);
        }
    }

    /** A summary and where it came from. Never null; {@link By#NONE} means there is no summary. */
    public record Summary(String text, By by) {

        public boolean present() {
            return by != null && by.any() && text != null && !text.isBlank();
        }
    }

    private static final Summary ABSENT = new Summary("", By.NONE);

    /** True when anything on this machine could write a summary. */
    public static boolean available(boolean allowCli) {
        return (allowCli && !toolsHere().isEmpty()) || localIsHere();
    }

    /**
     * Which command-line tools are actually installed, in the order this install prefers them.
     *
     * <p>{@code CliClient.ALL} is the full set the tool speaks to -- claude, codex, gemini, junie --
     * and any of them can write three sentences about a transcript. Naming one in the code would
     * make the archive depend on a particular subscription, which is the whole thing this class
     * exists not to do.
     */
    static List<com.osscli.llm.CliClient.Spec> toolsHere() {
        List<com.osscli.llm.CliClient.Spec> out = new java.util.ArrayList<>();
        // Whatever the user has selected comes first: if they said `oss gemini ...`, gemini is the
        // one they meant, and reaching past it to another installed tool would be this tool
        // choosing a provider on their behalf.
        for (com.osscli.llm.Ai.Engine preferred : com.osscli.llm.Ai.engines()) {
            com.osscli.llm.CliClient.Spec spec = com.osscli.llm.CliClient.specFor(preferred);
            if (spec != null && isHere(spec)) {
                out.add(spec);
            }
        }
        for (com.osscli.llm.CliClient.Spec spec : com.osscli.llm.CliClient.ALL) {
            if (!out.contains(spec) && isHere(spec)) {
                out.add(spec);
            }
        }
        return out;
    }

    private static boolean isHere(com.osscli.llm.CliClient.Spec spec) {
        try {
            return new com.osscli.llm.CliClient(spec, CLI_TIMEOUT_SECONDS).available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * What this session worked out, in a few sentences.
     *
     * <p>Never throws and never blocks past its timeout. Every failure falls through to the next
     * tier and finally to {@link #ABSENT}, because the caller's next move is identical whether the
     * model was missing, slow, out of credit or wrong -- write the note without it.
     */
    public static Summary summarise(String title, String topic, String transcript, boolean allowCli) {
        String prompt = promptFor(title, topic, clip(transcript));
        if (allowCli) {
            for (com.osscli.llm.CliClient.Spec spec : toolsHere()) {
                String said = tryTool(spec, prompt);
                if (said != null) {
                    return new Summary(said, By.tool(spec.binary()));
                }
                // Out of credit, rate limited, logged out. Try the next tool rather than the
                // local model: another subscription on the same machine is a better answer than
                // a smaller model, and finding out costs one failed call.
            }
        }
        String said = tryLocal(prompt);
        return said == null ? ABSENT : new Summary(said, By.tool("local model"));
    }

    /**
     * The prompt.
     *
     * <p>Asks for what a note is for -- the conclusion and the reason -- and explicitly forbids the
     * two things a model does instead when a transcript has no conclusion in it: narrating the
     * conversation, and inventing one. "Say so" is a permitted answer, and an honest note saying
     * nothing was settled is worth more than a confident summary of a session that went nowhere.
     */
    static String promptFor(String title, String topic, String transcript) {
        return """
                %s %s.

                Write at most four sentences saying what was actually worked out: the conclusion \
                reached, and why. Prefer the technical substance -- the cause of a bug, the reason \
                a design was chosen, the thing that turned out not to be true.

                Rules:
                - No preamble. Start with the substance.
                - Do not narrate the conversation ("the user asked", "we then looked at").
                - If nothing was concluded, say exactly that in one sentence. Do not invent one.
                - Plain prose. No bullets, no headings, no markdown.

                Session: %s

                --- TRANSCRIPT ---
                %s
                """.formatted(topic, title, transcript);
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= PROMPT_BUDGET_CHARS ? s : s.substring(0, PROMPT_BUDGET_CHARS) + "\n[…truncated]";
    }

    // ==========================================
    // The tiers
    // ==========================================

    private static String tryTool(com.osscli.llm.CliClient.Spec spec, String prompt) {
        try {
            return clean(new com.osscli.llm.CliClient(spec, CLI_TIMEOUT_SECONDS).generateText(prompt));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean localIsHere() {
        return local() != null;
    }

    private static com.osscli.llm.OllamaClient local() {
        try {
            String model;
            try {
                model = com.osscli.storage.SqliteStorage.loadConfig("ollama.model.guidance");
            } catch (java.sql.SQLException e) {
                model = null;
            }
            if (model == null || model.isBlank()) {
                model = com.osscli.Defaults.GUIDANCE_MODEL;
            }
            com.osscli.llm.OllamaClient client = new com.osscli.llm.OllamaClient(model);
            // Checked rather than assumed: otherwise every note pays a connection timeout before
            // falling back to the answer it was always going to give.
            return client.isServerReachable() ? client : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String tryLocal(String prompt) {
        com.osscli.llm.OllamaClient client = local();
        if (client == null) {
            return null;
        }
        try {
            return clean(client.generateText(prompt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strip what a small model puts around an answer it was told not to put anything around.
     *
     * <p>Not decoration. "Here is a summary:" as the first line of a note reads as though the note
     * is addressed to somebody, and a hundred of them make an archive look generated rather than
     * kept.
     */
    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> kept = new java.util.ArrayList<>();
        for (String line : raw.strip().lines().toList()) {
            String t = line.strip();
            String lower = t.toLowerCase(Locale.ROOT);
            if (t.isEmpty() && kept.isEmpty()) {
                continue;
            }
            if (kept.isEmpty()
                    && (lower.startsWith("here is")
                            || lower.startsWith("here's")
                            || lower.startsWith("summary:")
                            || lower.startsWith("sure,")
                            || lower.equals("summary"))) {
                continue;
            }
            // Fenced output happens even when the prompt asks for prose.
            if (t.startsWith("```")) {
                continue;
            }
            kept.add(t);
        }
        String out = String.join(" ", kept).replaceAll("\\s+", " ").strip();
        // A one-word answer is a model failing rather than a session with nothing in it.
        return out.length() < 20 ? null : out;
    }
}
