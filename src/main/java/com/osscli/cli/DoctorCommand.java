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

import com.osscli.AppPaths;
import com.osscli.llm.OllamaClient;
import com.osscli.storage.SqliteStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;

/**
 * One command that answers "why isn't this working yet".
 *
 * <p>Every prerequisite was already checked somewhere -- but each one fired at a different moment,
 * so a new user discovered them one failure at a time: run a command, hit a missing model, pull it,
 * run again, hit a missing token, and so on. Each individual message was fine. The sequence was the
 * problem.
 *
 * <p>So this reports EVERYTHING at once, and every failure carries the exact command that fixes it.
 * It also checks things no single command would notice on its own -- mixed embedding dimensions, a
 * context limit set below what retrieval actually assembles -- because those degrade results
 * silently rather than failing.
 */
@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Check every prerequisite at once and say exactly what to fix")
public class DoctorCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(DoctorCommand.class);

    /** Kept in step with ContextRetriever.TOKEN_BUDGET; a lower config silently over-escalates. */
    private static final int RETRIEVAL_TOKEN_BUDGET = 6000;

    private enum Level {
        OK("  ok  "),
        WARN(" warn "),
        FAIL(" FAIL ");
        final String tag;

        Level(String tag) {
            this.tag = tag;
        }
    }

    private record Check(Level level, String what, String detail, String fix) {}

    private final List<Check> checks = new ArrayList<>();

    private void ok(String what, String detail) {
        checks.add(new Check(Level.OK, what, detail, null));
    }

    private void warn(String what, String detail, String fix) {
        checks.add(new Check(Level.WARN, what, detail, fix));
    }

    private void fail(String what, String detail, String fix) {
        checks.add(new Check(Level.FAIL, what, detail, fix));
    }

    @Override
    public Integer call() {
        checkDataLocation();
        checkDatabase();
        String embedModel = checkModels();
        checkVectorConsistency(embedModel);
        checkGitHub();
        checkDrivePaths();
        checkThresholds();
        checkData();

        LOGGER.info("");
        LOGGER.info("  oss doctor");
        LOGGER.info("  ─────────────────────────────────────────────────────────────");
        for (Check c : checks) {
            LOGGER.info("  [{}] {} — {}", c.level().tag, c.what(), c.detail());
        }

        List<Check> problems = checks.stream().filter(c -> c.fix() != null).toList();
        long fails = checks.stream().filter(c -> c.level() == Level.FAIL).count();

        if (problems.isEmpty()) {
            LOGGER.info("  ─────────────────────────────────────────────────────────────");
            LOGGER.info("  Everything checks out.");
            return 0;
        }

        LOGGER.info("  ─────────────────────────────────────────────────────────────");
        LOGGER.info("  To fix:");
        for (Check c : problems) {
            LOGGER.info("    {}  {}", c.level() == Level.FAIL ? "!" : "·", c.fix());
        }
        LOGGER.info("");
        // Warnings alone are not a failure: the tool still runs, just not at its best.
        return fails > 0 ? 1 : 0;
    }

    // ── data location ───────────────────────────────────────────────────────
    /**
     * Which store this run is pointed at.
     *
     * <p>Reported first, and as a warning when relocated, because every check below is
     * about that directory. Without it, running doctor against a sandbox shows a healthy
     * but empty install, which reads as data loss.
     */
    private void checkDataLocation() {
        if (AppPaths.IS_RELOCATED) {
            warn(
                    "data directory",
                    AppPaths.BASE_DIR + "  (" + AppPaths.HOME_ENV_VAR + " is set)",
                    "That is a sandbox, not your usual store. Unset " + AppPaths.HOME_ENV_VAR + " to work against "
                            + AppPaths.HOME_DIR + "/.oss-cli.");
        } else {
            ok("data directory", AppPaths.BASE_DIR.toString());
        }
    }

    // ── database ────────────────────────────────────────────────────────────
    private void checkDatabase() {
        if (!Files.exists(AppPaths.DB_PATH)) {
            java.nio.file.Path legacy = AppPaths.findLegacyDb();
            if (legacy != null) {
                warn(
                        "database",
                        "found only at the pre-rename location " + legacy,
                        "Run any command once — it relocates automatically.");
            } else {
                warn("database", "not created yet", "Run 'oss setup' to create it.");
            }
            return;
        }
        try {
            long mb = Files.size(AppPaths.DB_PATH) / (1024 * 1024);
            ok("database", AppPaths.DB_PATH + " (" + mb + " MB)");
        } catch (Exception e) {
            warn("database", "present but unreadable: " + e.getMessage(), "Check file permissions.");
        }
        checkSchemaVersion();
    }

    /**
     * Whether this build can open the store at all.
     *
     * <p>{@code doctor} deliberately still runs when the schema is too new — it is the command that
     * explains why everything else stopped — so it has to be the one that says so plainly rather
     * than reporting a healthy database and leaving the user to guess.
     */
    private void checkSchemaVersion() {
        try {
            int store = com.osscli.storage.DatabaseManager.storedSchemaVersion();
            int understood = com.osscli.storage.DatabaseManager.currentSchemaVersion();
            if (store > understood) {
                fail(
                        "schema",
                        "database is at " + store + ", this build understands " + understood,
                        "It was written by a newer oss. Upgrade with 'brew upgrade oss', "
                                + "or point OSS_CLI_HOME at a different directory.");
            } else if (store < understood) {
                warn(
                        "schema",
                        "database is at " + store + ", this build is at " + understood,
                        "The next command migrates it forwards automatically.");
            } else {
                ok("schema", "version " + store);
            }
        } catch (Exception e) {
            warn("schema", "could not be read: " + e.getMessage(), "Run 'oss setup' to create the database.");
        }
    }

    // ── models ──────────────────────────────────────────────────────────────
    /** @return the built-in embedder's name, needed by the vector provenance check. */
    private String checkModels() {
        String embed = com.osscli.retrieval.Embeddings.MODEL;

        // The embedder runs in this process, so the question is whether its weights are on disk --
        // not whether a server is up. It is also never fatal: term search is the floor, and a report
        // that calls an optional layer a failure teaches people to ignore the report.
        if (com.osscli.retrieval.Embeddings.isReady()) {
            ok("embedding model", embed + " (built in, in-process)");
        } else {
            warn(
                    "embedding model",
                    "not fetched — search and pick answer by shared terms",
                    com.osscli.retrieval.Embeddings.ABSENT_HINT);
        }

        String guidance = cfg("ollama.model.guidance", null);
        String triage = cfg("ollama.model.triage", null);
        String url = cfg("ollama.url", com.osscli.Defaults.OLLAMA_URL);

        // Ollama is external and optional, and since embedding moved in-process it is only ever used
        // to generate local verdicts. Nothing indexes or searches through it any more, so an absent
        // daemon costs the local answer and nothing else -- the expert prompt still builds.
        if (guidance == null && triage == null) {
            ok("ollama", "not connected — local verdicts off, prompts still build");
            return embed;
        }
        if (!pingServer()) {
            warn(
                    "ollama",
                    "not reachable at " + url,
                    "Optional — it adds local verdicts. Start it ('ollama serve'), "
                            + "or set ollama.url to wherever it runs.");
            return embed;
        }
        ok("ollama", "reachable at " + url);
        reportProviderClis();

        if (guidance != null) {
            checkOneModel("guidance model", guidance, false);
        } else {
            warn("guidance model", "not configured", "Run 'oss setup', or set ollama.model.guidance.");
        }
        if (triage != null) {
            checkOneModel("triage model", triage, false);
        }
        return embed;
    }

    private void checkOneModel(String label, String model, boolean fatal) {
        OllamaClient client = new OllamaClient(model);
        boolean present = client.isModelAvailable();
        if (present) {
            // Pulled is not the same as runnable. Reported here because the alternative is
            // finding out by watching the machine stop responding for ten minutes, which is
            // exactly the sort of thing doctor exists to say in advance.
            com.osscli.llm.ModelFit.Verdict fit = com.osscli.llm.ModelFit.check(client, model);
            if (fit.shouldRefuse()) {
                warn(label, model + " does not fit in memory right now", String.join(" ", fit.explain()));
            } else if (fit.known()) {
                ok(label, model + " — fits (" + fit.memory() + ")");
            } else {
                ok(label, model);
            }
        } else if (fatal) {
            fail(label, "'" + model + "' is not pulled", "ollama pull " + model);
        } else {
            warn(label, "'" + model + "' is not pulled", "ollama pull " + model);
        }
    }

    private boolean pingServer() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net
                    .URI
                    .create(cfg("ollama.url", "http://localhost:11434") + "/api/tags")
                    .toURL()
                    .openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── vectors ─────────────────────────────────────────────────────────────
    /**
     * Mixed embedding models are the quietest failure in the system: similarity between vectors of
     * different dimensions is meaningless but never errors, so retrieval just gets worse.
     */
    private void checkVectorConsistency(String configuredModel) {
        for (String table : new String[] {"personal_chat_chunk", "personal_chat_memory", "embeddings"}) {
            try {
                Map<String, Integer> byModel = SqliteStorage.countVectorsByModel(table);
                if (byModel.isEmpty()) {
                    continue;
                }
                Set<String> models = new LinkedHashSet<>(byModel.keySet());
                if (models.size() > 1) {
                    fail(
                            "vectors in " + table,
                            "produced by " + models.size() + " different models: " + models,
                            "Re-sync to rebuild them with one model: oss sync --me");
                } else {
                    String only = models.iterator().next();
                    int n = byModel.get(only);
                    if ("(unknown)".equals(only)) {
                        warn(
                                table,
                                n + " vectors with no recorded model",
                                "Re-sync once so provenance is recorded: oss sync --me");
                    } else if (configuredModel != null && !only.equals(configuredModel)) {
                        warn(
                                table,
                                n + " vectors from '" + only + "' but configured model is '" + configuredModel + "'",
                                "Next sync will re-embed them automatically.");
                    } else {
                        ok(table, n + " vectors, all from " + only);
                    }
                }
            } catch (Exception e) {
                // table may not exist on an older schema; not worth failing over
            }
        }
    }

    // ── github ──────────────────────────────────────────────────────────────
    private void checkGitHub() {
        String user = cfg("github.username", null);
        if (user == null || user.isBlank()) {
            warn("github username", "not configured", "Run 'oss setup'.");
        } else {
            ok("github username", user);
        }
        String source = com.osscli.util.CredentialManager.gitHubTokenSource();
        if (source != null) {
            ok("github token", "found in " + source);
        } else {
            warn(
                    "github token",
                    "not in GITHUB_TOKEN, GH_TOKEN or the keychain",
                    // Not "only for sync": GitHubClient is what review, pr, issue, prs, hub and
                    // followup all read through, and naming one command sends somebody who cannot
                    // review a pull request off to look at something unrelated.
                    "export GITHUB_TOKEN=$(gh auth token)   # or 'oss setup' to store it");
        }
    }

    // ── notes ───────────────────────────────────────────────────────────────
    private void checkDrivePaths() {
        String raw = cfg("drive.paths", null);
        if (raw == null || raw.isBlank()) {
            warn(
                    "drive.paths",
                    "no note folders configured — your own knowledge is not indexed",
                    "Set drive.paths to a comma-separated list of note folders (see SETUP.md).");
            return;
        }
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (Files.isDirectory(Path.of(t))) {
                found++;
            } else {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            // A path that does not resolve is skipped with a log line nobody reads,
            // so the folder is simply never indexed and nothing ever says so.
            fail(
                    "drive.paths",
                    found + " folder(s) found, " + missing.size() + " missing: " + missing,
                    "Fix or remove the missing paths — they are silently skipped during sync.");
        } else {
            ok("drive.paths", found + " folder(s), all present");
        }
    }

    // ── thresholds ──────────────────────────────────────────────────────────
    private void checkThresholds() {
        int limit = intCfg("ollama.context_limit", 4096);
        if (limit < RETRIEVAL_TOKEN_BUDGET) {
            warn(
                    "ollama.context_limit",
                    limit + " is below the " + RETRIEVAL_TOKEN_BUDGET + "-token retrieval budget",
                    "Raise it to " + RETRIEVAL_TOKEN_BUDGET + ", or everything in the gap escalates needlessly.");
        } else {
            ok("ollama.context_limit", String.valueOf(limit));
        }

        // Read from the client rather than repeated here: a second copy of the number is how a
        // stored 300 kept reporting healthy after the default moved past it, on the one machine
        // where 300 had already been measured to be too short.
        int builtIn = com.osscli.llm.OllamaClient.defaultTimeoutSeconds();
        int timeout = intCfg("ollama.timeout_seconds", builtIn);
        if (timeout < 120) {
            warn(
                    "ollama.timeout_seconds",
                    timeout + "s is short for a full-context request",
                    "A 6000-token request can take minutes on CPU. Raise it to " + builtIn + ".");
        } else if (timeout < builtIn) {
            warn(
                    "ollama.timeout_seconds",
                    timeout + "s, below the built-in " + builtIn + "s",
                    "A 7B model on an Apple-silicon laptop was measured at 482s for a realistic"
                            + " prompt. A stored value below that cuts off requests that are working."
                            + " Raise it to " + builtIn + " unless you would rather fail fast.");
        } else {
            ok("ollama.timeout_seconds", timeout + "s");
        }
    }

    // ── data ────────────────────────────────────────────────────────────────
    private void checkData() {
        int notes = count("personal_chat_memory");
        int passages = count("personal_chat_chunk");
        int issues = count("issues");

        if (issues == 0) {
            warn("backlog", "no issues synced", "oss sync --all");
        } else {
            ok("backlog", issues + " issues");
        }

        if (notes == 0) {
            warn("your notes", "none indexed", "oss sync --me");
        } else if (passages == 0) {
            warn(
                    "your notes",
                    notes + " notes but no passages — long notes are unsearchable",
                    "oss sync --me   (builds passage-level embeddings)");
        } else {
            ok("your notes", notes + " notes, " + passages + " passages");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private String cfg(String key, String fallback) {
        try {
            String v = SqliteStorage.loadConfig(key);
            return (v == null || v.isBlank()) ? fallback : v;
        } catch (Exception e) {
            return fallback;
        }
    }

    private int intCfg(String key, int fallback) {
        try {
            return Integer.parseInt(cfg(key, String.valueOf(fallback)).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int count(String table) {
        try {
            Map<String, Integer> m = SqliteStorage.countVectorsByModel(table);
            int n = m.values().stream().mapToInt(Integer::intValue).sum();
            if (n > 0) {
                return n;
            }
        } catch (Exception e) {
            // fall through to the direct count below
        }
        try {
            return SqliteStorage.countRows(table);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Which provider command-line tools are installed, for {@code --cli}.
     *
     * <p>Reported rather than required. Each is an optional route that answers on the subscription
     * somebody already pays for, which matters most exactly when the API account has no credit --
     * and that is the moment a health check should already have told them the route exists.
     */
    private void reportProviderClis() {
        for (com.osscli.llm.CliClient.Spec spec : java.util.List.of(
                com.osscli.llm.CliClient.CLAUDE, com.osscli.llm.CliClient.CODEX, com.osscli.llm.CliClient.GEMINI)) {
            String name = spec.binary() + " cli";
            if (!new com.osscli.llm.CliClient(spec, 1).available()) {
                ok(name, "not installed — optional, only for " + spec.engine().typed() + " --cli");
            } else if (!spec.verified()) {
                // Said plainly rather than reported as working: the invocation for this one was
                // written from its documentation and never run, and a tick would claim otherwise.
                ok(name, "installed — invocation not verified here");
            } else {
                ok(name, "installed — " + spec.engine().typed() + " --cli answers on that subscription");
            }
        }
    }
}
