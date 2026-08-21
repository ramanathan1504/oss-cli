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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a new database is the same shape as an upgraded one.
 *
 * <p>This exists because it was not. A fresh database was built by the first migration and then
 * stamped at the current schema version, on the understanding that the first migration contained
 * everything the later ones add. Nothing checked, and it drifted: new installs were stamped 11 while
 * missing three tables, so they had no passage index, no review cache and no repository profiles,
 * and reported a version saying otherwise.
 *
 * <p>The failure was silent for as long as nobody looked, which is the argument for the test rather
 * than for a more careful reading of the migration list. The assertion below is deliberately a list
 * of names: adding a table to a migration and not to this list should fail, because that is exactly
 * the moment the two can part company again.
 */
class SchemaTest {

    /**
     * Every table the migrations create.
     *
     * <p>Written out rather than derived, so this fails when a migration adds a table and nobody
     * updates it. A test that computes its own expectation from the thing it is testing cannot
     * disagree with it.
     */
    private static final Set<String> EXPECTED = new LinkedHashSet<>(java.util.List.of(
            "ai_analysis",
            "chat_session",
            "chat_turn",
            "cross_repo_links",
            "embeddings",
            "issue_references",
            "issues",
            "jira_mentions",
            "labels",
            "monitored_repositories",
            "personal_chat_chunk",
            "personal_chat_memory",
            "personal_code_footprint",
            "personal_pr_memory",
            "pr_cache",
            "prompt_context_chunks",
            "prompt_history",
            "repo_profile",
            "schema_version",
            "snapshots",
            "system_config"));

    @BeforeAll
    static void freshDatabase() throws Exception {
        // Refuse to run anywhere near the real store, and refuse LOUDLY.
        //
        // This test deletes a database. The build redirects OSS_CLI_HOME to target/ so the one it
        // deletes is disposable -- but a redirection that silently fails to apply turns this line
        // into data loss, and that is not hypothetical: the redirection was first written as a
        // system property, which AppPaths does not read, and this deleted a real 496 MB database.
        //
        // So the guard does not trust the configuration. It asserts the outcome.
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store. "
                        + "Set OSS_CLI_HOME (the environment variable, not the oss.cli.home property).");

        Files.deleteIfExists(AppPaths.DB_PATH);
        DatabaseManager.initializeSchema();
    }

    private static Set<String> tables() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")) {
            while (rs.next()) {
                found.add(rs.getString("name"));
            }
        }
        return found;
    }

    @Test
    @DisplayName("a new database has every table the migrations create")
    void freshHasEveryTable() throws Exception {
        Set<String> actual = tables();
        Set<String> missing = new LinkedHashSet<>(EXPECTED);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), "a fresh database is missing: " + missing);
    }

    @Test
    @DisplayName("and no table the migrations do not")
    void freshHasNoStrangers() throws Exception {
        Set<String> unexpected = new LinkedHashSet<>(tables());
        unexpected.removeAll(EXPECTED);
        assertTrue(unexpected.isEmpty(), "unexpected tables, add them to EXPECTED if intended: " + unexpected);
    }

    @Test
    @DisplayName("a new database monitors nothing, and names nobody")
    void freshMonitorsNothing() throws Exception {
        // The migrations used to seed fourteen real third-party repositories -- log4j, kafka,
        // spark, elasticsearch and the rest -- into every fresh install, so a stranger's first
        // `oss sync --all` fetched hundreds of megabytes from projects they had never named,
        // against their API budget. One person's interests shipping as everybody's default is the
        // opposite of a tool for any OSS developer, and nothing in the suite noticed for as long
        // as it shipped.
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS n FROM monitored_repositories;")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("n"), "a fresh install must watch only what its owner adds");
        }
    }

    @Test
    @DisplayName("the reported version is the one the migrations actually reached")
    void versionIsHonest() throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT MAX(version) AS v FROM schema_version;")) {
            assertTrue(rs.next());
            // The number itself is not the point; that the tables exist at it is. Bump this with
            // CURRENT_SCHEMA_VERSION, and only after the two tests above still pass.
            assertEquals(14, rs.getInt("v"));
        }
    }

    @Test
    @DisplayName("the columns the vector tables depend on are present")
    void provenanceColumns() throws Exception {
        for (String table : new String[] {"embeddings", "personal_chat_memory", "personal_chat_chunk"}) {
            Set<String> cols = columns(table);
            assertTrue(cols.contains("embedding_model"), table + " has no embedding_model");
            assertTrue(cols.contains("embedding_dim"), table + " has no embedding_dim");
        }
        assertTrue(columns("personal_chat_memory").contains("tier"), "personal_chat_memory has no tier");
    }

    private static Set<String> columns(String table) throws Exception {
        Set<String> cols = new LinkedHashSet<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ");")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }
}
