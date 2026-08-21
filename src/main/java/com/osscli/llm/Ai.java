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
package com.osscli.llm;

import com.osscli.util.CredentialManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which engine may write a sentence, and in what order they are asked.
 *
 * <p>Before this, a provider was a per-command flag: {@code --send-claude} on {@code review},
 * {@code --claude} on {@code chat}, nothing at all on the rest, and an automatic local verdict
 * whenever Ollama happened to be installed. Two things were wrong with that. You could not tell
 * from what you typed whether a model had seen your code, and the same choice had a different
 * spelling on every command that offered it.
 *
 * <p>So the engine moved into the command line itself, in front:
 *
 * <pre>{@code
 * oss review 4249              the built-in model, and nothing leaves this machine
 * oss llm review 4249          local Ollama may answer
 * oss claude review 4249       Claude may answer
 * oss llm claude review 4249   either may, in that order
 * }</pre>
 *
 * <p><b>May, not will.</b> Naming an engine grants permission; it does not order a call. Every ask
 * starts on the local rung — your own notes, the vector index, the built-in model — and an external
 * engine is reached only when that rung fails a stated test, with the reason printed. A question
 * your own archive already answers is not worth a network round trip, and paying for one anyway is
 * how a tool teaches you to distrust its judgement about when it needs help.
 *
 * <p>Nothing here decides <em>whether</em> a command generates at all. A command that only reports
 * facts is not improved by a prefix, and accepting one would say a model was involved when none
 * was: {@link #USE} states that per command, and the dispatcher refuses the combination rather
 * than ignoring it.
 */
public final class Ai {

    /** Where a sentence can come from. Ordered cheapest and most private first. */
    public enum Engine {
        /** In this process, no daemon, no key, no network. */
        BUILTIN("oss", "built-in model", false),
        /** A local daemon the user installed and manages. */
        OLLAMA("oss llm", "local Ollama", false),
        CLAUDE("oss claude", "Anthropic Claude", true),
        GEMINI("oss gemini", "Google Gemini", true),
        OPENAI("oss codex", "OpenAI", true);

        private final String typed;
        private final String label;
        private final boolean needsKey;

        Engine(String typed, String label, boolean needsKey) {
            this.typed = typed;
            this.label = label;
            this.needsKey = needsKey;
        }

        /** What a reader types to get here, so a message can quote the fix rather than describe it. */
        public String typed() {
            return typed;
        }

        public String label() {
            return label;
        }

        public boolean needsKey() {
            return needsKey;
        }

        /**
         * True when this engine sends your text to somebody else's computer.
         *
         * <p>Ollama is not one of them. It is a daemon on this machine, so naming it grants no
         * network permission at all -- which is the difference {@code OFFLINE.md} counts, and the
         * reason this is not simply "anything but the built-in one".
         */
        public boolean isExternal() {
            return needsKey;
        }

        /** The credential this engine needs, if any, and whether it is actually present. */
        public boolean hasCredential() {
            switch (this) {
                case CLAUDE:
                    return present(CredentialManager.getClaudeKey());
                case GEMINI:
                    return present(CredentialManager.getGeminiKey());
                case OPENAI:
                    return present(CredentialManager.getOpenAiKey());
                default:
                    return true;
            }
        }

        private static boolean present(String key) {
            return key != null && !key.isBlank();
        }
    }

    /** How a command relates to generated text. The partition is the gate. */
    public enum Use {
        /** Reports facts, moves files, prints what is stored. A prefix here would be a lie. */
        NEVER,
        /** Answers without a model; a model adds a judgement on top. */
        OPTIONAL,
        /** Exists to produce prose. Without an engine there is nothing to print. */
        ALWAYS
    }

    /**
     * Every command, and whether it generates.
     *
     * <p>Stated for all of them rather than only the interesting ones, so adding a command forces
     * the decision instead of defaulting into silence. {@code AiModeGatingTest} asserts this map
     * and {@code release-surface.json} name exactly the same commands — the partition is the
     * assertion, the way the offline count is derived rather than typed.
     */
    public static final Map<String, Use> USE = buildUse();

    private static Map<String, Use> buildUse() {
        Map<String, Use> m = new java.util.TreeMap<>();
        // Generates by definition -- these have no other output.
        for (String c : List.of("analyze", "chat", "guide", "prompt")) {
            m.put(c, Use.ALWAYS);
        }
        // Answers on its own; an engine adds a verdict, a narrative, or steps.
        for (String c : List.of("review", "onboard", "sync")) {
            m.put(c, Use.OPTIONAL);
        }
        // Everything else reports, stores, dispatches or measures.
        //
        // `triage` is here deliberately and against its own documentation: COMMANDS.md called it
        // an Ollama command, and the code reads the stored `analyze` result plus the keyword
        // analyzer. It has never generated anything.
        for (String c : List.of(
                "alias",
                "backlog",
                "backup",
                "bench",
                "critical",
                "doctor",
                "duplicates",
                "ext",
                "followup",
                "hidden-critical",
                "history",
                "hub",
                "inspect",
                "issue",
                "kb",
                "memory",
                "model",
                "pick",
                "pr",
                "profile",
                "prs",
                "report",
                "restore",
                "run",
                "search",
                "serve",
                "setup",
                "trend",
                "triage")) {
            m.put(c, Use.NEVER);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** What the prefixes on this invocation asked for, in the order they were typed. */
    private static List<Engine> requested = List.of();

    private Ai() {}

    /**
     * Record the prefixes typed in front of the command.
     *
     * <p>Process-wide because it is a property of the invocation, not of one object: the prefix is
     * consumed before the command is even constructed, and every layer below has to see the same
     * answer. Duplicates collapse and order is kept, so {@code oss llm claude} and
     * {@code oss claude llm} differ, which is the point.
     */
    public static void select(List<Engine> engines) {
        requested = List.copyOf(new LinkedHashSet<>(engines));
    }

    /**
     * Append one engine, as its prefix is consumed.
     *
     * <p>Prefixes are eaten left to right and each re-dispatches the rest, so selection arrives one
     * engine at a time rather than as a list. Order is the escalation order, which is why this
     * appends rather than replacing.
     */
    /**
     * Whether the engine should be reached through the provider's own command-line tool.
     *
     * <p>Set by {@code --cli} on the prefix, never inferred. An engine that answers from a
     * subscription instead of API credit is a different account, a different harness -- the tools
     * can read files -- and a different answer to "whose model saw my code". The prefix already
     * exists so that question is settled by the line you typed; choosing the transport silently,
     * because the API happened to be out of credit, would take that back.
     */
    private static boolean viaCli = false;

    public static void useCli(boolean cli) {
        viaCli = cli;
    }

    public static boolean viaCli() {
        return viaCli;
    }

    public static void add(Engine engine) {
        List<Engine> next = new ArrayList<>(requested);
        next.add(engine);
        select(next);
    }

    /** Back to the default. Only tests need this; a real process runs one command and exits. */
    public static void reset() {
        requested = List.of();
    }

    /** The engines named, or the built-in when none were. */
    public static List<Engine> engines() {
        return requested.isEmpty() ? List.of(Engine.BUILTIN) : requested;
    }

    /** True when an external engine was granted permission -- not that it will be used. */
    public static boolean mayEscalate() {
        return engines().stream().anyMatch(Engine::isExternal);
    }

    /** The external engines to try, in the order typed, skipping any whose key is missing. */
    public static List<Engine> escalationPath() {
        List<Engine> out = new ArrayList<>();
        for (Engine e : engines()) {
            if (e.isExternal() && e.hasCredential()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Named but unusable: the prefix was typed and the key is not there. */
    public static List<Engine> missingCredentials() {
        List<Engine> out = new ArrayList<>();
        for (Engine e : engines()) {
            if (e.needsKey() && !e.hasCredential()) {
                out.add(e);
            }
        }
        return out;
    }

    /** How this invocation reads back to the person who typed it. */
    public static String describe() {
        List<String> names = new ArrayList<>();
        for (Engine e : engines()) {
            names.add(e.label());
        }
        return String.join(" then ", names);
    }

    /** The prefix that selects an engine, e.g. {@code llm} -> OLLAMA. */
    public static Optional<Engine> byPrefix(String word) {
        if (word == null) {
            return Optional.empty();
        }
        switch (word.toLowerCase(Locale.ROOT)) {
            case "llm":
                return Optional.of(Engine.OLLAMA);
            case "claude":
                return Optional.of(Engine.CLAUDE);
            case "gemini":
                return Optional.of(Engine.GEMINI);
            case "codex":
                return Optional.of(Engine.OPENAI);
            default:
                return Optional.empty();
        }
    }

    /** Every prefix word, for help text and for the gate's error messages. */
    public static Set<String> prefixes() {
        return new java.util.LinkedHashSet<>(List.of("llm", "claude", "gemini", "codex"));
    }

    /** What a command does with a model, defaulting to NEVER for anything unclassified. */
    public static Use use(String command) {
        return USE.getOrDefault(command, Use.NEVER);
    }

    /**
     * Send one prompt to the first named external engine that can take it.
     *
     * <p>Callers reach this only after their own local rung has failed a test they stated; it is
     * deliberately not a general "ask the AI" entry point. Returns empty when nothing external was
     * permitted or nothing had a key, and the caller then says so rather than printing a blank.
     */
    public static Optional<String> escalate(String prompt, String claudeModel, String geminiModel, String openAiModel)
            throws java.io.IOException, InterruptedException {
        for (Engine e : escalationPath()) {
            switch (e) {
                case CLAUDE:
                    return Optional.of(
                            new ClaudeClient(CredentialManager.getClaudeKey(), claudeModel).generateText(prompt));
                case GEMINI:
                    return Optional.of(
                            new GeminiClient(CredentialManager.getGeminiKey(), geminiModel).generateText(prompt));
                case OPENAI:
                    return Optional.of(
                            new OpenAiClient(CredentialManager.getOpenAiKey(), openAiModel).generateText(prompt));
                default:
                    break;
            }
        }
        return Optional.empty();
    }
}
