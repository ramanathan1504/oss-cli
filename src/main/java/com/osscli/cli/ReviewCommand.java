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
@Command(name = "review", description = "Review a pull request using every source you have connected")
public class ReviewCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ReviewCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Enough prior work to inform a judgment, few enough that the diff stays the bulk of the prompt. */
    private static final int MAX_NOTES = 5;

    private static final int NOTE_EXCERPT_CHARS = 1200;

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
        }

        printFacts(ev);
        com.osscli.model.RepoProfile profile = SqliteStorage.loadRepoProfile(repository);
        boolean conventionsChecked = printConventionChecks(ev, profile);

        List<com.osscli.model.PromptContextChunk> notes = noNotes ? List.of() : findRelatedNotes(ev);
        printRelatedNotes(notes);

        boolean verdictGiven = !noVerdict && printVerdict(ev, profile, notes);
        printLadder(verdictGiven, conventionsChecked, !notes.isEmpty());
        return 0;
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
                .filter(p -> p.contains("/src/main/") || p.startsWith("src/"))
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
            if (!ollama.isServerReachable()) {
                return false;
            }

            int budget = 24000;
            String diff = ev.diff() == null ? "" : ev.diff();
            boolean truncated = diff.length() > budget;
            if (truncated) {
                diff = diff.substring(0, budget);
            }

            LOGGER.info("");
            LOGGER.info("  ↳ Asking {} for a verdict{}...", model, truncated ? " (diff truncated)" : "");

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

                    Review THE DIFF and nothing else. The project rules and prior-work sections
                    are background: cite them only where they bear on this diff, and never review
                    them — they are not the change. Report only what the diff actually shows; if
                    something cannot be determined from it, say so rather than guessing.

                    Respond in JSON with this exact structure:
                    {
                      "summary": "<what this change does, one or two sentences>",
                      "concerns": ["<specific, actionable concern>"],
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

            JsonNode node = MAPPER.readTree(ollama.generateJson(prompt));

            LOGGER.info("");
            LOGGER.info(
                    "── Verdict ({}, confidence {}) ──",
                    model,
                    String.format("%.0f%%", node.path("confidence").asDouble(0.5) * 100));
            LOGGER.info("  {}", node.path("summary").asText(""));

            printBullets("Concerns", node.path("concerns"));
            printBullets("Questions for the author", node.path("questions"));

            if (truncated) {
                LOGGER.info("");
                LOGGER.info("  Note: the diff exceeded the local budget and was truncated.");
                LOGGER.info("        Findings cover only the portion shown above.");
            }

            recordReview(ev, model, node);
            return true;

        } catch (Exception e) {
            LOGGER.warn("  ⚠ Local verdict unavailable: {}", e.getMessage());
            LOGGER.warn("    The facts above are unaffected.");
            return false;
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

        com.osscli.knowledge.ResolutionWriter.record(
                ev.repository(), ev.prNumber(), ev.title(), model, null, sb.toString(), "oss-cli", "review");
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
    private void printLadder(boolean verdictGiven, boolean conventionsChecked, boolean notesUsed) {
        LOGGER.info("");
        LOGGER.info("── What this review used ──");
        LOGGER.info("  ✔ Facts from GitHub");
        report(conventionsChecked, "Convention checks against this project's rules", "run 'profile' to build it");
        report(notesUsed, "Your own prior work", whyNoNotes());
        report(
                verdictGiven,
                "Local verdict from Ollama",
                noVerdict ? "skipped by --no-verdict" : "start Ollama with 'ollama serve'");

        // Not yet consulted by this command. Listed so the gap is visible rather than mistaken for a clean result.
        LOGGER.info(
                "  ○ Escalation for large diffs — {}",
                hasCloudKey() ? "cloud key found, but review does not escalate yet" : "no cloud key configured");
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
