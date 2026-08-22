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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A build out of {@code target/} must not migrate somebody's real store.
 *
 * <p>{@link SchemaTooNewException} already refuses to READ a store written by a newer build, and
 * says loudly that migrations are one-way. The opposite direction was silent: a development jar
 * opened the same store, ran every pending migration, stamped it, and printed a progress line. Both
 * are one-way doors; only one asked.
 *
 * <p>On 2026-08-22 that cost an afternoon. One command was run without {@code OSS_CLI_HOME} set,
 * the checkout's jar took a 727 MB store from schema 14 to 15, and the installed release then
 * refused it — correctly — until a release carrying 15 existed.
 *
 * <p>The decision is a pure function precisely so this can be checked here. Exercising it for real
 * would mean pointing an unreleased build at the default store, which is the thing being prevented.
 */
class SchemaUpgradeGuardTest {

    @Test
    @DisplayName("unreleased code plus the real store plus pending migrations is the one refusal")
    void theExactCombinationIsRefused() {
        // buildOutput, defaultStore, allowed, current, build
        assertTrue(DatabaseManager.refuseUpgrade(true, true, false, 14, 15), "this is the afternoon that was lost");
    }

    @Test
    @DisplayName("an installed release migrating your store is the point of upgrading and never asks")
    void anInstalledBuildNeverAsks() {
        assertFalse(DatabaseManager.refuseUpgrade(false, true, false, 14, 15));
        assertFalse(DatabaseManager.refuseUpgrade(false, true, false, 1, 15), "however far behind it is");
    }

    @Test
    @DisplayName("a build output pointed somewhere else is what every test and experiment does")
    void aRedirectedStoreNeverAsks() {
        assertFalse(DatabaseManager.refuseUpgrade(true, false, false, 14, 15));
    }

    @Test
    @DisplayName("saying yes on purpose is honoured, and is the second way forward the message names")
    void theOverrideWorks() {
        assertFalse(DatabaseManager.refuseUpgrade(true, true, true, 14, 15));
    }

    @Test
    @DisplayName("a fresh store has nothing to lose, and an up-to-date one has nothing to do")
    void nothingToProtectIsNotRefused() {
        // Zero means "there was no store here when this started". CI proved this branch matters:
        // the bootstrap for a new database stamps an early version, so judging AFTER it made every
        // runner refuse the database it had just created a moment earlier.
        assertFalse(DatabaseManager.refuseUpgrade(true, true, false, 0, 15), "a brand new database");
        assertFalse(DatabaseManager.refuseUpgrade(true, true, false, 15, 15), "already current");
        // Newer than this build is SchemaTooNewException's job, and it is thrown first.
        assertFalse(DatabaseManager.refuseUpgrade(true, true, false, 16, 15));
    }

    @Test
    @DisplayName("the store is called default by whether it was chosen, not by where it points")
    void redirectionIsAboutTheChoice() {
        // The suite runs with OSS_CLI_HOME set to target/test-home, so this must read false here --
        // which is also the proof that nothing in this suite can trip the guard.
        assertFalse(
                AppPaths.isDefaultBaseDir(), "tests set OSS_CLI_HOME, so they must never be treated as the real store");
    }
}
