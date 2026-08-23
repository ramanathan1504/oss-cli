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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Follow a project, pull it down, then answer from it: {@code sync}, then {@code search}, then the
 * review ledger that {@code followup} reads.
 *
 * <p>This is the sentence the whole product rests on -- "sync once, then the network is optional"
 * -- and no test had ever performed it. {@code sync} had tests for parsing what GitHub returns.
 * {@code search} had tests for ranking what is already in the database. Whether the rows the first
 * writes are the rows the second reads was nobody's.
 *
 * <p>Against {@link FakeGitHub} rather than the real one, so it is the same on a train.
 */
class SyncJourneyTest {

    @Test
    @DisplayName("what sync pulled is what search finds, with the network gone afterwards")
    void syncThenSearchOffline(@TempDir Path home, @TempDir Path work) throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            Journey.Ran added = Journey.ossAgainst(github, home, work, "sync", "--add", "owner/name");
            assertEquals(0, added.code(), added.all());

            Journey.Ran synced = Journey.ossAgainst(github, home, work, "sync", "-r", "owner/name", "--no-embed");
            assertEquals(0, synced.code(), synced.all());
            assertTrue(
                    github.sawPathContaining("/repos/owner/name"),
                    "sync never asked for the repository: " + github.asked());

            // The point of the whole thing: the network is now gone, and the answer is still here.
            // The words as they appear. Term search matches terms -- it does not stem, and the
            // documentation says so: "ranks by WHICH words a query and a document share". Searching
            // "deadlock" for a title reading "deadlocks" finds nothing, which is the honest
            // behaviour of the floor and the reason `oss model --fetch` exists.
            Journey.Ran found = Journey.oss(home, work, "search", "eviction sweep locks", "-r", "owner/name");
            assertEquals(0, found.code(), found.all());
            assertTrue(
                    found.all().contains("4129") || found.all().contains("Pool deadlock"),
                    "what sync pulled was not findable offline: " + found.all());
        }
    }

    @Test
    @DisplayName("a recorded review is what followup reports on")
    void recordThenFollowup(@TempDir Path home, @TempDir Path work) throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            Journey.ossAgainst(github, home, work, "sync", "--add", "owner/name");

            // followup --record is the only thing in this tool that writes a verdict, and it writes
            // it locally. Nothing is posted anywhere, which is the rule that does not bend.
            Journey.Ran recorded = Journey.ossAgainst(
                    github, home, work, "followup", "--record", "812", "--repo", "owner/name", "--verdict", "take");
            assertEquals(0, recorded.code(), recorded.all());

            // hub reads the same ledger. Two commands, one record: if they disagree, the one you
            // did not run is the one that is wrong, and you would never find out.
            Journey.Ran hub = Journey.oss(home, work, "hub");
            assertEquals(0, hub.code(), hub.all());
            assertTrue(
                    hub.all().contains("812") || hub.all().contains("owner/name"),
                    "the review was recorded and hub does not know: " + hub.all());
        }
    }
}
