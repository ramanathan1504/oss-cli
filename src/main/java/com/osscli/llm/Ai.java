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
        BUILTIN("oss", "built-in model", false, false, false),
        /** A local daemon the user installed and manages. */
        OLLAMA("oss llm", "local Ollama", false, false, true),
        CLAUDE("oss claude", "Anthropic Claude", true, true, true),
        GEMINI("oss gemini", "Google Gemini", true, true, true),
        OPENAI("oss codex", "OpenAI", true, true, true),
        /**
         * JetBrains Junie, which brings its own authentication and has no endpoint of ours.
         *
         * <p>The engine that proved these were three questions rather than one. It leaves this
         * machine, so it is external; it holds its own token (or the provider key of your choice)
         * behind {@code junie --auth}, so oss manages no key for it; and there is no HTTP route
         * here to call, so its only road is the tool. Coupling "external" to "needs a key" would
         * have filed it as local and let it answer where nothing is supposed to leave the machine.
         */
        JUNIE("oss junie", "JetBrains Junie", false, true, false);

        private final String typed;
        private final String label;
        private final boolean needsKey;
        private final boolean external;
        private final boolean hasApi;

        Engine(String typed, String label, boolean needsKey, boolean external, boolean hasApi) {
            this.typed = typed;
            this.label = label;
            this.needsKey = needsKey;
            this.external = external;
            this.hasApi = hasApi;
        }

        /** Whether oss has an endpoint of its own to call, as opposed to only the provider's tool. */
        public boolean hasApi() {
            return hasApi;
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
            return external;
        }

        /** The credential this engine needs, if any, and whether it is actually present. */
        public boolean hasCredential() {
            // find*, not get*. The get* family throws when the key is absent -- correct for a caller
            // about to make a request, and a crash for one asking whether it can. This is a
            // predicate; it answers false.
            switch (this) {
                case CLAUDE:
                    return present(CredentialManager.findClaudeKey());
                case GEMINI:
                    return present(CredentialManager.findGeminiKey());
                case OPENAI:
                    return present(CredentialManager.findOpenAiKey());
                default:
                    // Junie and the local rungs: nothing here to be missing. Junie signs itself in.
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
        for (String c : List.of("analyze", "ask", "chat", "guide", "prompt")) {
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
     * Whether {@code --cli} was typed, forcing the provider's own command-line tool.
     *
     * <p>This flag is still never inferred, and the reason has not changed: an engine answering
     * from a subscription instead of API credit is a different account, a different harness -- the
     * tools can read files -- and a different answer to "whose model saw my code". Choosing that
     * transport silently <em>because the API was out of credit</em> would take that back, so a key
     * that exists and fails still fails, and {@link ApiFailure} names the tool rather than reaching
     * for it.
     *
     * <p><b>Having no key at all is a different situation, and {@link #routeFor} treats it as
     * one.</b> There is no account to switch away from, no bill to move, and nothing ambiguous
     * about the intent: {@code oss claude review} with an installed, logged-in {@code claude} and
     * no {@code ANTHROPIC_API_KEY} used to be a dead end that a flag you had to already know about
     * was the only way out of. Now the tool answers and says, before it does, which rung replied.
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

    /**
     * How an external engine will actually be reached.
     *
     * <p>Two routes exist to the same three providers and they are not interchangeable: the HTTP
     * API bills a key, the provider's own tool answers on the subscription it is logged in to.
     * Which one runs used to be decided entirely by whether {@code --cli} was typed, so an engine
     * with a tool installed and no key was simply unreachable.
     */
    public enum Route {
        /** The provider's HTTP API, against a key. */
        API,
        /** The provider's own command-line tool, against the subscription it is signed in to. */
        CLI,
        /** Neither. No key, and no tool on the PATH. */
        NONE
    }

    /**
     * The rung this engine can answer on, or {@link Route#NONE} when it cannot answer at all.
     *
     * <p>Order: an explicit {@code --cli} wins, because it was typed. Otherwise a key is preferred
     * -- it is the cheaper, narrower harness, and it is what the reader almost certainly meant by
     * naming the engine. Only with no key does the installed tool answer, and {@link Cloud} says
     * so out loud before it does.
     *
     * <p>An absent tool under {@code --cli} deliberately still routes to {@link Route#CLI}: the
     * flag was typed, and {@link CliClient} refuses with the binary's name and the way back, which
     * is a better answer than this method quietly reporting the engine unreachable.
     */
    public static Route routeFor(Engine engine) {
        boolean external = engine != null && engine.isExternal();
        return route(
                viaCli,
                external,
                external && engine.hasApi(),
                external && engine.hasCredential(),
                external && cliInstalled(engine));
    }

    /**
     * The decision itself, with nothing to look up.
     *
     * <p>Separated from {@link #routeFor} because the inputs it needs are a keychain and a PATH:
     * asked as a whole, this answers differently on the machine that wrote it (where {@code claude}
     * is installed) than on a CI runner (where it is not), and a rule that can only be tested on
     * one of them is a rule nobody can check. Here all eight combinations are a table.
     */
    static Route route(boolean forcedCli, boolean external, boolean hasKey, boolean toolInstalled) {
        return route(forcedCli, external, true, hasKey, toolInstalled);
    }

    /**
     * As above, for an engine that may have no endpoint of ours at all.
     *
     * <p>Junie is the case: it leaves this machine, brings its own authentication, and offers no
     * HTTP route here. Without {@code hasApi} the key branch would win by default — {@code
     * hasCredential} is true for it, because there is no key of ours to be missing — and the engine
     * would be sent to an endpoint that does not exist.
     */
    static Route route(boolean forcedCli, boolean external, boolean hasApi, boolean hasKey, boolean toolInstalled) {
        if (!external) {
            return Route.NONE;
        }
        if (forcedCli) {
            return Route.CLI;
        }
        if (hasApi && hasKey) {
            return Route.API;
        }
        return toolInstalled ? Route.CLI : Route.NONE;
    }

    /**
     * Whether the provider's tool is on the PATH.
     *
     * <p>A one second timeout because nothing is executed -- the check resolves a filename against
     * the PATH -- but the constructor asks for one and a value that could ever be waited on should
     * not be generous.
     */
    private static boolean cliInstalled(Engine engine) {
        CliClient.Spec spec = CliClient.specFor(engine);
        return spec != null && new CliClient(spec, 1).available();
    }

    /**
     * The external engines to try, in the order typed, skipping any that cannot answer.
     *
     * <p>"Cannot answer" is now both routes missing, not the key alone. Filtering on the key was
     * what made an installed, logged-in tool invisible to escalation: the engine was dropped here,
     * so nothing downstream ever got the chance to use it.
     */
    public static List<Engine> escalationPath() {
        List<Engine> out = new ArrayList<>();
        for (Engine e : engines()) {
            if (e.isExternal() && routeFor(e) != Route.NONE) {
                out.add(e);
            }
        }
        return out;
    }

    /** Named but unusable: the prefix was typed, and there is neither a key nor a tool. */
    public static List<Engine> missingCredentials() {
        List<Engine> out = new ArrayList<>();
        for (Engine e : engines()) {
            if (e.needsKey() && routeFor(e) == Route.NONE) {
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
            case "junie":
                return Optional.of(Engine.JUNIE);
            case "codex":
                return Optional.of(Engine.OPENAI);
            default:
                return Optional.empty();
        }
    }

    /** Every prefix word, for help text and for the gate's error messages. */
    public static Set<String> prefixes() {
        return new java.util.LinkedHashSet<>(List.of("llm", "claude", "gemini", "codex", "junie"));
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
