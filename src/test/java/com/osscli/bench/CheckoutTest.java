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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Preparing a pull request's own code to run against.
 *
 * <p>Nothing here reaches GitHub. What is checked is the half that decides whether somebody else's
 * build is about to execute on this machine, and the half that refuses to build a worktree
 * somewhere it has no business building one.
 */
class CheckoutTest {

    @Test
    @DisplayName("a worktree is never built in a directory that is not a clone of the repository")
    void refusesOutsideTheRightCheckout(@TempDir Path dir) {
        // An empty directory is not a git repository, so there is no object store to hang a
        // worktree off and no way to fetch the ref. Refusing here beats failing halfway through
        // with git's own words, which name a ref rather than the mistake.
        Checkout.Refused e = assertThrows(
                Checkout.Refused.class,
                () -> Checkout.prepare(
                        dir, "owner/name", 1, new Checkout.Prepared(null, "abc1234", false, "owner/name")));
        assertTrue(e.getMessage().contains("git checkout"), e.getMessage());
    }

    @Test
    @DisplayName("a clone of some other project is refused by name")
    void refusesTheWrongRepository() throws Exception {
        // This test runs inside oss-cli's own checkout, whose remote is oss-cli. Asked to prepare
        // a pull request belonging to a different project, it must say so rather than fetch a ref
        // that does not exist here and report git's error.
        Path here = Path.of(System.getProperty("user.dir", "."));
        if (!Files.isDirectory(here.resolve(".git"))) {
            return; // exported tree rather than a clone; there is nothing to assert against
        }
        Checkout.Refused e = assertThrows(
                Checkout.Refused.class,
                () -> Checkout.prepare(
                        here,
                        "some-other-owner/some-other-project",
                        1,
                        new Checkout.Prepared(null, "abc1234", false, "x/y")));
        assertTrue(e.getMessage().contains("some-other-owner/some-other-project"), e.getMessage());
    }

    @Test
    @DisplayName("discarding something that was never prepared is not an error")
    void discardIsSafeOnNothing() {
        // The run path calls this in a finally block, where the prepare may have thrown before
        // there was anything to remove.
        Checkout.discard(Path.of("."), null);
    }

    @Test
    @DisplayName("worktrees live beside the data, never inside the repository being reviewed")
    void worktreesAreKeptOutOfTheTree() {
        // A worktree inside the checkout would be picked up by the build it is about to run --
        // maven would find a second pom, a test would scan it, and the result would be about two
        // copies of the project at once.
        assertTrue(
                Checkout.DIR.startsWith(com.osscli.AppPaths.BASE_DIR),
                "worktrees must live under the data directory: " + Checkout.DIR);
    }

    @Test
    @DisplayName("a fork is anything whose head is not this repository's own branch")
    void forkDetectionIsAboutTheHeadRepository() {
        // Not inferred from the author's name, which is a guess: a maintainer pushing a branch to
        // the repository itself is not a fork, and an outsider whose name resembles theirs is.
        assertFalse(new Checkout.Prepared(null, "abc", false, "owner/name").fork());
        assertTrue(new Checkout.Prepared(null, "abc", true, "someone/name").fork());
    }

    @Test
    @DisplayName("the built-in runner is what --checkout can honestly point at a worktree")
    void onlyTheBuiltinRunnerTakesADirectory() {
        // An attached runner executes in its own root and is free to ignore a path handed to it.
        // Recording SAME_CODE for a run that never touched the worktree would be a wrong answer
        // wearing the badge of the right one, so --checkout refuses those verbs instead.
        for (String verb : List.of("detect", "build", "test", "doctor")) {
            assertTrue(com.osscli.runner.BuiltinRunner.supports(verb), verb + " is no longer built in");
        }
        assertFalse(com.osscli.runner.BuiltinRunner.supports("matrix"), "an extension verb must not pass the gate");
    }

    @Test
    @DisplayName("a run against a checked-out head records as being about that change")
    void recordedShaIsTheOneThatRan(@TempDir Path dir) {
        // The difference --checkout exists to make. ranOn is the sha that was checked out, not the
        // HEAD of wherever the command was typed.
        String sha = "609df1be479bfab1e75eadace55e69d464a0571a";
        BenchLedger.Row r = new BenchLedger.Row();
        r.repo = "owner/name";
        r.pr = 197;
        r.verb = "detect";
        r.prHead = sha;
        r.ranOn = sha;
        r.ranAt = "2026-08-24T12:24:53Z";

        assertEquals(BenchLedger.Trust.SAME_CODE, r.trust());
        assertEquals("detect passed", r.summary());
    }
}
