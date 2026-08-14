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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.cli.ChatCommand.Backends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * That chat runs on whatever model you have, and refuses only when you have none.
 *
 * <p>It used to exit the moment Ollama was unreachable, whatever else was configured — so somebody
 * with an API key and no wish to keep four gigabytes of weights on their laptop could not open a
 * chat at all. That is the same failure as requiring a cloud key was, pointing the other way, and
 * both break the rule the tool is built on: a capability may degrade, but it may not be gated on one
 * particular provider.
 *
 * <p>Three of these four states work. Only the empty one refuses.
 */
class ChatBackendsTest {

    @Nested
    @DisplayName("which state each combination is")
    class Resolution {

        @Test
        @DisplayName("Ollama and a key is BOTH")
        void ollamaAndKey() {
            assertEquals(Backends.BOTH, Backends.of(true, true));
        }

        @Test
        @DisplayName("Ollama alone is LOCAL_ONLY")
        void ollamaAlone() {
            assertEquals(Backends.LOCAL_ONLY, Backends.of(true, false));
        }

        @Test
        @DisplayName("a key alone is CLOUD_ONLY — the case that used to be refused")
        void keyAlone() {
            assertEquals(Backends.CLOUD_ONLY, Backends.of(false, true));
        }

        @Test
        @DisplayName("neither is NONE")
        void neither() {
            assertEquals(Backends.NONE, Backends.of(false, false));
        }
    }

    @Nested
    @DisplayName("what each state allows")
    class Capabilities {

        @Test
        @DisplayName("three of the four can hold a conversation")
        void threeOfFourAnswer() {
            assertTrue(Backends.BOTH.canAnswer());
            assertTrue(Backends.LOCAL_ONLY.canAnswer());
            assertTrue(Backends.CLOUD_ONLY.canAnswer(), "a cloud key alone is enough to chat");
            assertFalse(Backends.NONE.canAnswer(), "with no model at all there is nothing to talk to");
        }

        @Test
        @DisplayName("only NONE refuses")
        void onlyEmptyRefuses() {
            int refusing = 0;
            for (Backends b : Backends.values()) {
                if (!b.canAnswer()) {
                    refusing++;
                }
            }
            assertEquals(1, refusing, "exactly one state may refuse");
        }

        @Test
        @DisplayName("answers stay on the machine only when a local model is present")
        void staysLocal() {
            assertTrue(Backends.BOTH.staysLocal());
            assertTrue(Backends.LOCAL_ONLY.staysLocal());
            assertFalse(Backends.CLOUD_ONLY.staysLocal(), "every turn leaves the machine; the banner must say so");
            assertFalse(Backends.NONE.staysLocal());
        }

        @Test
        @DisplayName("'y' means something only when there is somewhere to escalate from and to")
        void escalation() {
            assertTrue(Backends.BOTH.escalates());
            assertFalse(Backends.LOCAL_ONLY.escalates(), "nothing to escalate to");
            assertFalse(
                    Backends.CLOUD_ONLY.escalates(),
                    "nothing to escalate from — the cloud already answers every turn, so offering "
                            + "the key would promise a second opinion that is the same opinion");
            assertFalse(Backends.NONE.escalates());
        }

        @Test
        @DisplayName("alignment needs a local model, because it reads your own history")
        void alignment() {
            assertTrue(Backends.BOTH.canAlign());
            assertFalse(
                    Backends.CLOUD_ONLY.canAlign(),
                    "sending a PR history to the same API that wrote the answer would undo the "
                            + "reason the two steps are separate");
            assertFalse(Backends.LOCAL_ONLY.canAlign(), "there is no cloud answer to align");
            assertFalse(Backends.NONE.canAlign());
        }

        @Test
        @DisplayName("escalating implies aligning: an escalation is never left unchecked")
        void escalationImpliesAlignment() {
            for (Backends b : Backends.values()) {
                if (b.escalates()) {
                    assertTrue(b.canAlign(), b + " escalates but cannot align, so an answer would go unchecked");
                }
            }
        }

        @Test
        @DisplayName("anything that can align can answer")
        void alignmentImpliesAnswering() {
            for (Backends b : Backends.values()) {
                if (b.canAlign()) {
                    assertTrue(b.canAnswer(), b + " claims to align without being able to answer");
                }
            }
        }

        @Test
        @DisplayName("a state that keeps answers local can always answer")
        void localImpliesAnswering() {
            for (Backends b : Backends.values()) {
                if (b.staysLocal()) {
                    assertTrue(b.canAnswer(), b + " claims to keep answers local while having none to keep");
                }
            }
        }
    }

    @Nested
    @DisplayName("the matrix as a whole")
    class Matrix {

        @Test
        @DisplayName("every combination of the two inputs maps to a distinct state")
        void allFourAreReachable() {
            java.util.Set<Backends> reached = new java.util.HashSet<>();
            for (boolean local : new boolean[] {true, false}) {
                for (boolean cloud : new boolean[] {true, false}) {
                    reached.add(Backends.of(local, cloud));
                }
            }
            assertEquals(
                    java.util.Set.of(Backends.BOTH, Backends.LOCAL_ONLY, Backends.CLOUD_ONLY, Backends.NONE),
                    reached,
                    "every state must be reachable, or one of them is dead code");
        }

        @Test
        @DisplayName("resolution depends only on what is available, not on the order asked")
        void isDeterministic() {
            for (boolean local : new boolean[] {true, false}) {
                for (boolean cloud : new boolean[] {true, false}) {
                    assertEquals(Backends.of(local, cloud), Backends.of(local, cloud));
                }
            }
        }
    }
}
