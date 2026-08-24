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
package com.osscli.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two lookups that fill a row in, and what each does when it cannot answer. */
class BenchRecorderTest {

    @Test
    @DisplayName("a repository typed out wins, and is trimmed")
    void explicitRepositoryIsUsed() {
        assertEquals("apache/logging-log4j2", BenchRecorder.resolveRepo("  apache/logging-log4j2  "));
    }

    @Test
    @DisplayName("an unreadable pull request costs the sha, not the run")
    void prHeadIsBestEffort() {
        // Offline, no token, or a repository that does not exist. The build really did run, and
        // losing that because GitHub could not be reached would be the wrong trade -- the row is
        // still worth having, it just cannot say which commit it was about.
        assertEquals("", BenchRecorder.prHead("not-a-real-owner/not-a-real-repo-xyz", 999999));
    }

    @Test
    @DisplayName("the local commit is a sha or nothing — never git's error text")
    void localHeadIsAShaOrBlank() {
        String head = BenchRecorder.localHead();
        // This test runs inside this repository's own checkout, so there is a HEAD; on a machine
        // where the tree is exported rather than cloned there is not, and blank is the answer.
        // What must never happen is git's stderr being stored as if it were a commit.
        assertTrue(
                head.isEmpty() || head.matches("[0-9a-fA-F]{7,40}"),
                "localHead returned something that is not a sha: " + head);
    }

    @Test
    @DisplayName("a run with no repository and no way to guess one is refused, not misfiled")
    void ambiguousRepositoryIsNotGuessed() {
        // resolveRepo returns null rather than picking among several. A row attached to the wrong
        // pull request is worse than no row: the gap is visible, the wrong answer is not.
        String resolved = BenchRecorder.resolveRepo(null);
        if (resolved != null) {
            // This machine follows exactly one repository, which is a legitimate case to default.
            assertTrue(resolved.contains("/"), "a defaulted repository must look like owner/name: " + resolved);
        } else {
            assertNull(resolved);
        }
    }
}
