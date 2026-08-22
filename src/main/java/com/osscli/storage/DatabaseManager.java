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
package com.osscli.storage;

import com.osscli.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DatabaseManager {

    private static final Logger LOGGER = LogManager.getLogger(DatabaseManager.class);
    // private static final String DB_URL = "jdbc:sqlite:data/issue_intelligence.db";
    private static final int CURRENT_SCHEMA_VERSION = 15;

    /**
     * How long a statement waits for a lock before giving up.
     *
     * <p>Sized for the longest write anybody actually runs into: a {@code sync} pass committing a
     * batch of issues. Chat turns are single-row inserts measured in milliseconds, so a chat in a
     * second terminal waits out a sync rather than failing in front of the user mid-sentence.
     */
    private static final int BUSY_TIMEOUT_MS = 15_000;

    private static volatile boolean journalModeChecked = false;

    /** Tables whose rows carry an embedding vector and therefore need provenance. */
    static final String[] VECTOR_TABLES = {"personal_chat_memory", "personal_pr_memory", "embeddings"};

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static boolean legacyRelocationChecked = false;

    // Interface representing a single, isolated database migration step
    private interface Migration {
        int getTargetVersion();

        void execute(Connection conn) throws SQLException;
    }

    // Static registry of all sequential database schema migrations
    private static final Migration[] MIGRATIONS = new Migration[] {
        // Migration 1: Fresh Database Schema Initialization
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 1;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Initializing fresh SQLite database schema...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreateIssuesTableSql());
                    stmt.execute(getCreateLabelsTableSql());
                    stmt.execute(getCreateAiAnalysisTableSql());
                    stmt.execute(getCreateEmbeddingsTableSql());
                    stmt.execute(getCreateCrossRepoLinksTableSql());
                    stmt.execute(getCreateSnapshotsTableSql());
                    stmt.execute(getCreateJiraMentionsTableSql());
                    stmt.execute(getCreateMonitoredTableSql());
                    // Deliberately not seeded. This used to insert fourteen real third-party
                    // repositories -- log4j, kafka, spark, elasticsearch and the rest -- into
                    // EVERY fresh install, so a stranger's first `oss sync --all` would fetch
                    // hundreds of megabytes from projects they had never named. One person's
                    // interests shipped as everybody's default is the exact opposite of a tool
                    // for any OSS developer, and it spends somebody else's API budget to do it.
                    // The registry starts empty; `oss sync --add owner/name` fills it.
                    stmt.execute(getCreateConfigTableSql());
                    stmt.execute(getSeedConfigTableSql());
                    stmt.execute(getCreatePersonalCodeFootprintTableSql());
                    stmt.execute(getCreatePersonalPrMemoryTableSql());
                    stmt.execute(getCreatePersonalChatMemoryTableSql());
                    stmt.execute(getCreatePromptHistoryTableSql());
                    stmt.execute(getCreatePromptContextChunksTableSql());
                    stmt.execute(getSeedPromptConfigSql());
                }
            }
        },
        // Migration 2: Upgrade V1 Legacy tables to composite-key schemas
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 2;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema from Version 1 to Version 2...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = OFF;");
                }

                migrateTable(
                        conn,
                        "issues",
                        "number, title, body, state, comments, created_at, updated_at, is_pull_request, author, author_association",
                        getCreateIssuesTableSql());

                migrateTable(conn, "labels", "issue_number, label_name", getCreateLabelsTableSql());

                migrateTable(
                        conn,
                        "ai_analysis",
                        "issue_number, severity, confidence, reason",
                        getCreateAiAnalysisTableSql());

                migrateTable(conn, "embeddings", "issue_number, vector", getCreateEmbeddingsTableSql());

                migrateTable(
                        conn,
                        "snapshots",
                        "date, critical_issues, high_priority, stale_prs, duplicate_clusters",
                        getCreateSnapshotsTableSql());

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreateCrossRepoLinksTableSql());
                    stmt.execute(getCreateJiraMentionsTableSql());
                    stmt.execute(getCreateMonitoredTableSql());
                    // Not seeded here either, for the reason given in Migration 1. Nothing is lost
                    // for a store that already ran this: a migration runs once, and the rows it
                    // wrote then are still there.
                    stmt.execute("PRAGMA foreign_keys = ON;");
                }
            }
        },
        // Migration 3: Add last_synced_at to monitored repositories
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 3;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 3 (Adding delta-sync tracking)...");
                if (!columnExists(conn, "monitored_repositories", "last_synced_at")) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE monitored_repositories ADD COLUMN last_synced_at TEXT;");
                    }
                }
            }
        },
        // Migration 4: Create system_config table and seed defaults
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 4;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 4 (Adding system configuration)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreateConfigTableSql());
                    stmt.execute(getSeedConfigTableSql());
                }
            }
        },
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 5;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 5 (Adding personal code footprint)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePersonalCodeFootprintTableSql());
                }
            }
        },
        // Migration 6: Create personal_pr_memory and personal_chat_memory tables
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 6;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 6 (Adding personal memory registries)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePersonalPrMemoryTableSql());
                    stmt.execute(getCreatePersonalChatMemoryTableSql());
                }
            }
        },
        // Migration 7: Create prompt_history and prompt_context_chunks tables for Prompt Intelligence
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 7;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 7 (Adding Prompt Intelligence tables)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePromptHistoryTableSql());
                    stmt.execute(getCreatePromptContextChunksTableSql());
                    stmt.execute(getSeedPromptConfigSql());
                }
            }
        },
        // Migration 8: Safety — ensure prompt tables exist for DBs created before Migration 1 was updated
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 8;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 8 (Ensuring Prompt Intelligence tables exist)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePromptHistoryTableSql());
                    stmt.execute(getCreatePromptContextChunksTableSql());
                    stmt.execute(getSeedPromptConfigSql());
                }
            }
        },
        // Migration 9: Record which embedding model produced each vector.
        //
        // Vectors from different models are not comparable, and the failure is
        // SILENT rather than loud: all-minilm emits 384 dimensions, nomic-embed-text
        // 768, mxbai-embed-large 1024. Mix them in one table and cosine similarity
        // returns a plausible-looking number computed from unrelated coordinate
        // spaces -- or 0.0 on a length check, which reads as "unrelated" rather than
        // "incomparable". Either way the user sees bad retrieval, not an error.
        //
        // Storing the model and dimension next to each vector makes a model swap
        // detectable, so sync can re-embed instead of quietly corrupting the index.
        // This is what lets someone run whatever embedding model they prefer.
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 9;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 9 (Tracking embedding model provenance)...");
                for (String table : VECTOR_TABLES) {
                    addColumnIfMissing(conn, table, "embedding_model", "TEXT");
                    addColumnIfMissing(conn, table, "embedding_dim", "INTEGER");
                }
                // Pre-existing rows deliberately keep NULL provenance. NULL means
                // "unknown, do not trust" -- sync treats it as stale and re-embeds,
                // which self-heals on the next run rather than guessing wrongly.
            }
        },
        // Migration 10: Passage-level embeddings.
        //
        // A note used to be embedded whole, but embedding models have small input
        // windows -- a few hundred tokens. With a median note of ~11k characters,
        // the vector represented only each note's opening and everything after it
        // was invisible to retrieval: a note whose answer sits in the middle could
        // not be found by asking about that answer. Bigger chat models do not fix
        // this; they cannot recover text the embedder never read.
        //
        // Notes are therefore split into overlapping passages, each embedded
        // separately and pointing back at its parent note.
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 10;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 10 (Passage-level embeddings)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePersonalChatChunkTableSql());
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_chunk_file ON personal_chat_chunk(file_path);");
                }
            }
        },
        // Migration 11: Pull request review cache and per-repository profile.
        //
        // The PR cache is keyed by head SHA rather than PR number. A pull request is not a fixed
        // object -- every push rewrites its diff, commits and checks -- so caching by number alone
        // would serve a review of code that no longer exists, which is worse than no cache at all.
        // Keying on the SHA makes a re-review of untouched code free and a re-review after a push
        // automatic, with no staleness rule to get wrong.
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 11;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 11 (PR review cache and repo profiles)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreatePrCacheTableSql());
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pr_cache_repo ON pr_cache(repository, pr_number);");
                    stmt.execute(getCreateRepoProfileTableSql());
                }
            }
        },
        // Migration 12: What one issue says about another.
        //
        // Retrieval ranked everything by similarity, which cannot see a reference. A pull request
        // whose whole body is "fixes #4100" shares almost no wording with the issue it closes, so
        // the two scored as unrelated at exactly the moment they were most related. These edges are
        // read out of what people wrote and followed directly, beside the ranking rather than
        // instead of it.
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 12;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 12 (issue reference index)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(getCreateIssueReferencesTableSql());
                    // Looked up in one direction constantly -- "what does this issue point at" --
                    // and in the other only when asked, so only the first is indexed.
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_refs_from "
                            + "ON issue_references(repository, from_number);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_refs_to "
                            + "ON issue_references(to_repository, to_number);");
                }
            }
        },
        // Migration 13: Whether a note is yours or merely collected.
        //
        // Harvesting whole repositories puts other people's conversations in the same corpus as your
        // own conclusions. Both belong there; ranked identically the collected material wins on
        // volume, because there is far more of it. Recorded per note so retrieval can prefer what you
        // worked out without discarding what you gathered.
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 13;
            }

            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 13 (knowledge and reference tiers)...");
                // Existing rows predate harvesting whole repositories, so every one of them is a note
                // the user wrote or a thread they were in. KNOWLEDGE is the honest default.
                addColumnIfMissing(conn, "personal_chat_memory", "tier", "TEXT DEFAULT 'KNOWLEDGE'");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("UPDATE personal_chat_memory SET tier = 'KNOWLEDGE' WHERE tier IS NULL;");
                }
            }
        },

        // Migration 14: Resumable chat sessions
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 14;
            }

            /**
             * A chat used to exist only in one process's memory and was written to the archive on a clean
             * {@code exit}. Ctrl-C, a closed lid or a dropped connection therefore discarded the whole
             * conversation, and there was no way to come back to one the next morning.
             *
             * <p>Two tables rather than a transcript column, because a turn has to be durable the moment it
             * happens. Appending a row is a single short write; rewriting a growing blob on every turn would
             * hold the write lock longer and longer, against the other terminals, for the same data.
             */
            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 14 (resumable chat sessions)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS chat_session (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                repository TEXT NOT NULL,
                                issue_number INTEGER NOT NULL,
                                issue_title TEXT,
                                provider TEXT,
                                summary TEXT,
                                overview TEXT,
                                started_at TEXT NOT NULL,
                                updated_at TEXT NOT NULL,
                                ended_at TEXT,
                                note_path TEXT,
                                owner_pid INTEGER,
                                owner_host TEXT,
                                parent_id INTEGER
                            );""");
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS chat_turn (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                session_id INTEGER NOT NULL,
                                seq INTEGER NOT NULL,
                                role TEXT NOT NULL,
                                content TEXT NOT NULL,
                                created_at TEXT NOT NULL,
                                UNIQUE (session_id, seq)
                            );""");
                    // Both list orders the picker uses: newest overall, and newest for one issue.
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_updated ON chat_session (updated_at);");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_chat_session_issue ON chat_session (repository, issue_number);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_turn_session ON chat_turn (session_id, seq);");
                }
            }
        },

        // Migration 15: the comments you wrote, kept as yours
        new Migration() {
            @Override
            public int getTargetVersion() {
                return 15;
            }

            /**
             * The corpus was full of prose and almost none of it was the user's.
             *
             * <p>{@code harvest} has always fetched whole comment threads and rendered them into a
             * note, which loses the one fact that makes a comment the user's: who wrote it. So a
             * machine holding 1,874 notes could offer nine pieces of its owner's writing, and
             * anything learned about "their" voice was learned from other people and from generated
             * drafts.
             *
             * <p>Only comments whose author is the configured username are stored here. Other
             * people's words stay where they were, in the thread note -- keeping them in a table
             * called "yours" would be a lie the schema tells, and this table exists precisely
             * because that lie was already being told by omission.
             *
             * <p>No new network call: harvest already reads these pages.
             */
            @Override
            public void execute(Connection conn) throws SQLException {
                LOGGER.info("Upgrading database schema to Version 15 (the comments you wrote)...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS authored_comment (
                                comment_id INTEGER PRIMARY KEY,
                                repository TEXT NOT NULL,
                                issue_number INTEGER NOT NULL,
                                author TEXT NOT NULL,
                                body TEXT NOT NULL,
                                created_at TEXT
                            );""");
                    // Read one way only: everything this author wrote, newest first.
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_authored_comment_author ON authored_comment (author, created_at);");
                }
            }
        }
    };

    /** The schema version this build knows how to run. Read by {@code doctor} and the release guard. */
    public static int currentSchemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    /**
     * The version stamped in the store, or 0 when there is nothing stamped yet.
     *
     * <p>Reads only {@code schema_version}, and never migrates. It has to be answerable on a
     * database this build has already refused to open, which is exactly when it is asked.
     */
    public static int storedSchemaVersion() throws SQLException {
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            if (!tableExists(conn, "schema_version")) {
                return 0;
            }
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) AS version FROM schema_version;")) {
                return rs.next() ? rs.getInt("version") : 0;
            }
        }
    }

    public static Connection getConnection() throws SQLException {
        try {
            Files.createDirectories(AppPaths.DATA_DIR);
        } catch (IOException e) {
            LOGGER.error("Failed to create database directory structure: {}", e.getMessage());
            throw new SQLException("Failed to create database directory structure", e);
        }

        relocateLegacyDatabase();

        int attempts = 0;
        while (true) {
            try {
                Connection conn = DriverManager.getConnection(AppPaths.DB_URL);
                applyConcurrencyPragmas(conn);
                return conn;
            } catch (SQLException e) {
                attempts++;
                if (attempts >= MAX_RETRY_ATTEMPTS) {
                    LOGGER.fatal("Failed to connect to SQLite after {} attempts.", attempts, e);
                    throw e;
                }
                LOGGER.warn(
                        "Database busy or connection locked. Retrying attempt {}/{}...", attempts, MAX_RETRY_ATTEMPTS);
                try {
                    Thread.sleep(100 + (long) (Math.random() * 400));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Database connection attempt interrupted", ie);
                }
            }
        }
    }

    /**
     * Makes this connection survive the other {@code oss} processes the user has open.
     *
     * <p>Nothing set these before, which left SQLite in its default rollback-journal mode: a writer
     * takes an exclusive lock on the whole file and every reader is turned away immediately with
     * {@code SQLITE_BUSY}. That is fine for one terminal and wrong for the way this is actually
     * used -- a sync running in one window made {@code chat} in the next window fail outright,
     * mid-sentence, with a database error the user could do nothing about.
     *
     * <p>The retry loop around {@link #getConnection()} did not help, because connecting is not the
     * step that fails. Opening a SQLite file almost always succeeds; the lock is taken when a
     * statement runs. So the wait has to live in the connection itself, which is what
     * {@code busy_timeout} is.
     *
     * <ul>
     *   <li><b>WAL</b> lets readers carry on while one writer works, so the three terminals only
     *       ever contend writer-against-writer, never reader-against-writer.
     *   <li><b>busy_timeout</b> turns that remaining contention into a wait instead of an error.
     *   <li><b>synchronous=NORMAL</b> is the documented safe pairing with WAL: durable across a
     *       process being killed, which is the case that matters here, since losing a chat turn to
     *       a ctrl-c is the exact failure sessions exist to prevent.
     * </ul>
     */
    private static void applyConcurrencyPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS + ";");
            stmt.execute("PRAGMA synchronous = NORMAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");

            // journal_mode is a property of the file, not the connection, so this only does work
            // once per database. The result is read back rather than assumed: WAL needs shared
            // memory, which a network filesystem may not provide, and SQLite reports that by
            // quietly returning the mode it kept. Silently staying in rollback mode would restore
            // exactly the failure above with nothing on screen to explain it.
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode = WAL;")) {
                String mode = rs.next() ? rs.getString(1) : "unknown";
                if (!"wal".equalsIgnoreCase(mode) && !journalModeChecked) {
                    journalModeChecked = true;
                    LOGGER.warn("  ⚠ This database is in '{}' journal mode, not WAL.", mode);
                    LOGGER.warn("    Running two oss commands at once will make one of them wait,");
                    LOGGER.warn("    and a long sync can hold the other off. WAL needs a local disk;");
                    LOGGER.warn("    a network or cloud-synced OSS_CLI_HOME cannot provide it.");
                } else if ("wal".equalsIgnoreCase(mode)) {
                    journalModeChecked = true;
                }
            }
        }
    }

    /**
     * Carries a pre-rename {@code ~/.issue-ai} database over to {@code ~/.self-analyse} once.
     *
     * <p>Only acts when the canonical database is genuinely absent, so it can never overwrite live
     * data. A move (not a copy) keeps a single source of truth and is atomic on the same volume; if
     * the two live on different volumes the move falls back to a copy and the original is left in
     * place for the user to remove. The WAL and shared-memory sidecars travel with it -- leaving
     * them behind would strand committed transactions that had not yet been checkpointed.
     */
    private static synchronized void relocateLegacyDatabase() {
        if (legacyRelocationChecked) {
            return;
        }
        legacyRelocationChecked = true;

        if (Files.exists(AppPaths.DB_PATH)) {
            return;
        }
        // Walk the whole rename chain, newest first, so an upgrade from ANY earlier
        // version is carried across rather than only the most recent one.
        java.nio.file.Path legacyDb = AppPaths.findLegacyDb();
        if (legacyDb == null) {
            return;
        }
        java.nio.file.Path legacyDir = legacyDb.getParent();

        LOGGER.warn(
                "Found a pre-rename database at {} and none at {} — relocating it once.", legacyDb, AppPaths.DB_PATH);
        try {
            for (String suffix : new String[] {"", "-wal", "-shm"}) {
                java.nio.file.Path from = legacyDir.resolve(AppPaths.DB_FILE_NAME + suffix);
                java.nio.file.Path to = AppPaths.DATA_DIR.resolve(AppPaths.DB_FILE_NAME + suffix);
                if (!Files.exists(from)) {
                    continue;
                }
                try {
                    Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    // Different volume: copy instead, and leave the original for the user.
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.warn("  ↳ Copied (not moved) {} — the original is still on its old volume.", from);
                }
            }
            LOGGER.warn("  ↳ Done. Your data now lives at {}.", AppPaths.DATA_DIR);
        } catch (IOException e) {
            LOGGER.error(
                    "Could not relocate the legacy database ({}). Move {} to {} by hand.",
                    e.getMessage(),
                    legacyDb,
                    AppPaths.DB_PATH);
        }
    }

    public static void initializeSchema() {
        LOGGER.info("Initializing local SQLite database connection...");

        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }

            // Ensure version tracking table exists
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY);");
            }

            // Read current version
            int currentVersion = 0;
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT MAX(version) AS version FROM schema_version;")) {
                if (rs.next()) {
                    currentVersion = rs.getInt("version");
                }
            }

            // Handle unversioned or legacy database migrations
            if (currentVersion == 0) {
                if (!tableExists(conn, "issues")) {
                    // Fresh database. Migration 1 builds the core schema, and then every later
                    // migration runs on top of it exactly as it would on an existing database.
                    //
                    // It used to stamp CURRENT_SCHEMA_VERSION here instead, which said "Migration 1
                    // already contains everything the later ones add". Nothing enforced that, and it
                    // had drifted: a database created this way was stamped 11 while missing
                    // personal_chat_chunk from Migration 10 and pr_cache and repo_profile from
                    // Migration 11 -- so a new install had no passage index, no review cache and no
                    // repository profiles, and reported a schema version claiming otherwise.
                    //
                    // Running the real migrations is what keeps a new database and an upgraded one
                    // identical, rather than a promise somebody has to remember to keep. Every
                    // migration from 3 onwards is written to be safe when its work is already done.
                    MIGRATIONS[0].execute(conn);
                    setVersion(conn, 2);
                    currentVersion = 2;
                } else if (!columnExists(conn, "issues", "repository")) {
                    // Old unversioned single-repo DB (V1)
                    MIGRATIONS[1].execute(conn);
                    setVersion(conn, 2);
                    currentVersion = 2;
                } else {
                    setVersion(conn, 2);
                    currentVersion = 2;
                }
            }

            // Migrations only run forwards, so a store stamped higher than this build knows about
            // cannot be understood -- and until this check existed, nothing said so. The loop below
            // matched no migration, fell through in silence, and the command carried on reading
            // tables whose meaning may have changed, then writing rows in the shape it believed in.
            // Refusing costs one command; carrying on costs the store.
            if (currentVersion > CURRENT_SCHEMA_VERSION) {
                throw new SchemaTooNewException(currentVersion, CURRENT_SCHEMA_VERSION);
            }

            // Sequentially execute any remaining migrations registered in the array
            for (Migration migration : MIGRATIONS) {
                if (migration.getTargetVersion() > currentVersion
                        && migration.getTargetVersion() <= CURRENT_SCHEMA_VERSION) {
                    migration.execute(conn);
                    setVersion(conn, migration.getTargetVersion());
                    currentVersion = migration.getTargetVersion();
                }
            }

        } catch (SQLException e) {
            // This used to log and return, so a database that had failed to initialise let the
            // command run anyway -- every subsequent query failing against a schema that was never
            // built, under a heading that had already said what it was about to do. A warning that
            // scrolls past inside a command which then continues is worse than no warning.
            LOGGER.error("Database schema initialization failed: {}", e.getMessage(), e);
            throw new IllegalStateException("could not initialise the database at " + AppPaths.DB_PATH, e);
        }
    }

    private static void migrateTable(Connection conn, String tableName, String fields, String createTableSql)
            throws SQLException {
        if (tableExists(conn, tableName)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " RENAME TO old_" + tableName + ";");
                stmt.execute(createTableSql);
                stmt.execute("INSERT INTO " + tableName + " (repository, " + fields + ") "
                        + "SELECT 'apache/logging-log4j2', " + fields + " FROM old_" + tableName + ";");
                stmt.execute("DROP TABLE old_" + tableName + ";");
            }
        } else {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSql);
            }
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name=?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        String sql = "PRAGMA table_info(" + tableName + ");";
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds a column only when it is absent, so a migration can be re-run safely and a
     * database that already carries the column (fresh install) is left alone.
     */
    private static void addColumnIfMissing(Connection conn, String tableName, String columnName, String type)
            throws SQLException {
        if (!tableExists(conn, tableName) || columnExists(conn, tableName, columnName)) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + type + ";");
        }
    }

    private static void setVersion(Connection conn, int version) throws SQLException {
        String sql = "INSERT OR REPLACE INTO schema_version (version) VALUES (?);";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // Table Creation SQL Strings
    // ==========================================

    private static String getCreateIssuesTableSql() {
        return "CREATE TABLE IF NOT EXISTS issues (repository TEXT, number INTEGER, title TEXT, body TEXT, state TEXT, comments INTEGER, created_at TEXT, updated_at TEXT, is_pull_request BOOLEAN, author TEXT, author_association TEXT, PRIMARY KEY (repository, number));";
    }

    private static String getCreateLabelsTableSql() {
        return "CREATE TABLE IF NOT EXISTS labels (repository TEXT, issue_number INTEGER, label_name TEXT, PRIMARY KEY (repository, issue_number, label_name), FOREIGN KEY (repository, issue_number) REFERENCES issues(repository, number) ON DELETE CASCADE);";
    }

    private static String getCreateAiAnalysisTableSql() {
        return "CREATE TABLE IF NOT EXISTS ai_analysis (repository TEXT, issue_number INTEGER, severity TEXT, confidence REAL, reason TEXT, PRIMARY KEY (repository, issue_number), FOREIGN KEY (repository, issue_number) REFERENCES issues(repository, number) ON DELETE CASCADE);";
    }

    private static String getCreateEmbeddingsTableSql() {
        return "CREATE TABLE IF NOT EXISTS embeddings (repository TEXT, issue_number INTEGER, vector TEXT, embedding_model TEXT, embedding_dim INTEGER, PRIMARY KEY (repository, issue_number), FOREIGN KEY (repository, issue_number) REFERENCES issues(repository, number) ON DELETE CASCADE);";
    }

    private static String getCreateCrossRepoLinksTableSql() {
        return "CREATE TABLE IF NOT EXISTS cross_repo_links (source_repo TEXT, source_number INTEGER, target_repo TEXT, target_number INTEGER, link_type TEXT, PRIMARY KEY (source_repo, source_number, target_repo, target_number), FOREIGN KEY (source_repo, source_number) REFERENCES issues(repository, number) ON DELETE CASCADE);";
    }

    private static String getCreateSnapshotsTableSql() {
        return "CREATE TABLE IF NOT EXISTS snapshots (repository TEXT, date TEXT, critical_issues INTEGER, high_priority INTEGER, stale_prs INTEGER, duplicate_clusters INTEGER, PRIMARY KEY (repository, date));";
    }

    private static String getCreateJiraMentionsTableSql() {
        return "CREATE TABLE IF NOT EXISTS jira_mentions (repository TEXT, issue_number INTEGER, jira_key TEXT, PRIMARY KEY (repository, issue_number, jira_key), FOREIGN KEY (repository, issue_number) REFERENCES issues(repository, number) ON DELETE CASCADE);";
    }

    private static String getCreateMonitoredTableSql() {
        return "CREATE TABLE IF NOT EXISTS monitored_repositories (repository TEXT PRIMARY KEY, enabled BOOLEAN DEFAULT 1, last_synced_at TEXT);";
    }

    private static String getCreateConfigTableSql() {
        return "CREATE TABLE IF NOT EXISTS system_config (key TEXT PRIMARY KEY, value TEXT);";
    }

    /**
     * Seeds only impersonal defaults. Identity -- the GitHub username -- is deliberately absent:
     * it used to be seeded here, so every fresh database on every machine shipped preloaded with
     * one person's account and {@code sync --me} would have harvested their contribution history
     * onto a stranger's laptop. {@code setup} asks for it and {@code doctor} warns while it is
     * unset.
     */
    private static String getSeedConfigTableSql() {
        // No 'ollama.model.embedding' here any more. The embedder ships inside the tool and runs in
        // this process, so there is nothing to configure -- and a key that looks configurable but is
        // ignored is worse than no key, because it invites an answer that silently does nothing.
        return "INSERT OR IGNORE INTO system_config (key, value) VALUES "
                + "('ollama.model.triage', '" + com.osscli.Defaults.TRIAGE_MODEL + "'), "
                + "('ollama.model.guidance', '" + com.osscli.Defaults.GUIDANCE_MODEL + "'), "
                + "('ollama.url', '" + com.osscli.Defaults.OLLAMA_URL + "'), "
                + "('drive.paths', '');";
    }

    private static String getCreatePersonalCodeFootprintTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS personal_code_footprint (
                    repository TEXT,
                    issue_number INTEGER,
                    file_path TEXT,
                    PRIMARY KEY (repository, issue_number, file_path),
                    FOREIGN KEY (repository, issue_number) REFERENCES issues(repository, number) ON DELETE CASCADE
                );
                """;
    }

    private static String getCreatePersonalPrMemoryTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS personal_pr_memory (
                    repository TEXT,
                    pr_number INTEGER,
                    files_changed TEXT,
                    generated_story TEXT,
                    vector TEXT,
                    embedding_model TEXT,
                    embedding_dim INTEGER,
                    PRIMARY KEY (repository, pr_number)
                );
                """;
    }

    private static String getCreatePersonalChatMemoryTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS personal_chat_memory (
                    file_path TEXT PRIMARY KEY,
                    file_name TEXT,
                    last_modified INTEGER,
                    content TEXT,
                    vector TEXT,
                    embedding_model TEXT,
                    embedding_dim INTEGER
                );
                """;
    }

    /**
     * Raw GitHub evidence for one pull request at one commit.
     *
     * <p>Stored as fetched, not as rendered. The review layers above this re-read the same rows, so a verdict can be
     * regenerated with a different model or a rebuilt profile without spending the API calls again.
     */
    private static String getCreatePrCacheTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS pr_cache (
                    repository TEXT NOT NULL,
                    pr_number INTEGER NOT NULL,
                    head_sha TEXT NOT NULL,
                    title TEXT,
                    author TEXT,
                    state TEXT,
                    base_ref TEXT,
                    body TEXT,
                    commits_json TEXT,
                    files_json TEXT,
                    diff TEXT,
                    reviews_json TEXT,
                    comments_json TEXT,
                    checks_json TEXT,
                    additions INTEGER,
                    deletions INTEGER,
                    changed_files INTEGER,
                    fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (repository, pr_number, head_sha)
                );
                """;
    }

    /**
     * Edges between issues, pull requests and commits, as written by the people who wrote them.
     *
     * <p>{@code to_ref} is the identity -- {@code owner/name#123} or {@code sha:abc123} -- and exists so the primary
     * key stays simple. The parsed halves are stored beside it because retrieval looks up by number, and re-parsing a
     * string on every read to recover what was already known is work with no reader.
     *
     * <p>A reference to a repository that has never been synced is still recorded. It costs a row, it is true whether
     * or not this machine has the other side, and dropping it would mean re-reading every body the day that repository
     * is added.
     */
    private static String getCreateIssueReferencesTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS issue_references (
                    repository TEXT NOT NULL,
                    from_number INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    to_ref TEXT NOT NULL,
                    to_repository TEXT,
                    to_number INTEGER,
                    to_sha TEXT,
                    PRIMARY KEY (repository, from_number, to_ref)
                );
                """;
    }

    /**
     * What a repository is, in its own terms: language, build system, target version, conventions.
     *
     * <p>Derived from files the repository actually contains rather than from any list of known projects, so it is
     * built the same way for a repository nobody has seen before.
     */
    private static String getCreateRepoProfileTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS repo_profile (
                    repository TEXT PRIMARY KEY,
                    primary_language TEXT,
                    build_system TEXT,
                    target_version TEXT,
                    min_version TEXT,
                    conventions_json TEXT,
                    docs_json TEXT,
                    summary TEXT,
                    built_at DATETIME DEFAULT CURRENT_TIMESTAMP
                );
                """;
    }

    private static String getCreatePersonalChatChunkTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS personal_chat_chunk (
                    file_path TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT,
                    vector TEXT,
                    embedding_model TEXT,
                    embedding_dim INTEGER,
                    PRIMARY KEY (file_path, chunk_index)
                );
                """;
    }

    private static String getCreatePromptHistoryTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS prompt_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    issue_number INTEGER NOT NULL,
                    repository TEXT NOT NULL,
                    ollama_answered INTEGER DEFAULT 0,
                    escalation_reason TEXT,
                    ollama_response TEXT,
                    prompt_text TEXT,
                    token_estimate INTEGER,
                    confidence_score REAL,
                    provider_sent TEXT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                );
                """;
    }

    private static String getCreatePromptContextChunksTableSql() {
        return """
                CREATE TABLE IF NOT EXISTS prompt_context_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    prompt_id INTEGER NOT NULL REFERENCES prompt_history(id) ON DELETE CASCADE,
                    source_type TEXT NOT NULL,
                    source_ref TEXT,
                    content TEXT,
                    relevance_score REAL,
                    token_count INTEGER,
                    included INTEGER DEFAULT 1
                );
                """;
    }

    private static String getSeedPromptConfigSql() {
        return """
                INSERT OR IGNORE INTO system_config (key, value) VALUES
                -- Matches ContextRetriever's token budget. Seeding 4096 guaranteed a
                -- warning on every fresh install and made every request assembling more
                -- than 4096 tokens escalate for no reason.
                ('ollama.context_limit', '6000'),
                ('ollama.confidence_threshold', '0.70');
                """;
    }
}
