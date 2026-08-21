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
package com.osscli.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Naming an engine that can answer must not refuse.
 *
 * <p>Two routes reach the same three providers: a key against the HTTP API, or the provider's own
 * command-line tool against the subscription it is signed in to. Only the key counted. So
 * {@code oss claude --cli review 4249} -- the flag typed, {@code claude} installed and logged in --
 * warned <em>"was named and has no key configured — 'oss setup'"</em> and escalated to nothing:
 * {@link Ai#escalationPath()} dropped the engine before {@code --cli} was ever consulted, and both
 * {@code review} and {@code chat} gate on that list being non-empty.
 *
 * <p>That is the trap this repository already knows by name -- a capability gated on one provider,
 * refusing a user who had the other half installed all along -- wearing a different coat: not one
 * provider against another this time, but one <em>transport</em> to the same provider.
 */
class EngineLadderTest {

    @AfterEach
    void clearSelection() {
        Ai.reset();
        Ai.useCli(false);
    }

    @Test
    @DisplayName("every combination of flag, key and installed tool has one stated answer")
    void theLadderIsATable() {
        // forcedCli, external, hasKey, toolInstalled
        assertEquals(Ai.Route.NONE, Ai.route(false, false, true, true), "the built-in and Ollama are not reached here");
        assertEquals(Ai.Route.NONE, Ai.route(true, false, true, true), "--cli does not make a local engine external");

        assertEquals(Ai.Route.API, Ai.route(false, true, true, true), "a key is preferred to the tool");
        assertEquals(Ai.Route.API, Ai.route(false, true, true, false), "a key alone is enough");

        assertEquals(
                Ai.Route.CLI, Ai.route(false, true, false, true), "no key, tool installed — the rung that was missing");
        assertEquals(Ai.Route.NONE, Ai.route(false, true, false, false), "neither half: nothing to answer with");

        assertEquals(Ai.Route.CLI, Ai.route(true, true, false, true), "--cli was typed");
        assertEquals(
                Ai.Route.CLI,
                Ai.route(true, true, true, false),
                "--cli was typed, so the key does not quietly win it back");
    }

    @Test
    @DisplayName("a key is never abandoned for a subscription without being asked")
    void aKeyIsPreferred() {
        // The one property that must not regress. An API key and a logged-in tool are two accounts;
        // moving between them silently changes who pays and what the harness may read. The ladder
        // only ever reaches DOWN to the tool from nothing, never sideways from a working key.
        assertEquals(Ai.Route.API, Ai.route(false, true, true, true));
    }

    @Test
    @DisplayName("--cli alone reaches the tool, with no key anywhere")
    void forcedCliEscalatesWithoutAKey() {
        Ai.select(List.of(Ai.Engine.CLAUDE));
        Ai.useCli(true);

        // Machine-independent: --cli short-circuits both the keychain and the PATH.
        assertEquals(List.of(Ai.Engine.CLAUDE), Ai.escalationPath(), "the engine typed must survive to escalation");
        assertTrue(Ai.missingCredentials().isEmpty(), "a tool that was asked for is not a missing credential");
    }

    @Test
    @DisplayName("the local rungs are never counted as external escalation")
    void localEnginesAreNotEscalation() {
        Ai.select(List.of(Ai.Engine.BUILTIN, Ai.Engine.OLLAMA));
        assertTrue(Ai.escalationPath().isEmpty());
        assertFalse(Ai.mayEscalate(), "nothing here leaves the machine");
    }

    @Test
    @DisplayName("asking whether a key exists is never itself an error")
    void askingForAKeyDoesNotThrow() {
        // This is what CI caught and this machine could not: `hasCredential` read
        // `CredentialManager.getClaudeKey()`, which THROWS when the key is absent, so the question
        // "can this engine answer?" raised "Anthropic API Key is missing. Run 'oss setup'" and
        // logged it at ERROR -- on every machine that simply had no key, which is every runner and
        // most new installs. It passed here only because this machine happens to have one.
        for (Ai.Engine engine : Ai.Engine.values()) {
            assertDoesNotThrow(engine::hasCredential, engine + " must answer, not raise");
            assertDoesNotThrow(() -> Ai.routeFor(engine), engine + " must resolve a route, not raise");
        }
        Ai.select(List.of(Ai.Engine.CLAUDE, Ai.Engine.GEMINI, Ai.Engine.OPENAI));
        assertDoesNotThrow(Ai::escalationPath);
        assertDoesNotThrow(Ai::missingCredentials);
    }

    @Test
    @DisplayName("routeFor agrees with the table on whatever machine is running it")
    void routeForIsWiredToTheTable() {
        for (Ai.Engine engine : List.of(Ai.Engine.CLAUDE, Ai.Engine.GEMINI, Ai.Engine.OPENAI)) {
            Ai.useCli(false);
            Ai.Route actual = Ai.routeFor(engine);
            if (engine.hasCredential()) {
                assertEquals(Ai.Route.API, actual, engine + " has a key here, so the key must be the route");
            } else {
                // No key: CLI when the tool is on this machine, NONE when it is not. Both are
                // correct answers -- asserting which one depends on the runner, so assert that it
                // is one of them and never API, which would mean a keyless call was about to go out.
                assertTrue(
                        actual == Ai.Route.CLI || actual == Ai.Route.NONE,
                        engine + " keyless must not route to the API");
            }
            Ai.useCli(true);
            assertEquals(Ai.Route.CLI, Ai.routeFor(engine), "--cli must win for " + engine);
        }
    }
}
