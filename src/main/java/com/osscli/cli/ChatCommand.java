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

import com.osscli.knowledge.SessionDigest;
import com.osscli.llm.ClaudeClient;
import com.osscli.llm.GeminiClient;
import com.osscli.llm.ModelFit;
import com.osscli.llm.OllamaClient;
import com.osscli.llm.OpenAiClient;
import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import com.osscli.model.Issue;
import com.osscli.storage.ChatSessionStore;
import com.osscli.storage.SqliteStorage;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * An interactive conversation about one issue, which survives the terminal it was started in.
 *
 * <p>This used to hold the whole conversation in a {@link StringBuilder} and write it to the
 * archive only if the user typed {@code exit}. Everything else -- ctrl-c, a closed lid, a dropped
 * ssh session, an exception inside the model call -- discarded it. And there was no way back into
 * yesterday's conversation, so every morning started from nothing on a problem that had been half
 * solved the night before.
 *
 * <p>Now every turn is written the moment it is said. The transcript is state on disk that a
 * process happens to be attached to, rather than state in a process that is occasionally written to
 * disk. Resuming is then just attaching to it again, which is what {@code --resume} does.
 */
@Command(
        name = "chat",
        hidden = true,
        mixinStandardHelpOptions = true,
        description =
                "Talk through an issue. Conversations are saved as you go and can be resumed.  ·  now covered by: oss ask --issue <n>  (or oss ask, for a conversation)")
public class ChatCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ChatCommand.class);

    /** How many past turns are replayed on screen when resuming. Enough to remember, not a wall of text. */
    private static final int REPLAY_TURNS = 6;

    /** {@code --resume} with no id means "show me the list". */
    private static final long PICK = -1L;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<number>",
            description = "The issue or PR number to chat about. Optional when resuming.")
    private Long issueNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "Repository as owner/name. Defaults to the configured one.")
    private String repository;

    @Option(
            names = {"-c", "--continue"},
            description = "Continue the most recent conversation.")
    private boolean continueLatest;

    @Option(
            names = {"--resume"},
            arity = "0..1",
            fallbackValue = "-1",
            paramLabel = "<id>",
            description = "Resume a saved conversation. With no id, pick one from the list.")
    private Long resumeId;

    @Override
    public Integer call() throws Exception {
        ChatSession session = resolveSession();
        if (session == null) {
            return 1;
        }
        return run(session);
    }

    /**
     * Opens the session this invocation is about: an existing one to resume, or a new one.
     *
     * @return null when the user cancelled or the request could not be satisfied, having said why
     */
    private ChatSession resolveSession() throws Exception {
        boolean resuming = continueLatest || resumeId != null;

        if (!resuming) {
            if (issueNumber == null) {
                LOGGER.error("Which issue? Give a number, or use --resume to pick up a saved conversation.");
                LOGGER.error("  oss chat 4129            start on issue #4129");
                LOGGER.error("  oss chat --continue      carry on where you left off");
                LOGGER.error("  oss history              browse everything you have discussed");
                return null;
            }
            if (!resolveRepository()) {
                return null;
            }
            Issue target = findIssue(repository, issueNumber);
            if (target == null) {
                LOGGER.error("Issue #{} is not in the local data for '{}'.", issueNumber, repository);
                // NOT `oss sync`. Sync fetches OPEN issues from a delta watermark, so it can
                // never bring down a closed one -- and a closed issue is exactly the kind
                // somebody comes back to read. The old advice sent people round a loop that
                // reported "0 saved" every time and left them no better off.
                LOGGER.error("  oss issue {} --repo {} fetches it, and keeps it.", issueNumber, repository);
                LOGGER.error("  oss sync -r {} only covers issues that are still open.", repository);
                return null;
            }
            long id = ChatSessionStore.open(repository, issueNumber, target.title(), providerName(), null);
            return ChatSessionStore.byId(id);
        }

        ChatSession chosen;
        if (resumeId != null && resumeId != PICK) {
            chosen = ChatSessionStore.byId(resumeId);
            if (chosen == null) {
                LOGGER.error("No saved conversation with id {}. `oss history` lists them.", resumeId);
                return null;
            }
        } else if (continueLatest) {
            // --continue means the last thing worked on, narrowed by whatever else was typed.
            chosen = ChatSessionStore.latest(repository, issueNumber);
            if (chosen == null) {
                LOGGER.error("Nothing to continue yet — no saved conversations{}.", scopeSuffix());
                LOGGER.error("  oss chat <number> starts one.");
                return null;
            }
        } else {
            chosen = HistoryCommand.pick(repository, issueNumber);
            if (chosen == null) {
                // Either nothing matched or the user backed out; the picker has already said which.
                return null;
            }
        }

        repository = chosen.repository();
        issueNumber = chosen.issueNumber();
        return attach(chosen);
    }

    /**
     * Takes the session over, forking it if another terminal is already in it.
     *
     * <p>Two processes appending to one transcript produce a conversation that interleaves two
     * people's thinking and afterwards reads as neither. Refusing outright is worse though -- a
     * stale heartbeat from a process that was killed would lock the user out of their own
     * conversation. So the collision is reported and the user chooses, with the safe option as the
     * default.
     */
    private ChatSession attach(ChatSession chosen) throws Exception {
        if (!chosen.heldElsewhere(ChatSessionStore.myPid(), ChatSessionStore.myHost())) {
            ChatSessionStore.claim(chosen.id());
            return ChatSessionStore.byId(chosen.id());
        }

        LOGGER.warn("  ⚠ Conversation {} looks open in another terminal.", chosen.id());
        LOGGER.warn(
                "    Last activity {} ago, from pid {} on {}.",
                chosen.ageLabel(),
                chosen.ownerPid(),
                chosen.ownerHost());
        LOGGER.warn("    Both terminals writing to one transcript would interleave them.");
        LOGGER.info("");
        LOGGER.info("  [f] fork it — a new conversation carrying this history  (default)");
        LOGGER.info("  [a] attach anyway — if you know the other terminal is gone");
        LOGGER.info("  [c] cancel");
        LOGGER.info("");
        System.err.print("  Choice: ");

        Scanner scanner = new Scanner(System.in);
        String answer = scanner.hasNextLine() ? scanner.nextLine().trim().toLowerCase() : "f";

        if (answer.startsWith("c")) {
            LOGGER.info("Cancelled. Nothing was changed.");
            return null;
        }
        if (answer.startsWith("a")) {
            ChatSessionStore.claim(chosen.id());
            return ChatSessionStore.byId(chosen.id());
        }

        long forked = ChatSessionStore.open(
                chosen.repository(), chosen.issueNumber(), chosen.issueTitle(), providerName(), chosen.id());
        ChatSessionStore.copyTurns(chosen.id(), forked);
        if (chosen.summary() != null) {
            ChatSessionStore.setSummary(forked, chosen.summary());
        }
        LOGGER.info("  ↳ Forked to conversation {}. The original is untouched.", forked);
        return ChatSessionStore.byId(forked);
    }

    /** Runs a session someone else resolved -- how {@code oss history} hands one over. */
    static int resume(ChatSession session) throws Exception {
        ChatCommand cmd = new ChatCommand();
        cmd.repository = session.repository();
        cmd.issueNumber = session.issueNumber();
        ChatSession attached = cmd.attach(session);
        return attached == null ? 1 : cmd.run(attached);
    }

    // ==========================================
    // The conversation
    // ==========================================

    private int run(ChatSession session) throws Exception {
        Issue target = findIssue(session.repository(), session.issueNumber());
        if (target == null) {
            LOGGER.error(
                    "Issue #{} is no longer in the local data for '{}'.", session.issueNumber(), session.repository());
            LOGGER.error("  The conversation is still readable: oss history --show {}", session.id());
            return 1;
        }

        String modelName = SqliteStorage.loadConfig("ollama.model.guidance");
        if (modelName == null) {
            modelName = com.osscli.Defaults.GUIDANCE_MODEL;
        }
        Cloud cloud = Cloud.forEngine();

        // Chat needs a model that writes. It does not need a *particular* one.
        //
        // This used to exit here the moment Ollama was unreachable, whatever else was configured --
        // so somebody with an API key and no wish to keep four gigabytes of weights on their laptop
        // could not open a chat at all. That is the same failure as requiring a cloud key was, just
        // pointing the other way, and it breaks the rule the whole tool is built on: a capability
        // may degrade, but it may not be gated on one particular provider.
        // Ollama is used when it was asked for. A daemon that happens to be running is not a
        // request, and answering from it silently is what the engine prefixes exist to end.
        OllamaClient localClient = new OllamaClient(modelName);
        boolean localUsable =
                com.osscli.llm.Ai.engines().contains(com.osscli.llm.Ai.Engine.OLLAMA) && localClient.isModelAvailable();

        // Installed is not the same as runnable. A model larger than the free memory is loaded
        // rather than refused, the machine swaps, and it stops responding for minutes -- which
        // cannot be cancelled or read. Treated as "not available", so the conversation falls back
        // to whatever else is connected instead of taking the laptop down.
        if (localUsable) {
            ModelFit.Verdict fit = ModelFit.check(localClient, modelName);
            if (fit.shouldRefuse()) {
                LOGGER.warn("  ⚠ Not using '{}' — it does not fit in memory right now.", modelName);
                for (String line : fit.explain()) {
                    LOGGER.warn("    {}", line);
                }
                localUsable = false;
            }
        }

        Backends backends = Backends.of(localUsable, cloud.available());
        if (!backends.staysLocal()) {
            localClient = null;
        }
        if (!backends.canAnswer()) {
            LOGGER.error("Chat needs a model that writes, and none was named.");
            LOGGER.error("");
            LOGGER.error("  oss llm chat {}      local Ollama", session.issueNumber());
            LOGGER.error("  oss claude chat {}   Anthropic Claude", session.issueNumber());
            LOGGER.error("  oss gemini chat {}   Google Gemini", session.issueNumber());
            LOGGER.error("  oss codex chat {}    OpenAI", session.issueNumber());
            LOGGER.error("");
            LOGGER.error("  Local:  '{}' at {}", modelName, new OllamaClient(modelName).endpoint());
            LOGGER.error("          ollama serve, then: ollama pull {}", modelName);
            LOGGER.error("  Cloud:  {}", cloud.why().isEmpty() ? "no API key found" : cloud.why());
            LOGGER.error("");
            LOGGER.error("  Either one is enough. Everything else in oss works without both:");
            LOGGER.error(
                    "    oss prompt {} assembles the same context as a prompt you can paste anywhere.",
                    session.issueNumber());
            return 1;
        }

        String memoryContext = buildMemoryContext(session.repository(), session.issueNumber());

        List<ChatTurn> turns = ChatSessionStore.turns(session.id());
        banner(session, target, backends.staysLocal() ? modelName : null, cloud, turns, backends);

        Scanner scanner = new Scanner(System.in);
        String lastUserPrompt = lastUserPrompt(turns);

        while (true) {
            System.err.print("\n> ");
            if (!scanner.hasNextLine()) {
                // stdin closed: the pipe ended or the terminal went away. Every turn is already
                // stored, so this is a pause, not a loss -- and it is worth saying so.
                LOGGER.info("");
                LOGGER.info("Input ended. Conversation {} is saved: oss chat --resume {}", session.id(), session.id());
                return 0;
            }
            String userInput = scanner.nextLine().trim();

            if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                finish(session, target);
                return 0;
            }
            if (userInput.isEmpty()) {
                continue;
            }

            // The user has been typing; say so before the model call, so a second terminal does not
            // decide this session was abandoned while somebody was composing a paragraph.
            ChatSessionStore.touch(session.id());

            // Two meta-commands, and neither becomes a turn: asking how full the conversation is,
            // and folding it now rather than waiting for the fold to happen mid-answer. A long
            // conversation is a thing the user can feel going wrong before the tool notices, and
            // having to wait for an automatic threshold is the difference between a tool you steer
            // and one that steers you.
            if (userInput.equalsIgnoreCase("/context")) {
                reportContext(session, turns, memoryContext);
                continue;
            }
            if (userInput.equalsIgnoreCase("/compact")) {
                int before = SessionDigest.used(session, turns);
                turns = SessionDigest.compact(session, turns);
                session = ChatSessionStore.byId(session.id());
                int after = SessionDigest.used(session, turns);
                if (after < before) {
                    LOGGER.info("  ↳ {} → {} characters. Nothing left `oss history`.", before, after);
                } else {
                    LOGGER.info("  ↳ Nothing to fold yet — this conversation still fits in full.");
                }
                reportContext(session, turns, memoryContext);
                continue;
            }

            boolean escalating = userInput.equalsIgnoreCase("y") && !lastUserPrompt.isEmpty();
            if (!escalating) {
                lastUserPrompt = userInput;
                ChatSessionStore.append(session.id(), ChatTurn.Role.USER, userInput);
                turns = ChatSessionStore.turns(session.id());
            }

            session = ChatSessionStore.byId(session.id());
            // The retrieved notes are charged against the same window, so they are declared here.
            // Budgeting the transcript alone was how a folded conversation could still overflow.
            if (SessionDigest.needsCompaction(session, turns, memoryContext.length())) {
                turns = SessionDigest.compact(session, turns);
                session = ChatSessionStore.byId(session.id());
            }
            String history = ChatSessionStore.transcript(session, turns);

            if (escalating) {
                if (!cloud.available()) {
                    LOGGER.warn("  ⚠ No cloud provider is configured, so there is nothing to escalate to.");
                    LOGGER.warn("    {}", cloud.why());
                    LOGGER.warn("    The local model is still answering; carry on typing.");
                    continue;
                }
                escalate(session, target, cloud, localClient, memoryContext, history, lastUserPrompt);
            } else if (localClient != null) {
                answerLocally(session, target, localClient, memoryContext, history);
            } else {
                // No local model, so the cloud is not an escalation here -- it is the only thing
                // that writes. Answering with it directly is the point; making the user press 'y'
                // every turn to reach the one model they have would be ceremony.
                escalate(session, target, cloud, null, memoryContext, history, lastUserPrompt);
            }
            turns = ChatSessionStore.turns(session.id());
        }
    }

    private void banner(
            ChatSession session, Issue target, String modelName, Cloud cloud, List<ChatTurn> turns, Backends backends) {
        LOGGER.info("==================================================");
        LOGGER.info(" Issue #{} — {}", session.issueNumber(), target.title());
        LOGGER.info(" {} · conversation {}", session.repository(), session.id());
        // Names what is actually answering. "Local: null" would be worse than useless, and a user
        // running on a cloud key needs to know every turn is leaving the machine.
        if (modelName != null) {
            LOGGER.info(" Local: {} · escalation: {}", modelName, cloud.name());
        } else {
            LOGGER.info(" Local: none · answering with: {}", cloud.name());
            LOGGER.info(" Every turn goes to {}. Attach a local model to keep them here.", cloud.name());
        }
        LOGGER.info("==================================================");

        if (!turns.isEmpty()) {
            LOGGER.info("");
            LOGGER.info(" Resuming — {} turns, last active {} ago.", turns.size(), session.ageLabel());
            if (session.summary() != null && !session.summary().isBlank()) {
                LOGGER.info(" Earlier turns are folded into a summary the model can still see.");
            }
            LOGGER.info("");
            List<ChatTurn> replay =
                    turns.size() <= REPLAY_TURNS ? turns : turns.subList(turns.size() - REPLAY_TURNS, turns.size());
            if (replay.size() < turns.size()) {
                LOGGER.info(
                        "   … {} earlier turns (oss history --show {})", turns.size() - replay.size(), session.id());
            }
            for (ChatTurn t : replay) {
                LOGGER.info("   {}: {}", t.role().label(), oneLine(t.content()));
            }
        }
        LOGGER.info("");
        if (backends.escalates()) {
            LOGGER.info(" 'y' escalates the last question · 'exit' saves and stops");
        } else {
            LOGGER.info(" 'exit' saves and stops");
        }
        LOGGER.info(" '/context' shows how full this conversation is · '/compact' folds it now");
        LOGGER.info(" ctrl-c is safe: every turn is already saved.");
    }

    /**
     * How much of the window this conversation is using.
     *
     * <p>Compaction is lossy however well it is done, so the moment it happens should never be the
     * first the user hears of it. A bar they can ask for at any time turns "why did it suddenly
     * forget the start of this?" into something they saw coming and could have acted on.
     */
    private void reportContext(ChatSession session, List<ChatTurn> turns, String memoryContext) {
        int used = SessionDigest.used(session, turns);
        int budget = SessionDigest.budgetChars(memoryContext.length());
        int percent = budget == 0 ? 100 : Math.min(999, (int) Math.round(100.0 * used / budget));

        int filled = Math.min(20, percent / 5);
        String bar = "█".repeat(filled) + "░".repeat(20 - filled);

        LOGGER.info("");
        LOGGER.info("  {} {}%  ({} of {} characters)", bar, percent, used, budget);
        LOGGER.info("  conversation: {} turns · retrieved notes: {} characters", turns.size(), memoryContext.length());
        if (session.summary() != null && !session.summary().isBlank()) {
            LOGGER.info("  earlier turns are already folded into a summary — `oss history --show {}`", session.id());
        }
        if (percent >= 100) {
            LOGGER.info("  the next turn will fold the older half automatically.");
        } else {
            LOGGER.info("  `/compact` folds it now; otherwise it happens on its own at 100%.");
        }
        LOGGER.info("");
    }

    private void answerLocally(
            ChatSession session, Issue target, OllamaClient localClient, String memoryContext, String history) {
        String prompt = String.format(
                """
                You are an expert maintainer for '%s' acting as a live pair-programmer.
                We are actively resolving Issue #%d: %s

                --- RELEVANT PAST EXPERIENCES ---
                %s

                --- CONVERSATION HISTORY ---
                %s

                Please respond directly to the User's last message. Provide code snippets if requested.
                """,
                session.repository(),
                session.issueNumber(),
                target.title(),
                memoryContext.isEmpty() ? "(None)" : memoryContext,
                history);

        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("thinking locally")) {
            String out = localClient.generateText(prompt);
            live.done("answered");
            print("LOCAL RESPONSE", out);
            store(session, ChatTurn.Role.LOCAL, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted.");
        } catch (Exception e) {
            LOGGER.error("Local generation failed: {}", e.getMessage());
            LOGGER.error("  Your question is saved; try again or type 'y' to escalate.");
            // Saved, so nothing is lost by retrying -- that is the whole point of storing the turn
            // before the model call rather than after it.
        }
    }

    private void escalate(
            ChatSession session,
            Issue target,
            Cloud cloud,
            OllamaClient localClient,
            String memoryContext,
            String history,
            String lastUserPrompt) {

        // Counted here rather than threaded down from the caller: the retrieval is cached, and a
        // ledger that reported different numbers from the context actually sent would be worse
        // than no ledger at all.
        com.osscli.retrieval.Sources.Retrieved retrieved =
                com.osscli.retrieval.Sources.count(session.issueNumber(), session.repository());

        String cloudPrompt = String.format(
                """
                You are an expert maintainer for the '%s' open-source repository.
                We are actively resolving Issue #%d: %s
                Body: %s

                --- CONVERSATION HISTORY ---
                %s

                --- NEW PROMPT ---
                %s

                Provide a highly technical, expert code resolution for the new prompt.
                """,
                session.repository(), session.issueNumber(), target.title(), target.body(), history, lastUserPrompt);

        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("asking " + cloud.name())) {
            String cloudOutput = cloud.generate(cloudPrompt);

            // Alignment reads the cloud's answer back against the user's own past work, and only a
            // local model can do it -- sending their PR history to the same API would undo the
            // reason for splitting the two steps in the first place. Without one, the cloud answer
            // stands on its own and the loss is stated rather than quietly absorbed: an answer that
            // has NOT been checked against your history looks exactly like one that has.
            if (localClient == null) {
                live.done("answered");
                print(cloud.name().toUpperCase(java.util.Locale.ROOT) + " RESPONSE", cloudOutput);
                com.osscli.retrieval.Sources.report(
                        session.repository(),
                        session.issueNumber(),
                        retrieved,
                        cloud.name(),
                        false,
                        "no local model — the API that wrote the answer cannot also check it");
                store(session, ChatTurn.Role.CLOUD, cloudOutput);
                return;
            }

            live.step("aligning with your own history");

            String alignmentPrompt = String.format(
                    """
                    You are a personal developer copilot.
                    An online expert AI provided this code solution:
                    %s

                    Here is my personal development memory (my past PRs and edits):
                    %s

                    Compare the expert solution with my memory. Output a final response that includes:
                    1. The expert solution.
                    2. ALIGNMENT CHECK: Does this solution match my past coding patterns?
                    3. Which specific local files from my past PRs should I apply this to?
                    """, cloudOutput, memoryContext.isEmpty() ? "(No local memory found)" : memoryContext);

            String aligned = localClient.generateText(alignmentPrompt);
            live.done("answered");
            print("EXPERT ALIGNED RESPONSE", aligned);
            com.osscli.retrieval.Sources.report(
                    session.repository(), session.issueNumber(), retrieved, cloud.name(), true, null);
            store(session, ChatTurn.Role.CLOUD, aligned);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted.");
        } catch (Exception e) {
            LOGGER.error("Cloud escalation failed: {}", e.getMessage());
            LOGGER.error("  Nothing was lost; the conversation is still here.");
        }
    }

    private void print(String heading, String body) {
        LOGGER.info("\n================ {} ================", heading);
        LOGGER.info("\n{}\n", body);
        LOGGER.info("=========================================================\n");
    }

    /**
     * Stores an answer the user is already reading.
     *
     * <p>A failure here must not look like a failure of the answer, so it is reported as what it is:
     * this one turn will not come back tomorrow.
     */
    private void store(ChatSession session, ChatTurn.Role role, String content) {
        try {
            ChatSessionStore.append(session.id(), role, content);
        } catch (java.sql.SQLException e) {
            LOGGER.warn("  ⚠ That answer could not be saved to the conversation: {}", e.getMessage());
            LOGGER.warn("    It is on screen but will not be there when you resume.");
        }
    }

    // ==========================================
    // Ending
    // ==========================================

    /**
     * Ends the session and files it in the archive.
     *
     * <p>Filed under the path the session already has, when it has one, so a conversation resumed
     * three times becomes one note that grew rather than three overlapping notes competing to answer
     * the same question.
     */
    private void finish(ChatSession session, Issue target) throws Exception {
        LOGGER.info("Saving and indexing this conversation...");
        List<ChatTurn> turns = ChatSessionStore.turns(session.id());
        ChatSession current = ChatSessionStore.byId(session.id());

        String overview = SessionDigest.overview(current, turns);
        ChatSessionStore.setOverview(session.id(), overview);

        Path reuse = current.notePath() == null ? null : Path.of(current.notePath());
        Path written = com.osscli.knowledge.ResolutionWriter.record(
                current.repository(),
                current.issueNumber(),
                target == null ? null : target.title(),
                "chat",
                overview,
                ChatSessionStore.transcript(current, turns),
                "oss-cli",
                "chat",
                reuse);
        if (written != null) {
            ChatSessionStore.setNotePath(session.id(), written.toAbsolutePath().toString());
        }

        ChatSessionStore.end(session.id());
        LOGGER.info("");
        LOGGER.info("  {}", overview);
        LOGGER.info("  Resume it any time: oss chat --resume {}", session.id());
    }

    // ==========================================
    // Context
    // ==========================================

    private boolean resolveRepository() throws java.sql.SQLException {
        if (repository != null && !repository.isBlank()) {
            return true;
        }
        repository = SqliteStorage.loadConfig("default.repository");
        if (repository == null || repository.trim().isEmpty()) {
            LOGGER.error("No repository. Use '-r owner/name', or run 'oss setup' to set a default.");
            return false;
        }
        return true;
    }

    private String scopeSuffix() {
        if (repository != null && issueNumber != null) {
            return " for " + repository + " #" + issueNumber;
        }
        if (repository != null) {
            return " for " + repository;
        }
        return "";
    }

    private static Issue findIssue(String repository, long number) throws Exception {
        for (Issue i : SqliteStorage.loadIssues(repository)) {
            if (i.number() == number) {
                return i;
            }
        }
        for (Issue p : SqliteStorage.loadPullRequests(repository)) {
            if (p.number() == number) {
                return p;
            }
        }
        return null;
    }

    /**
     * The user's own past work, budgeted to fit the prompt.
     *
     * <p>This used to walk every stored note and append the whole of any that scored above 0.35,
     * with no cap at all. On a corpus of 592 notes totalling 34 MB that produced a prompt of roughly
     * 19 MB for a model with a 6,000-token window, and the request simply timed out -- which reads
     * as a slow machine rather than a prompt that could never have worked.
     */
    private static String buildMemoryContext(String repository, long issueNumber) {
        return com.osscli.retrieval.MemoryContext.forIssue(issueNumber, repository);
    }

    private static String lastUserPrompt(List<ChatTurn> turns) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == ChatTurn.Role.USER) {
                return turns.get(i).content();
            }
        }
        return "";
    }

    private static String oneLine(String s) {
        String flat = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return flat.length() <= 90 ? flat : flat.substring(0, 89) + "…";
    }

    private String providerName() {
        for (com.osscli.llm.Ai.Engine e : com.osscli.llm.Ai.escalationPath()) {
            switch (e) {
                case OPENAI:
                    return "openai";
                case CLAUDE:
                    return "claude";
                case GEMINI:
                    return "gemini";
                default:
                    break;
            }
        }
        return "local";
    }

    // ==========================================
    // Cloud escalation
    // ==========================================

    /**
     * Which model, if any, is going to write the answers.
     *
     * <p>Four states, and the whole point of naming them is that three of them work. Chat needs a
     * model that writes; it does not need a <em>particular</em> one, and every decision downstream
     * -- what the banner says, whether {@code y} means anything, whether an answer can be checked
     * against the user's own history -- falls out of which of these four it is.
     */
    enum Backends {
        /** Ollama and a cloud key. Local answers, {@code y} escalates, escalations get aligned. */
        BOTH,
        /** Ollama only. Local answers, nothing to escalate to. */
        LOCAL_ONLY,
        /** A cloud key only. The cloud answers directly, and cannot be aligned against local history. */
        CLOUD_ONLY,
        /** Neither. The one state that refuses. */
        NONE;

        static Backends of(boolean local, boolean cloud) {
            if (local && cloud) {
                return BOTH;
            }
            if (local) {
                return LOCAL_ONLY;
            }
            return cloud ? CLOUD_ONLY : NONE;
        }

        /** Whether a conversation can happen at all. */
        boolean canAnswer() {
            return this != NONE;
        }

        /** Whether answers stay on this machine. */
        boolean staysLocal() {
            return this == BOTH || this == LOCAL_ONLY;
        }

        /**
         * Whether {@code y} does anything.
         *
         * <p>False for {@link #CLOUD_ONLY} because there is nothing to escalate <em>from</em>: the
         * cloud is already answering every turn, so offering the key would promise a second opinion
         * that is the same opinion.
         */
        boolean escalates() {
            return this == BOTH;
        }

        /**
         * Whether a cloud answer can be read back against the user's own past work.
         *
         * <p>Only a local model can do it. Sending a PR history to the same API that produced the
         * answer would undo the reason the two steps are separate.
         */
        boolean canAlign() {
            return this == BOTH;
        }
    }

    /**
     * The optional second opinion.
     *
     * <p>This used to be resolved up front and to <b>fail the whole command</b> when no key was
     * found -- so a user with a local model and no cloud account could not open a chat at all, on a
     * tool whose first rule is that no capability is mandatory. Now a missing key costs the
     * escalation key, and only when it is pressed.
     */
    private static final class Cloud {

        private final String name;
        private final String why;
        private final GeminiClient gemini;
        private final OpenAiClient openAi;
        private final ClaudeClient claude;

        private Cloud(String name, String why, GeminiClient gemini, OpenAiClient openAi, ClaudeClient claude) {
            this.name = name;
            this.why = why;
            this.gemini = gemini;
            this.openAi = openAi;
            this.claude = claude;
        }

        /**
         * The cloud engine named in front of the command, if any.
         *
         * <p>Was three boolean flags on this command alone, spelled differently from the same
         * choice on {@code review}. The engine is one decision about the whole invocation, so it
         * is read from one place.
         */
        static Cloud forEngine() {
            java.util.List<com.osscli.llm.Ai.Engine> path = com.osscli.llm.Ai.engines();
            boolean useOpenAi = path.contains(com.osscli.llm.Ai.Engine.OPENAI);
            boolean useClaude = path.contains(com.osscli.llm.Ai.Engine.CLAUDE);
            boolean useGemini = path.contains(com.osscli.llm.Ai.Engine.GEMINI);
            if (!useOpenAi && !useClaude && !useGemini) {
                return none("cloud", "no engine named — try 'oss claude chat', 'oss gemini chat' or 'oss codex chat'.");
            }
            if (useOpenAi) {
                return key("OPENAI_API_KEY", "openai_api_key") == null
                        ? none("OpenAI", "OPENAI_API_KEY is not set and no openai_api_key is in the keychain.")
                        : new Cloud("OpenAI", null, null, new OpenAiClient(configOr("openai.model", "gpt-4o")), null);
            }
            if (useClaude) {
                return key("ANTHROPIC_API_KEY", "anthropic_api_key") == null
                        ? none("Claude", "ANTHROPIC_API_KEY is not set and no anthropic_api_key is in the keychain.")
                        : new Cloud(
                                "Claude",
                                null,
                                null,
                                null,
                                new ClaudeClient(configOr("claude.model", "claude-3-5-sonnet-20240620")));
            }
            return key("GEMINI_API_KEY", "gemini_api_key") == null
                    ? none("Gemini", "GEMINI_API_KEY is not set and no gemini_api_key is in the keychain.")
                    : new Cloud(
                            "Gemini",
                            null,
                            new GeminiClient(configOr("gemini.model", "gemini-1.5-flash-latest")),
                            null,
                            null);
        }

        private static Cloud none(String wanted, String why) {
            return new Cloud(wanted + " (not configured)", why, null, null, null);
        }

        boolean available() {
            return gemini != null || openAi != null || claude != null;
        }

        String name() {
            return available() ? name : "none — local only";
        }

        String why() {
            return why == null ? "" : why;
        }

        String generate(String prompt) throws Exception {
            if (openAi != null) {
                return openAi.generateText(prompt);
            }
            if (claude != null) {
                return claude.generateText(prompt);
            }
            return gemini.generateText(prompt);
        }

        private static String configOr(String configKey, String fallback) {
            try {
                String v = SqliteStorage.loadConfig(configKey);
                return v == null || v.isBlank() ? fallback : v;
            } catch (java.sql.SQLException e) {
                // A model name that cannot be read is not worth failing an optional capability over.
                return fallback;
            }
        }

        /** Environment first, then the macOS keychain, which is where {@code oss setup} can put it. */
        private static String key(String envName, String keychainName) {
            String key = System.getenv(envName);
            if (key != null && !key.trim().isEmpty()) {
                return key;
            }
            try {
                Process process = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "security find-generic-password -s " + keychainName + " -w 2>/dev/null || true"
                });
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        process.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
            } catch (Exception ignored) {
                // No keychain (linux, windows, a locked keyring): treated as no key, which is true.
            }
            return null;
        }
    }
}
