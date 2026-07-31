package com.osscli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical on-disk locations for the application.
 *
 * <p>The project has been renamed twice: {@code issue-ai}, then {@code self-analyse}, now
 * {@code oss-cli}. Each rename moved the data directory, and the first was not handled -- the build
 * read a path that did not exist while the real database sat untouched under the old name, which is
 * indistinguishable from total data loss. Every previous location therefore stays declared here and
 * {@code DatabaseManager} relocates once on first run, rather than asking anyone to move files by hand.
 */
public class AppPaths {
    // Dynamically resolves to /Users/<you> (or equivalent on Linux/Windows)
    public static final String HOME_DIR = System.getProperty("user.home");

    /** The master global hidden directory: ~/.oss-cli */
    public static final Path BASE_DIR = Paths.get(HOME_DIR, ".oss-cli");

    /**
     * Every location this data has lived in, newest first.
     *
     * <p>The project has been renamed twice: issue-ai, then self-analyse, now oss-cli. Each rename
     * moved the data directory, and the first one was not handled -- the build started reading a
     * path that did not exist while 176 MB sat untouched under the old name, which is
     * indistinguishable from total data loss. Keeping the full chain means someone upgrading from
     * ANY previous version is carried across, not just the most recent one.
     */
    public static final Path[] LEGACY_BASE_DIRS = {
        Paths.get(HOME_DIR, ".self-analyse"), Paths.get(HOME_DIR, ".issue-ai"),
    };

    public static final Path DATA_DIR = BASE_DIR.resolve("data");
    public static final Path REPORTS_DIR = BASE_DIR.resolve("reports");
    public static final Path BACKUPS_DIR = BASE_DIR.resolve("backups");

    public static final String DB_FILE_NAME = "issue_intelligence.db";

    public static final Path DB_PATH = DATA_DIR.resolve(DB_FILE_NAME);

    /** First legacy database that actually exists, or null when there is nothing to carry over. */
    public static Path findLegacyDb() {
        for (Path base : LEGACY_BASE_DIRS) {
            Path candidate = base.resolve("data").resolve(DB_FILE_NAME);
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Absolute JDBC URL targeting ~/.oss-cli/data/issue_intelligence.db */
    public static final String DB_URL = "jdbc:sqlite:" + DB_PATH.toAbsolutePath();
}
