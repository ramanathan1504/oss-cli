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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That an older build refuses a store a newer one has migrated.
 *
 * <p>This is the case that used to pass silently: the migration loop matched nothing, fell through,
 * and the command carried on reading — and then writing — a schema it did not understand. The damage
 * from that is quiet and cumulative, which is why the refusal is the point rather than the warning.
 */
class SchemaVersionGuardTest {

    @BeforeAll
    static void schema() throws Exception {
        // The same refusal as SchemaTest, for the same reason: these tests write version rows into
        // whatever database AppPaths resolves, and a redirection that silently failed to apply once
        // cost a real 496 MB store. Assert where we are pointing rather than trusting the build.
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store. "
                        + "Set OSS_CLI_HOME (the environment variable, not the oss.cli.home property).");
        DatabaseManager.initializeSchema();
    }

    @AfterEach
    void restoreVersion() throws Exception {
        // Every test here fiddles with the stamped version, and the rest of the suite shares this
        // database. Leaving it stamped high would fail every later test with a refusal that has
        // nothing to do with what it was testing.
        stamp(DatabaseManager.currentSchemaVersion());
    }

    private static void stamp(int version) throws Exception {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM schema_version;");
            stmt.execute("INSERT INTO schema_version (version) VALUES (" + version + ");");
        }
    }

    @Test
    @DisplayName("a store stamped newer than this build is refused, not opened")
    void newerStoreIsRefused() throws Exception {
        int understood = DatabaseManager.currentSchemaVersion();
        stamp(understood + 1);

        SchemaTooNewException thrown = assertThrows(SchemaTooNewException.class, DatabaseManager::initializeSchema);

        assertEquals(understood + 1, thrown.storeVersion());
        assertEquals(understood, thrown.understoodVersion());
    }

    @Test
    @DisplayName("the refusal names both versions, so the message can say what to do")
    void refusalCarriesBothNumbers() throws Exception {
        stamp(DatabaseManager.currentSchemaVersion() + 7);

        SchemaTooNewException thrown = assertThrows(SchemaTooNewException.class, DatabaseManager::initializeSchema);

        assertTrue(thrown.getMessage().contains(String.valueOf(thrown.storeVersion())));
        assertTrue(thrown.getMessage().contains(String.valueOf(thrown.understoodVersion())));
    }

    @Test
    @DisplayName("refusing changes nothing on disk")
    void refusalIsReadOnly() throws Exception {
        int tooNew = DatabaseManager.currentSchemaVersion() + 1;
        stamp(tooNew);

        assertThrows(SchemaTooNewException.class, DatabaseManager::initializeSchema);

        assertEquals(tooNew, DatabaseManager.storedSchemaVersion(), "the refusal must not renumber the store");
    }

    @Test
    @DisplayName("a store at the same version opens normally")
    void currentStoreIsFine() {
        assertDoesNotThrow(DatabaseManager::initializeSchema);
    }

    @Test
    @DisplayName("an older store is migrated forwards rather than refused")
    void olderStoreMigrates() throws Exception {
        stamp(DatabaseManager.currentSchemaVersion() - 1);

        assertDoesNotThrow(DatabaseManager::initializeSchema);
        assertEquals(DatabaseManager.currentSchemaVersion(), DatabaseManager.storedSchemaVersion());
    }

    @Test
    @DisplayName("the stored version is readable without migrating")
    void storedVersionIsReadable() throws Exception {
        stamp(DatabaseManager.currentSchemaVersion() + 3);

        // Answerable on a database initializeSchema has already refused -- which is exactly when
        // doctor needs it.
        assertEquals(DatabaseManager.currentSchemaVersion() + 3, DatabaseManager.storedSchemaVersion());
    }
}
