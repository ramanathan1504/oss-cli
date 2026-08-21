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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.cli.NearMiss;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a verb typed one level too deep is answered with the command that does the job.
 *
 * <p>{@code oss memory sync --me} was the line that cost a session. {@code sync} is a top-level
 * command, {@code --me} is its flag, and the whole thing was three characters from correct — but
 * the built-in memory replied "no verb \"sync\"", listed its own eleven, and never named the
 * command one level up. Accurate, and it sent the reader looking for a memory verb that is never
 * going to exist.
 *
 * <p>Driven through the real command tree rather than by calling the helper and agreeing with it.
 * The helper being right is not the claim; what a user sees on screen is.
 */
class NearMissTest {

    @Test
    @DisplayName("oss memory sync --me names oss sync --me, with the flag kept")
    void memorySuggestsSync() {
        Cli.Result r = Cli.run("memory", "sync", "--me");
        assertEquals(2, r.exitCode(), "it still refuses; the hint does not make it succeed");
        assertTrue(r.says("oss sync --me"), "the runnable line has to be there, not just the name: " + r.all());
    }

    /**
     * Why {@code oss run} is not wired the same way, asserted rather than remembered.
     *
     * <p>{@code run}'s rule is "a built-in verb is answered here, everything else goes to the
     * pack", so an unknown verb never reaches a verb list — it reaches the engine, which answers
     * about packs. Wiring the hint into {@link com.osscli.runner.BuiltinRunner}'s default branch
     * looked right and was dead code: nothing typed at the command line can get there. If that
     * dispatch rule ever changes, this fails and the hint becomes worth adding.
     */
    @Test
    @DisplayName("run hands an unknown verb to the pack, which is why the hint is not there")
    void runDoesNotReachAVerbList() {
        Cli.Result r = Cli.run("run", "search", "rollover");
        assertFalse(r.says("the built-in runner has no verb"), "dispatch changed; revisit the hint: " + r.all());
    }

    @Test
    @DisplayName("a verb that names nothing anywhere gets no invented suggestion")
    void nothingIsInvented() {
        Cli.Result r = Cli.run("memory", "sproing");
        assertEquals(2, r.exitCode());
        assertFalse(r.says("is a command of its own"), "there is no oss sproing: " + r.all());
        // The floor still holds: it must still say what memory does know.
        assertTrue(r.says("harvest"), r.all());
    }

    @Test
    @DisplayName("an alias is answered with the canonical name, not a second spelling")
    void aliasResolvesToCanonical() {
        // `kb` is an alias of `memory`. A suggestion is the wrong place to teach a second name for
        // the same command, so the hint says `oss memory`.
        assertEquals("oss memory map", NearMiss.elsewhere("run", "kb", List.of("map")));
    }

    @Test
    @DisplayName("a dispatcher is never told to run itself")
    void noSelfSuggestion() {
        assertNull(NearMiss.elsewhere("run", "run", List.of()), "oss run run must not suggest oss run");
        assertNull(NearMiss.elsewhere("memory", "memory", List.of()));
        assertNull(NearMiss.elsewhere("memory", "", List.of()));
        assertNull(NearMiss.elsewhere("memory", null, List.of()));
    }

    @Test
    @DisplayName("every top-level command is reachable as a hint, so the list cannot drift")
    void readsTheRealCommandTree() {
        // The point of reading RootCommand's annotation rather than keeping a list: a command
        // added tomorrow is suggestable today. Nothing fails when a hint stops being offered, so
        // this is the only thing that would catch a hand-kept copy going stale.
        for (String command : List.of("sync", "search", "chat", "guide", "doctor", "backlog", "pick")) {
            assertEquals("oss " + command, NearMiss.elsewhere("memory", command, List.of()), command + " is a command");
        }
    }
}
