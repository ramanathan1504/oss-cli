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
package com.osscli.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.llm.Ai;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step between deciding who answers and actually asking them.
 *
 * <p>{@code Ai.route} was thoroughly tested and {@code Rungs} was not tested at all -- and Rungs is
 * where the decision becomes a thing with a label and a callable. That is the same shape as every
 * other gap found today: the two ends covered, the join between them nobody's.
 *
 * <p>Two properties here are load-bearing and neither is obvious from reading the route table.
 *
 * <p><b>The label is shown before anything is sent.</b> "whose model saw my code" is answered in
 * advance or it is answered too late, so every rung has to carry a name a person can read, and it
 * has to say which of a key and a subscription is being spent.
 *
 * <p><b>A rung that fails returns text, not an exception.</b> The loop treats a failed turn as an
 * observation and can still answer from what it already has; an exception ends the run and throws
 * away a corpus search that already succeeded. That is the difference between "the API is down, so
 * here is what your own archive says" and a stack trace.
 *
 * <p>Nothing here needs a key, a daemon or a network: what is asserted is the shape of the rung,
 * not the answer it would give.
 */
class RungsTest {

    @Test
    @DisplayName("every offered rung carries a label saying who answers and what it costs")
    void everyRungNamesItself() {
        for (Rungs.Chosen rung : Rungs.available("qwen2.5:0.5b")) {
            assertNotNull(rung.label(), "a rung with no label is one a user cannot be warned about");
            assertFalse(rung.label().isBlank(), "a rung with a blank label is the same thing");
            assertNotNull(rung.ask(), "a rung that cannot be asked is not a rung");
            // Either it is local and says so, or it is external and says which of the two ways it
            // is being reached -- a key that is metered, or a subscription that is not.
            assertTrue(
                    rung.label().contains("nothing leaves this machine")
                            || rung.label().contains("an API key")
                            || rung.label().contains("its own tool"),
                    "a rung must say what it costs: " + rung.label());
        }
    }

    @Test
    @DisplayName("only engines the route table can actually reach are offered")
    void unreachableEnginesAreNotOffered() {
        List<Rungs.Chosen> offered = Rungs.available("qwen2.5:0.5b");

        for (Ai.Engine engine : Ai.Engine.values()) {
            boolean listed = offered.stream().anyMatch(r -> r.label().startsWith(engine.label()));
            if (engine.isExternal() && Ai.routeFor(engine) == Ai.Route.NONE) {
                // Offering an engine with no key and no installed tool is offering a dead end, and
                // the user finds out only after choosing it.
                assertFalse(listed, engine.label() + " has no route and was offered anyway");
            }
        }
    }

    @Test
    @DisplayName("a local rung is offered only when the daemon is there and holds the model")
    void theLocalRungNeedsBothHalves() {
        // A model name nothing will ever have pulled. Whether a daemon is running on this machine
        // is not something a test may assume either way -- what it may assert is that a model which
        // does not exist is never offered as one that answers.
        List<Rungs.Chosen> offered = Rungs.available("no-such-model-a4f1c9");

        assertTrue(
                offered.stream().noneMatch(r -> r.label().contains("no-such-model-a4f1c9")),
                "a model that is not installed was offered as a rung: "
                        + offered.stream().map(Rungs.Chosen::label).toList());
    }

    // There is deliberately NO test here that calls rung.ask().
    //
    // The first version of this class had one, to prove a failing rung hands back text rather than
    // throwing. On a machine with a provider CLI signed in, `available()` offers that CLI and
    // asking it INVOKES it -- the test took thirty-nine seconds and spent a real subscription, and
    // in CI with a key present it would spend a real key. A test that costs money to run is a test
    // that gets deleted or, worse, kept and paid for.
    //
    // The property still matters and is covered where it can be reached for free: Loop's own tests
    // hand it a failing ask function and check the run continues, and CliTransportTest asserts a
    // tool reporting failure in a zero exit is not read as an answer. What is asserted here is the
    // SHAPE of the ladder, which needs nothing sent anywhere.

    @Test
    @DisplayName("nothing connected is an empty list, never a rung that cannot write")
    void theBuiltinModelIsNeverOfferedAsAWriter() {
        // The built-in 22 MB model ranks and retrieves; it does not write sentences, and deciding
        // what to look at next is writing a sentence. Offering it would produce a command that
        // appears to work and returns nothing.
        assertTrue(
                Rungs.available("no-such-model-a4f1c9").stream()
                        .noneMatch(r ->
                                r.label().toLowerCase(java.util.Locale.ROOT).contains("built-in")),
                "the built-in model was offered as something that can drive the loop");
    }

    @Test
    @DisplayName("asking for the rungs twice gives the same answer")
    void theListIsStable() {
        assertEquals(
                Rungs.available("qwen2.5:0.5b").stream()
                        .map(Rungs.Chosen::label)
                        .toList(),
                Rungs.available("qwen2.5:0.5b").stream()
                        .map(Rungs.Chosen::label)
                        .toList(),
                "the ladder changed between two calls on an unchanged machine");
    }
}
