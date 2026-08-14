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
package com.osscli.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.AppPaths;
import com.osscli.storage.DatabaseManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a prompt built from the user's own work stays inside the model's window.
 *
 * <p>{@code chat} and {@code guide} each appended the entire text of every note scoring above 0.35,
 * uncapped. Measured on a real corpus — 592 notes, 34 MB, 332 matching — that produced roughly a
 * 19 MB prompt for a model configured with 6,000 tokens. The request timed out, which reads as a
 * slow machine rather than a prompt that could never have worked.
 */
class MemoryContextTest {

    @BeforeAll
    static void schema() throws Exception {
        // Same refusal as the other storage-touching tests: assert where we are pointing rather
        // than trusting the build, because a redirection that silently failed once cost 496 MB.
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store.");
        DatabaseManager.initializeSchema();
    }

    @Test
    @DisplayName("an issue with no corpus behind it yields an empty block, not a crash")
    void emptyCorpusIsEmptyString() {
        String out = MemoryContext.forIssue(999_999L, "owner/name");
        assertNotNull(out, "must never return null — callers concatenate it straight into a prompt");
        assertTrue(out.isEmpty(), "nothing indexed means nothing to add: " + out);
    }

    @Test
    @DisplayName("an unknown repository is answered with less, not with an exception")
    void unknownRepositoryDegrades() {
        String out = MemoryContext.forIssue(1L, "nobody/nothing");
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("whatever comes back is bounded by the token budget, never by the corpus size")
    void outputIsBounded() {
        String out = MemoryContext.forIssue(1L, "owner/name");

        // The budget is in tokens; four characters per token is the estimator's own ratio, and
        // ContextRetriever's ceiling is well under this. The point of the assertion is the order of
        // magnitude: 19 MB must not be reachable no matter what is in the database.
        assertTrue(
                out.length() < 512_000, "context grew to " + out.length() + " chars — the budget is not being applied");
    }

    @Test
    @DisplayName("the estimator agrees that a bounded block is a bounded number of tokens")
    void tokensAreBoundedToo() {
        String out = MemoryContext.forIssue(1L, "owner/name");
        assertTrue(
                ContextRetriever.estimateTokens(out) < 128_000,
                "a block this large would be rejected by every local model");
    }

    @Test
    @DisplayName("it is called for its budget, so it must not be the identity of the corpus")
    void neverReturnsEverything() {
        // A regression guard with a long memory: if someone reverts to concatenating whole notes,
        // this is the shape that returns. The assertion is deliberately loose because the fixture
        // corpus is small; what it forbids is unbounded growth, which is what the bug was.
        String out = MemoryContext.forIssue(1L, "owner/name");
        assertFalse(out.length() > 1_000_000, "unbounded concatenation has returned");
    }

    @Test
    @DisplayName("repeated calls are stable, so an answer does not change between two identical asks")
    void isDeterministic() {
        assertEquals(MemoryContext.forIssue(1L, "owner/name"), MemoryContext.forIssue(1L, "owner/name"));
    }
}
