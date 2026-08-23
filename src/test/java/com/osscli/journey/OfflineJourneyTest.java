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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With the network gone: what still answers, and how the rest refuses.
 *
 * <p>"Sync once, then the network is optional" is on the landing page, in the docs, and in
 * OFFLINE.md, where a test already checks that the two lists partition the command set. What no
 * test did was unplug the network and run them.
 *
 * <p>Every journey here points {@code GITHUB_API_URL} at a port nothing listens on, which is what
 * a train, a firewall and a dead DNS all look like from inside the process. Two things are
 * asserted, and the second matters more than the first: that the offline half still works, and
 * that the online half fails in a sentence a person can act on rather than a stack trace.
 */
class OfflineJourneyTest {

    @Test
    @DisplayName("a command it cannot complete refuses with a remedy, whichever wall it hits first")
    void aRefusalAlwaysCarriesItsFix(@TempDir Path home, @TempDir Path work) throws Exception {
        // Which wall comes first is a property of the MACHINE, not of the product. With no
        // credential anywhere it stops at "GitHub Token is missing"; with one it gets as far as
        // "no network". CredentialManager falls back to the macOS keychain after the environment,
        // so a developer laptop reaches the second and a CI runner reaches the first -- and the
        // version of this test that asserted only the second passed here and failed on all four
        // runners. The contract is what both refusals share.
        for (String[] argv : new String[][] {{"issue", "1", "-r", "owner/name"}, {"pr", "1", "-r", "owner/name"}}) {
            Journey.Ran ran = Journey.oss(home, work, argv);

            assertNotEquals(0, ran.code(), "a command that could not do its job exited 0: " + ran.all());
            assertTrue(
                    ran.all().contains("oss setup") || ran.all().contains("oss search"),
                    "every refusal must carry a next step: " + ran.all());
            assertNoStackTrace(ran);
        }
    }

    @Test
    @DisplayName("with a token and no network, it names the network and what still works")
    void withATokenButNoNetwork(@TempDir Path home, @TempDir Path work) throws Exception {
        // Past the credential, into the thing this journey is actually about. Which of these two
        // refusals you get depends on whether a token exists, and asserting only this one is what
        // made the first version of this test pass here and fail on every CI runner.
        for (String[] argv : new String[][] {{"issue", "1", "-r", "owner/name"}, {"pr", "1", "-r", "owner/name"}}) {
            Journey.Ran ran = Journey.ossWithToken(home, work, argv);

            assertNotEquals(0, ran.code(), "a command that could not reach GitHub exited 0: " + ran.all());
            assertTrue(
                    ran.all().toLowerCase(java.util.Locale.ROOT).contains("no network"),
                    "the failure must name what went wrong: " + ran.all());
            // The remedy is the half that makes an error useful. An absence without one is a
            // complaint.
            assertTrue(ran.all().contains("oss search"), "it must name what still works: " + ran.all());
            assertNoStackTrace(ran);
        }
    }

    private static void assertNoStackTrace(Journey.Ran ran) {
        assertFalse(ran.all().contains("java.net."), "a stack trace reached the user: " + ran.all());
        assertFalse(ran.all().contains("\tat com.osscli"), "a stack trace reached the user: " + ran.all());
    }

    @Test
    @DisplayName("the offline half is unaffected by there being no network at all")
    void theOfflineHalfStillAnswers(@TempDir Path home, @TempDir Path work) throws Exception {
        Files.writeString(work.resolve("note.md"), "# Retry budget\n\nThree attempts, then give up.\n");
        Journey.oss(home, work, "memory", "file", "note.md");

        for (String[] argv : new String[][] {
            {"memory", "search", "retry budget"},
            {"doctor"},
            {"hub"},
            {"skill"},
            {"ext", "list"}
        }) {
            Journey.Ran ran = Journey.oss(home, work, argv);
            assertEquals(0, ran.code(), "offline command '" + String.join(" ", argv) + "' failed: " + ran.all());
        }

        // And the archive really answers, rather than merely exiting 0.
        Journey.Ran found = Journey.oss(home, work, "memory", "search", "retry budget");
        assertTrue(found.all().contains("Retry budget"), found.all());
    }

    @Test
    @DisplayName("doctor reports the network as unreachable instead of depending on it")
    void doctorPingsRatherThanDepends(@TempDir Path home, @TempDir Path work) throws Exception {
        // doctor is the command somebody runs precisely when things are broken. If it needed the
        // thing it is diagnosing, it would be useless exactly when it is wanted.
        Journey.Ran ran = Journey.oss(home, work, "doctor");

        assertEquals(0, ran.code(), ran.all());
        assertFalse(ran.all().contains("Exception"), ran.all());
    }

    @Test
    @DisplayName("sync with nothing registered explains itself rather than failing at the network")
    void syncWithNothingToDo(@TempDir Path home, @TempDir Path work) throws Exception {
        // The first command a new install runs, before anything is followed. It must not reach the
        // network to discover it has nothing to fetch.
        Journey.Ran ran = Journey.oss(home, work, "sync", "--all");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(ran.all().contains("--add"), "it must say how to follow a project: " + ran.all());
        assertFalse(ran.all().toLowerCase(java.util.Locale.ROOT).contains("no network"), ran.all());
    }
}
