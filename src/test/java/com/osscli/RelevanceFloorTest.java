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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import com.osscli.retrieval.Corpus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two ways the tool used to answer a question it had no answer to.
 *
 * <p>Asked {@code oss memory search "keyspace"} against six notes, it returned three — at 0.10, 0.09
 * and 0.08 — about a website deployment and two release write-ups. None was about keyspaces. They
 * were simply the least unrelated files on disk, and they were printed in exactly the shape a real
 * hit takes. Every nearest-neighbour search has a nearest neighbour; without a floor, "I have
 * nothing on this" comes out as three confident results.
 *
 * <p>The second is the same failure in the model layer: an issue whose URL could not be parsed was
 * reported as belonging to {@code apache/logging-log4j2} — the project this tool was written
 * against, hardcoded as a fallback. A guess with a real project's name on it is worse than a blank,
 * because {@code sync} then fetched that project's files and stored them as the user's own work.
 */
class RelevanceFloorTest {

    @Test
    @DisplayName("a floor exists at all, and is high enough to exclude what was being shown")
    void floorExcludesTheObservedNoise() {
        // The three scores that prompted this. If the floor ever drops below them the noise is back.
        for (double noise : new double[] {0.10, 0.09, 0.08}) {
            assertTrue(
                    noise < Corpus.RELEVANCE_FLOOR,
                    "a " + noise + " cosine would be presented as a search result again");
        }
        assertTrue(Corpus.RELEVANCE_FLOOR < 0.35, "but not so high that real subject matches are cut");
    }

    @Test
    @DisplayName("an issue that does not say which repository it is from returns nothing, not log4j")
    void unparseableUrlYieldsNothing() {
        assertNoRepository(issueAt(null));
        assertNoRepository(issueAt(""));
        assertNoRepository(issueAt("not-a-url"));
        assertNoRepository(issueAt("https://github.com/"));
        assertNoRepository(issueAt("https://github.com/owner"));
        assertNoRepository(issueAt("https://example.com/owner/name/pull/1"));
    }

    @Test
    @DisplayName("an issue that does say is read from its own URL, whatever the project")
    void parseableUrlIsHonoured() {
        assertTrue("canonical/cloud-init"
                .equals(issueAt("https://github.com/canonical/cloud-init/pull/6087")
                        .getRepositoryOwnerAndName()));
        assertTrue("apache/logging-log4j2"
                .equals(issueAt("https://github.com/apache/logging-log4j2/issues/4143")
                        .getRepositoryOwnerAndName()));
    }

    private static void assertNoRepository(Issue issue) {
        String actual = issue.getRepositoryOwnerAndName();
        assertFalse(
                "apache/logging-log4j2".equals(actual),
                "one project's name was returned as a default for an issue that never named it");
        assertTrue(actual == null, "and the honest answer is nothing at all, got: " + actual);
    }

    /** An Issue carrying only the field under test. */
    private static Issue issueAt(String htmlUrl) {
        return new Issue(1L, "a title", null, "open", 0, null, null, null, null, null, null, htmlUrl);
    }
}
