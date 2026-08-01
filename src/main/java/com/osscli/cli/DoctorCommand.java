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
@Command(name = "doctor", description = "Check every prerequisite at once and say exactly what to fix")
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
        LOGGER.info("  oss-cli doctor");
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
                warn("database", "not created yet", "Run 'oss-cli setup' to create it.");
            }
            return;
        }
        try {
            long mb = Files.size(AppPaths.DB_PATH) / (1024 * 1024);
            ok("database", AppPaths.DB_PATH + " (" + mb + " MB)");
        } catch (Exception e) {
            warn("database", "present but unreadable: " + e.getMessage(), "Check file permissions.");
        }
    }

    // ── models ──────────────────────────────────────────────────────────────
    /** @return the configured embedding model, needed by the dimension check. */
    private String checkModels() {
        String embed = cfg("ollama.model.embedding", "all-minilm");
        String guidance = cfg("ollama.model.guidance", null);
        String triage = cfg("ollama.model.triage", null);

        boolean serverUp;
        try {
            serverUp = new OllamaClient(embed).isModelAvailable() || pingServer();
        } catch (Exception e) {
            serverUp = false;
        }
        if (!serverUp) {
            fail(
                    "model server",
                    "not reachable at " + cfg("ollama.url", "http://localhost:11434"),
                    "Start it (e.g. 'ollama serve'), or point ollama.url at your own endpoint.");
            return embed;
        }
        ok("model server", "reachable");

        // The embedding model is the one that matters most: without it nothing can
        // be indexed or searched at all.
        checkOneModel("embedding model", embed, true);
        if (guidance != null) {
            checkOneModel("guidance model", guidance, false);
        } else {
            warn("guidance model", "not configured", "Run 'oss-cli setup', or set ollama.model.guidance.");
        }
        if (triage != null) {
            checkOneModel("triage model", triage, false);
        }
        return embed;
    }

    private void checkOneModel(String label, String model, boolean fatal) {
        boolean present = new OllamaClient(model).isModelAvailable();
        if (present) {
            ok(label, model);
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
                            "Re-sync to rebuild them with one model: oss-cli sync --me");
                } else {
                    String only = models.iterator().next();
                    int n = byModel.get(only);
                    if ("(unknown)".equals(only)) {
                        warn(
                                table,
                                n + " vectors with no recorded model",
                                "Re-sync once so provenance is recorded: oss-cli sync --me");
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
            warn("github username", "not configured", "Run 'oss-cli setup'.");
        } else {
            ok("github username", user);
        }
        boolean env = System.getenv("GITHUB_TOKEN") != null || System.getenv("GH_TOKEN") != null;
        if (env) {
            ok("github token", "found in the environment");
        } else {
            warn(
                    "github token",
                    "not in GITHUB_TOKEN or GH_TOKEN",
                    "export GITHUB_TOKEN=$(gh auth token)   # needed only for 'sync'");
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

        int timeout = intCfg("ollama.timeout_seconds", 300);
        if (timeout < 120) {
            warn(
                    "ollama.timeout_seconds",
                    timeout + "s is short for a full-context request",
                    "A 6000-token request can take minutes on CPU. Consider 300.");
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
            warn("backlog", "no issues synced", "oss-cli sync --all");
        } else {
            ok("backlog", issues + " issues");
        }

        if (notes == 0) {
            warn("your notes", "none indexed", "oss-cli sync --me");
        } else if (passages == 0) {
            warn(
                    "your notes",
                    notes + " notes but no passages — long notes are unsearchable",
                    "oss-cli sync --me   (builds passage-level embeddings)");
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
}
