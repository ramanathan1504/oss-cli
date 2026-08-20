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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.model.PrEvidence;
import com.osscli.review.PrEvidenceFetcher;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reviews a pull request from whatever the user has connected.
 *
 * <p>Built as a ladder rather than a pipeline. Layer 0 -- the facts -- needs only a GitHub token, and every layer above
 * it is optional: a repository profile adds convention checks, Ollama adds a verdict, a cloud key adds escalation, a
 * notes corpus adds history. A user with none of those still gets a working review.
 *
 * <p>Missing layers are reported as one line each, saying what they would add and how to enable them. Silently
 * producing a thinner review would leave the user unable to tell a clean pull request from an unexamined one.
 */
@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        description = "Review a pull request using every source you have connected")
public class ReviewCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ReviewCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Enough prior work to inform a judgment, few enough that the diff stays the bulk of the prompt. */
    private static final int MAX_NOTES = 5;

    private static final int NOTE_EXCERPT_CHARS = 1200;

    /** Diff characters a local model is asked to read. Beyond this it is truncated, or escalated when allowed. */
    private static final int LOCAL_DIFF_BUDGET = 24000;

    /** Set once the verdict came from a cloud model, so the layer summary reports the route actually taken. */
    private boolean escalated;

    /** Set when a cloud call was made at all, whether or not it came back with anything. */
    private boolean escalationAttempted;

    @Parameters(index = "0", description = "The pull request number to review")
    private long prNumber;

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository in 'owner/name' format")
    private String repository;

    @Option(
            names = {"--refresh"},
            description = "Re-fetch from GitHub even when this exact commit is already cached")
    private boolean refresh;

    @Option(
            names = {"--no-verdict"},
            description = "Report the facts only, without asking a model to judge the change")
    private boolean noVerdict;

    @Option(
            names = {"--verify"},
            description = "Build the change and re-run its tests with it reverted, in a throwaway worktree")
    private boolean verify;

    @Option(
            names = {"--clone"},
            paramLabel = "<path>",
            description = "A checkout of the repository, for --verify. Never modified.")
    private java.nio.file.Path clone;

    @Option(
            names = {"--no-notes"},
            description = "Do not consult your own notes when reviewing")
    private boolean noNotes;

    @Override
    public Integer call() throws Exception {
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.isBlank()) {
                LOGGER.error("No repository specified. Use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        LOGGER.info("Reviewing {} #{}...", repository, prNumber);

        PrEvidence ev;
        try {
            ev = PrEvidenceFetcher.fetch(repository, prNumber, refresh);
        } catch (IllegalArgumentException e) {
            LOGGER.error("{}", e.getMessage());
            return 1;
        } catch (java.io.IOException | InterruptedException | RuntimeException e) {
            // This catch used to name IllegalArgumentException alone, so a connect failure -- not
            // one -- escaped into picocli and reached the user as forty lines of
            // jdk.internal.net.http stack. A review needs the network and cannot be done without
            // it; that is a sentence, not a crash.
            //
            // RuntimeException is here for the same reason and was found the same way: with no
            // token registered, CredentialManager throws one, and CI -- which has no token --
            // printed a second stack trace under the message it had already logged.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.error("{}", com.osscli.github.Reachability.describe(e));
            return 1;
        }

        printFacts(ev);
        com.osscli.model.RepoProfile profile = SqliteStorage.loadRepoProfile(repository);
        boolean conventionsChecked = printConventionChecks(ev, profile);

        List<com.osscli.model.PromptContextChunk> notes = noNotes ? List.of() : findRelatedNotes(ev);
        printRelatedNotes(notes);

        boolean verdictGiven = !noVerdict && printVerdict(ev, profile, notes);
        boolean verified = verify && printVerification(ev);
        printLadder(verdictGiven, conventionsChecked, !notes.isEmpty(), verified);
        return 0;
    }

    /**
     * Build the change and find out whether its tests mean anything.
     *
     * <p>The one layer that produces facts rather than opinions. Everything above reads the diff and
     * says something about it; this compiles it, runs the tests it adds, takes the production change
     * back out, and runs them again. A test that passes both ways is the finding -- it would have
     * passed before the bug was fixed, which is invisible to reading and decisive to a reviewer.
     */
    private boolean printVerification(PrEvidence ev) {
        List<String> changed = new ArrayList<>();
        try {
            for (Map<String, Object> f : readList(ev.filesJson())) {
                changed.add(String.valueOf(f.get("filename")));
            }
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Could not read the file list: {}", e.getMessage());
            return false;
        }

        LOGGER.info("");
        LOGGER.info("── Verification (built and run, not read) ──");
        // Two Maven runs over somebody else's repository is minutes, and this printed one line at
        // the start of each and then nothing -- which is the case Live exists for. The rule in this
        // repository is that anything slower than a second says what it is doing while it does it,
        // and the newest command was the one breaking it.
        com.osscli.review.Verifier.Report report;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("verify")) {
            report = com.osscli.review.Verifier.verify(clone, ev.headSha(), null, ev.baseRef(), changed, line -> {
                live.step(line);
                LOGGER.info("  ↳ {}", line);
            });
        }

        if (!report.ran()) {
            LOGGER.info("  ○ not verified — {}", report.why());
            return false;
        }
        for (com.osscli.review.Verifier.Step step : report.steps()) {
            LOGGER.info(
                    "  {} {}{}",
                    step.outcome() == com.osscli.review.Verifier.Outcome.PASSED ? "✔" : "✘",
                    step.what(),
                    step.detail().isEmpty() ? "" : " — " + step.detail());
        }
        LOGGER.info("");
        for (com.osscli.review.Verifier.TestResult t : report.tests()) {
            switch (t.verdict()) {
                case PROVEN -> LOGGER.info("  ✔ {} — {}", t.testClass(), t.detail());
                case PROVES_NOTHING -> {
                    // The whole reason this layer exists.
                    LOGGER.info("  ⚠ {} — {}", t.testClass(), t.detail());
                    LOGGER.info("      It would have passed before the change, so it is not covering it.");
                }
                default -> LOGGER.info("  ○ {} — {}", t.testClass(), t.detail());
            }
        }
        if (report.why() != null) {
            LOGGER.info("  {}", report.why());
        }
        // Only a run that got all the way through re-running the tests may tick the ladder. It used
        // to return true here whatever happened, so a verification that stopped at "could not revert
        // the production change" still printed "Built and re-run with the change reverted" -- the
        // summary contradicting the section immediately above it.
        return report.why() == null;
    }

    // ── Layer 4: the user's own notes ────────────────────────────────────────

    /**
     * Finds prior work bearing on this change.
     *
     * <p>The query is built from the title and the changed paths rather than the diff. Paths are what a note about the
     * same subsystem is likely to share; diff bodies are dominated by syntax that matches everything weakly and
     * nothing well.
     *
     * <p>Notes this tool generated for the same pull request are excluded. Feeding a review its own earlier verdict
     * reads as independent corroboration while being an echo, and the same reasoning would then appear to be supported
     * by evidence that is merely a copy of itself.
     */
    private List<com.osscli.model.PromptContextChunk> findRelatedNotes(PrEvidence ev) throws Exception {
        StringBuilder query = new StringBuilder();
        if (ev.title() != null) {
            query.append(ev.title()).append('\n');
        }
        for (Map<String, Object> f : readList(ev.filesJson())) {
            query.append(String.valueOf(f.get("filename"))).append('\n');
        }

        return com.osscli.retrieval.NoteRetriever.retrieveFor(
                query.toString(), MAX_NOTES, "Issue-" + ev.prNumber() + "-review");
    }

    private void printRelatedNotes(List<com.osscli.model.PromptContextChunk> notes) {
        if (notes.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("── Your prior work on this area ({}) ──", notes.size());
        for (com.osscli.model.PromptContextChunk n : notes) {
            LOGGER.info("  {}  ({}% match)", n.sourceRef(), Math.round(n.relevanceScore() * 100));
        }
    }

    // ── Layer 1: convention checks, no model involved ────────────────────────

    /**
     * Compares the change against the gates this project actually enforces.
     *
     * <p>Deterministic on purpose. These are the findings a maintainer must be able to trust without wondering whether
     * a model inferred them, and they stay available to a user who has connected nothing but a token.
     */
    private boolean printConventionChecks(PrEvidence ev, com.osscli.model.RepoProfile profile) throws Exception {
        if (profile == null) {
            return false;
        }

        Map<String, String> conventions = MAPPER.readValue(
                profile.conventionsJson() == null ? "{}" : profile.conventionsJson(),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));

        List<Map<String, Object>> files = readList(ev.filesJson());
        List<String> notes = new ArrayList<>();

        boolean apiGated = conventions.keySet().stream()
                .anyMatch(k -> k.equals("bnd-baseline-maven-plugin")
                        || k.equals("japicmp")
                        || k.equals("revapi")
                        || k.equals("Export-Package"));

        long addedSources = files.stream()
                .filter(f -> "added".equals(String.valueOf(f.get("status"))))
                .map(f -> String.valueOf(f.get("filename")))
                // Not "anything under src/". An API baseline is about public types, so it is main
                // Java source or nothing -- and that filter counted 4249's changelog XML as a new
                // source file, then told the author their changelog entry might need a baseline or
                // export update. One implementation of "is this production source", in Verifier.
                .filter(com.osscli.review.Verifier::isMainSource)
                .count();

        if (apiGated && addedSources > 0) {
            notes.add(String.format(
                    "%d new source file(s) added, and this project gates its public API "
                            + "(%s). New public types may require baseline or export updates.",
                    addedSources, String.join(", ", apiGatedMarkers(conventions))));
        }

        if (conventions.containsKey("spotless")) {
            notes.add("Formatting is enforced by spotless — the build fails on unformatted code.");
        }
        if (profile.targetVersion() != null) {
            notes.add("Target toolchain is " + profile.targetVersion()
                    + (profile.minVersion() == null ? "" : " (minimum " + profile.minVersion() + ")")
                    + " — language features beyond it will not compile.");
        }
        if (conventions.containsKey("inherits-from")) {
            notes.add("Build rules are inherited from " + conventions.get("inherits-from")
                    + " — conventions may not be visible in this repository.");
        }

        LOGGER.info("");
        LOGGER.info("── Project conventions ──");
        if (notes.isEmpty()) {
            LOGGER.info("  no gate in this project's profile applies to these files");
        }
        notes.forEach(n -> LOGGER.info("  • {}", n));
        return true;
    }

    private List<String> apiGatedMarkers(Map<String, String> conventions) {
        List<String> markers = new ArrayList<>();
        for (String k : new String[] {"bnd-baseline-maven-plugin", "japicmp", "revapi", "Export-Package"}) {
            if (conventions.containsKey(k)) {
                markers.add(k);
            }
        }
        return markers;
    }

    // ── Layer 2: a local verdict, when Ollama is connected ───────────────────

    /**
     * Asks the local model to judge the change against its own evidence.
     *
     * @return true only if a verdict was actually produced, so the layer summary can report what happened rather than
     *     what was merely available. Reporting the capability as active while printing no verdict is the same class of
     *     defect as a sync that reports success after failing.
     */
    private boolean printVerdict(
            PrEvidence ev, com.osscli.model.RepoProfile profile, List<com.osscli.model.PromptContextChunk> notes) {
        try {
            String model = SqliteStorage.loadConfig("ollama.model.guidance");
            if (model == null || model.isBlank()) {
                model = "qwen2.5-coder:7b";
            }
            com.osscli.llm.OllamaClient ollama = new com.osscli.llm.OllamaClient(model);

            String fullDiff = ev.diff() == null ? "" : ev.diff();
            boolean oversized = fullDiff.length() > LOCAL_DIFF_BUDGET;

            // The local rung, and whether it can actually answer. Ollama is asked only when it was
            // asked for: `oss review` on a machine that happens to run a daemon used to get a local
            // verdict nobody requested, which is the thing the engine prefixes exist to end.
            boolean localOffered = com.osscli.llm.Ai.engines().contains(com.osscli.llm.Ai.Engine.OLLAMA);
            boolean localReady = localOffered && ollama.isServerReachable();

            // Escalate on a stated test, never on a hunch: either the local rung cannot answer at
            // all, or it can but only by reading part of the change. A diff that already fits and a
            // rung that can read it are a question this machine can answer, and paying for a call
            // anyway is how a tool teaches you to distrust its judgement about when it needs help.
            String provider = escalationProvider();
            boolean useCloud = provider != null && (!localReady || oversized);

            if (provider != null && localReady && !oversized) {
                LOGGER.info("");
                LOGGER.info("  ↳ {} answered and the diff fits its budget — no call to {} needed.", model, provider);
            }
            if (!useCloud && !localReady) {
                LOGGER.info("");
                if (!localOffered) {
                    LOGGER.info("  ↳ No engine was named, so no verdict is written here.");
                    LOGGER.info("     oss llm review {}      a local Ollama verdict", prNumber);
                    LOGGER.info("     oss claude review {}   Claude reads the whole diff", prNumber);
                } else {
                    LOGGER.info("  ↳ Ollama is not reachable — start it with 'ollama serve'.");
                }
                return false;
            }

            String diff = useCloud || !oversized ? fullDiff : fullDiff.substring(0, LOCAL_DIFF_BUDGET);
            boolean truncated = !useCloud && oversized;

            LOGGER.info("");
            if (useCloud) {
                // Say which of the two reasons it actually was. Printing "over the local budget"
                // for a 12k diff against a 24k budget is a sentence the reader can check and find
                // false, and once one line is provably wrong the rest of the report is suspect.
                if (!localReady) {
                    LOGGER.info("  ↳ No local engine was named, so {} answers with the full diff...", provider);
                } else {
                    LOGGER.info(
                            "  ↳ Diff is {} chars, over the {} local budget — escalating to {} with the full diff...",
                            fullDiff.length(),
                            LOCAL_DIFF_BUDGET,
                            provider);
                }
            } else {
                LOGGER.info("  ↳ Asking {} for a verdict{}...", model, truncated ? " (diff truncated)" : "");
            }

            // The profile is what turns a generic code opinion into a project-specific one: without it the model has
            // no idea this project gates exported packages or targets a particular toolchain.
            String projectRules = profile == null || profile.summary() == null
                    ? "(no profile built for this project — judge on general grounds only)"
                    : profile.summary();

            // Labelled as the reviewer's own material and capped, so it informs the judgment without displacing the
            // diff. Notes are context for reading the change, never a substitute for reading it.
            String priorWork = "(none found in your notes)";
            if (!notes.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (com.osscli.model.PromptContextChunk n : notes) {
                    String body = n.content().length() > NOTE_EXCERPT_CHARS
                            ? n.content().substring(0, NOTE_EXCERPT_CHARS) + "…"
                            : n.content();
                    sb.append("--- ")
                            .append(n.sourceRef())
                            .append(" ---\n")
                            .append(body)
                            .append("\n\n");
                }
                priorWork = sb.toString();
            }

            String prompt = String.format(
                    """
                    You are reviewing a pull request for the open-source project %s.

                    PROJECT RULES (what this project enforces):
                    %s

                    YOUR PRIOR WORK ON THIS AREA (from the reviewer's own notes):
                    %s

                    Title: %s
                    Author: %s
                    Target branch: %s
                    Size: %d files, +%d -%d

                    DIFF%s:
                    %s

                    Every concern must name one of the changed files and say what is wrong in it.
                    Naming the subject of the change ("circular reference handling") is a description
                    of the diff, not a concern; if you have none, return an empty list.

                    Review THE DIFF and nothing else. The project rules and prior-work sections
                    are background: cite them only where they bear on this diff, and never review
                    them — they are not the change. Report only what the diff actually shows; if
                    something cannot be determined from it, say so rather than guessing.

                    Respond in JSON with this exact structure:
                    {
                      "summary": "<what this change does, one or two sentences>",
                      "concerns": ["<one of the changed file names> — <the specific, actionable problem in it>"],
                      "questions": ["<what you would ask the author>"],
                      "confidence": <0.0 to 1.0>
                    }
                    """,
                    ev.repository(),
                    projectRules,
                    priorWork,
                    ev.title(),
                    ev.author(),
                    ev.baseRef(),
                    ev.changedFiles(),
                    ev.additions(),
                    ev.deletions(),
                    truncated ? " (truncated — judge only what is shown)" : "",
                    diff);

            escalationAttempted = useCloud;
            String raw = useCloud ? sendToCloud(provider, prompt) : ollama.generateJson(prompt);
            if (raw == null) {
                LOGGER.warn("  ⚠ No response from {} — the facts above are unaffected.", useCloud ? provider : model);
                return false;
            }

            JsonNode node = MAPPER.readTree(extractJson(raw));
            String answeredBy = useCloud ? provider : model;

            LOGGER.info("");
            // "confidence 80%" is the model scoring itself, and a small one scores itself high on
            // an answer that found nothing. Printed bare it reads as a measurement somebody took.
            LOGGER.info(
                    "── Verdict ({}, {} confidence claimed) ──",
                    answeredBy,
                    String.format("%.0f%%", node.path("confidence").asDouble(0.5) * 100));
            LOGGER.info("  {}", node.path("summary").asText(""));

            List<String> rawConcerns = new ArrayList<>();
            for (JsonNode c : node.path("concerns")) {
                rawConcerns.add(c.asText(""));
            }
            com.osscli.review.Findings.Located located =
                    com.osscli.review.Findings.locate(rawConcerns, changedPaths(ev));
            printStrings("Concerns", located.concerns());
            if (located.unlocated() > 0) {
                LOGGER.info("");
                LOGGER.info(
                        "  {} concern(s) named no file in the change and are not shown — the model",
                        located.unlocated());
                LOGGER.info("  described the diff rather than reviewing it.");
            }
            printBullets("Questions for the author", node.path("questions"));

            if (truncated) {
                LOGGER.info("");
                LOGGER.info("  Note: the diff exceeded the local budget and was truncated.");
                LOGGER.info("        Findings cover only the portion shown above.");
                LOGGER.info("        oss claude review {} sends the whole diff instead.", prNumber);
            }

            escalated = useCloud;
            recordReview(ev, answeredBy, node);
            return true;

        } catch (Exception e) {
            LOGGER.warn("  ⚠ Local verdict unavailable: {}", e.getMessage());
            LOGGER.warn("    The facts above are unaffected.");
            return false;
        }
    }

    /**
     * Which external engine may be reached, or null when none was named or none has a key.
     *
     * <p>Read from the prefix typed in front of the command rather than from a flag on it, so the
     * answer to "whose model saw my code" is the line you typed. A prefix naming an engine with no
     * key says so here instead of failing at the end of a long review.
     */
    private String escalationProvider() {
        List<com.osscli.llm.Ai.Engine> path = com.osscli.llm.Ai.escalationPath();
        for (com.osscli.llm.Ai.Engine missing : com.osscli.llm.Ai.missingCredentials()) {
            LOGGER.warn("  ⚠ {} was named and has no key configured — 'oss setup'.", missing.typed());
        }
        if (path.isEmpty()) {
            return null;
        }
        switch (path.get(0)) {
            case CLAUDE:
                return "claude";
            case OPENAI:
                return "openai";
            case GEMINI:
                return "gemini";
            default:
                return null;
        }
    }

    private String sendToCloud(String provider, String prompt) throws Exception {
        // One dispatch, in com.osscli.llm.Cloud. This was a switch over the same three providers as
        // the one in PromptCommand, with different defaults for the same settings.
        return com.osscli.llm.Cloud.generateText(com.osscli.llm.Cloud.engineNamed(provider), prompt);
    }

    private String configOr(String key, String fallback) throws java.sql.SQLException {
        String v = SqliteStorage.loadConfig(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * Pulls the JSON object out of a model response.
     *
     * <p>Ollama is asked for JSON and returns only JSON. Cloud models are conversational by default and commonly wrap
     * the object in a sentence or a fenced code block, which parses as a failure and would discard an answer that was
     * actually correct.
     */
    private static String extractJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    /** The paths this pull request touches, or empty when the file list cannot be read. */
    private List<String> changedPaths(PrEvidence ev) {
        List<String> changed = new ArrayList<>();
        try {
            for (Map<String, Object> f : readList(ev.filesJson())) {
                changed.add(String.valueOf(f.get("filename")));
            }
        } catch (Exception e) {
            // An unreadable file list must not lose the verdict. Findings.locate keeps every
            // concern when it has no names to match against, because unable to judge and judged
            // and rejected are different answers.
            LOGGER.debug("could not read the file list for locating concerns: {}", e.getMessage());
        }
        return changed;
    }

    private void printStrings(String heading, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("  {}:", heading);
        for (String item : items) {
            LOGGER.info("    • {}", item);
        }
    }

    private void printBullets(String heading, JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("  {}:", heading);
        for (JsonNode item : array) {
            LOGGER.info("    • {}", item.asText(""));
        }
    }

    /**
     * Files the verdict with this tool's other generated output, not beside hand-written reviews.
     *
     * <p>Generated notes are kept in one folder of their own so that a note's provenance is a property of its
     * location. Mixing them into an archive's existing review folder costs two things. A knowledge base that scores
     * what its owner knows cannot then separate what they reasoned out from what a model produced from their own
     * corpus, so re-answering a question raises the score for it. And a filing tool that overwrites its previous note
     * for a pull request cannot see a generated file under a different naming scheme, so the same review accumulates
     * under two names.
     */
    private void recordReview(PrEvidence ev, String model, JsonNode verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append(verdict.path("summary").asText("")).append("\n");
        appendSection(sb, "Concerns", verdict.path("concerns"));
        appendSection(sb, "Questions", verdict.path("questions"));
        sb.append("\nHead commit: ").append(ev.headSha()).append('\n');

        // Rewrite the note this pull request already has rather than filing another. Re-reviewing
        // is normal -- after a push, with a different engine, with --verify added -- and each run
        // used to leave a copy behind for retrieval to fight over. The head commit is inside the
        // note, so what it reviewed is never in doubt.
        java.nio.file.Path existing =
                com.osscli.knowledge.ResolutionWriter.existingNote(ev.repository(), ev.prNumber(), "oss-cli", "review");
        com.osscli.knowledge.ResolutionWriter.record(
                ev.repository(), ev.prNumber(), ev.title(), model, null, sb.toString(), "oss-cli", "review", existing);
    }

    private void appendSection(StringBuilder sb, String heading, JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return;
        }
        sb.append("\n## ").append(heading).append("\n\n");
        for (JsonNode item : array) {
            sb.append("- ").append(item.asText("")).append('\n');
        }
    }

    // ── Layer 0: the facts ───────────────────────────────────────────────────

    private void printFacts(PrEvidence ev) throws Exception {
        LOGGER.info("");
        LOGGER.info("╔══════════════════════════════════════════════════════════╗");
        LOGGER.info("║  REVIEW  |  {} #{}", ev.repository(), ev.prNumber());
        LOGGER.info("╚══════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        LOGGER.info("  {}", ev.title() == null ? "(no title)" : ev.title());
        LOGGER.info(
                "  by {} · {} · into {} · head {}",
                ev.author(),
                ev.state(),
                ev.baseRef(),
                PrEvidenceFetcher.shortSha(ev.headSha()));
        LOGGER.info("  {} file(s), +{} −{}", ev.changedFiles(), ev.additions(), ev.deletions());

        printCommits(ev);
        printFiles(ev);
        printChecks(ev);
        printThreads(ev);
    }

    private void printCommits(PrEvidence ev) throws Exception {
        List<Map<String, Object>> commits = readList(ev.commitsJson());
        if (commits.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("── Commits ({}) ──", commits.size());
        for (Map<String, Object> c : commits) {
            JsonNode node = MAPPER.valueToTree(c);
            String message = node.path("commit").path("message").asText("");
            String subject = message.contains("\n") ? message.substring(0, message.indexOf('\n')) : message;
            LOGGER.info("  {}  {}", PrEvidenceFetcher.shortSha(node.path("sha").asText(null)), subject);
        }
    }

    private void printFiles(PrEvidence ev) throws Exception {
        List<Map<String, Object>> files = readList(ev.filesJson());
        if (files.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("── Files ({}) ──", files.size());

        // Grouped by top-level directory: which areas a change touches is the first thing a maintainer checks, and a
        // flat list of eighty paths hides it.
        Map<String, List<String>> byArea = new LinkedHashMap<>();
        for (Map<String, Object> f : files) {
            JsonNode node = MAPPER.valueToTree(f);
            String path = node.path("filename").asText("");
            int slash = path.indexOf('/');
            String area = slash > 0 ? path.substring(0, slash) : "(root)";
            byArea.computeIfAbsent(area, k -> new ArrayList<>())
                    .add(String.format(
                            "%s (+%d −%d)",
                            path,
                            node.path("additions").asInt(0),
                            node.path("deletions").asInt(0)));
        }
        byArea.forEach((area, paths) -> {
            LOGGER.info("  {}/", area);
            paths.forEach(p -> LOGGER.info("    {}", p));
        });
    }

    private void printChecks(PrEvidence ev) throws Exception {
        if (ev.checksJson() == null || ev.checksJson().isBlank()) {
            LOGGER.info("");
            LOGGER.info("── CI ──");
            LOGGER.info("  no check runs reported for this commit");
            return;
        }
        JsonNode root = MAPPER.readTree(ev.checksJson());
        JsonNode runs = root.path("check_runs");
        if (!runs.isArray() || runs.isEmpty()) {
            LOGGER.info("");
            LOGGER.info("── CI ──");
            LOGGER.info("  no check runs reported for this commit");
            return;
        }

        LOGGER.info("");
        LOGGER.info("── CI ({}) ──", runs.size());
        for (JsonNode run : runs) {
            String conclusion = run.path("conclusion").asText("pending");
            String marker =
                    switch (conclusion) {
                        case "success" -> "✔";
                        case "failure", "timed_out", "cancelled" -> "✖";
                        case "neutral", "skipped" -> "–";
                        default -> "…";
                    };
            LOGGER.info("  {} {}  {}", marker, run.path("name").asText(""), conclusion);
        }
    }

    private void printThreads(PrEvidence ev) throws Exception {
        List<Map<String, Object>> reviews = readList(ev.reviewsJson());
        List<Map<String, Object>> comments = readList(ev.commentsJson());

        LOGGER.info("");
        LOGGER.info("── Discussion ──");
        LOGGER.info("  {} review(s), {} inline comment(s)", reviews.size(), comments.size());

        for (Map<String, Object> r : reviews) {
            JsonNode node = MAPPER.valueToTree(r);
            String state = node.path("state").asText("");
            if (!state.isBlank()) {
                LOGGER.info("    {} — {}", node.path("user").path("login").asText("?"), state);
            }
        }
    }

    // ── The ladder: what is on, what is not, and how to turn it on ───────────

    /**
     * Reports the sources this run actually drew on.
     *
     * <p>Deliberately reports use rather than availability. A tick against something the review did not consult would
     * tell the reader their notes had been weighed when they had not -- and a review is trusted precisely because of
     * what went into it.
     */
    private void printLadder(boolean verdictGiven, boolean conventionsChecked, boolean notesUsed, boolean verified) {
        LOGGER.info("");
        LOGGER.info("── What this review used ──");
        LOGGER.info("  ✔ Facts from GitHub");
        report(conventionsChecked, "Convention checks against this project's rules", "run 'profile' to build it");
        report(notesUsed, "Your own prior work", whyNoNotes());
        report(
                verdictGiven && !escalated,
                "Local verdict from Ollama",
                noVerdict
                        ? "skipped by --no-verdict"
                        : (escalated
                                ? "escalated instead"
                                : (com.osscli.llm.Ai.engines().contains(com.osscli.llm.Ai.Engine.OLLAMA)
                                        ? "start Ollama with 'ollama serve'"
                                        : "not asked for — oss llm review " + prNumber)));
        report(escalated, "Escalation to a cloud model", whyNoEscalation());
        report(verified, "Built and re-run with the change reverted", verify ? "could not be run" : "--verify");
    }

    /**
     * Separates the three ways a review can go without a cloud call.
     *
     * <p>"You did not ask", "you asked and there is no key" and "you asked, there is a key, and the
     * local rung had it covered" mean different things to somebody deciding whether to trust what
     * they just read. Collapsing them into one line was the old flag's habit.
     */
    private String whyNoEscalation() {
        if (!com.osscli.llm.Ai.mayEscalate()) {
            return "not asked for — oss claude review " + prNumber;
        }
        if (com.osscli.llm.Ai.escalationPath().isEmpty()) {
            return "named, but no key configured — 'oss setup'";
        }
        if (escalationAttempted) {
            // A call that was made and failed is not the same as one that was never needed. The
            // report said "the local rung answered and the diff fit its budget" directly under a
            // rejected API call and an empty verdict -- three lines apart, and contradicting both.
            return "tried, and the provider refused — the message above says why";
        }
        return "the local rung answered and the diff fit its budget";
    }

    /** Distinguishes "you have no notes" from "your notes had nothing to say about this change". */
    private String whyNoNotes() {
        if (noNotes) {
            return "skipped by --no-notes";
        }
        return hasNotesCorpus() ? "nothing in your notes matched this change" : "no notes indexed ('sync --me')";
    }

    private void report(boolean used, String whatItGives, String howToEnable) {
        if (used) {
            LOGGER.info("  ✔ {}", whatItGives);
        } else {
            LOGGER.info("  ○ {} — {}", whatItGives, howToEnable);
        }
    }

    private boolean hasRepoProfile() {
        try (var conn = com.osscli.storage.DatabaseManager.getConnection();
                var ps = conn.prepareStatement("SELECT 1 FROM repo_profile WHERE repository = ?;")) {
            ps.setString(1, repository);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ollamaReachable() {
        try {
            String model = SqliteStorage.loadConfig("ollama.model.guidance");
            return new com.osscli.llm.OllamaClient(model == null ? "qwen2.5-coder:7b" : model).isServerReachable();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasCloudKey() {
        for (String var : new String[] {"ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNotesCorpus() {
        try (var conn = com.osscli.storage.DatabaseManager.getConnection();
                var ps = conn.prepareStatement("SELECT 1 FROM personal_chat_memory LIMIT 1;");
                var rs = ps.executeQuery()) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> readList(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(
                json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    }
}
