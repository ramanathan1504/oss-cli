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

import com.osscli.memory.BuiltinMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a dispatcher says when you type it without a verb.
 *
 * <p>It used to say {@code Missing required parameter: '<verb>'} and then print a usage block whose
 * example verbs — {@code run, matrix, review, file, index, search} — are a fixed string in the
 * source. They are not read from anything: an attached archive declaring {@code harvest} and
 * {@code coverage} was never mentioned, and a reader was pointed at verbs it does not offer.
 *
 * <p>Which is the wrong way round for the one command whose capabilities are not knowable at
 * compile time. The verbs live in a manifest on this machine, so the listing has to be read at
 * runtime or it is fiction.
 */
class DispatchDiscoveryTest {

    @Test
    @DisplayName("bare `oss memory` lists what it can do instead of refusing")
    void memoryListsItsVerbs() {
        Cli.Result r = Cli.run("memory");

        assertTrue(r.ok(), "asking what is available is a question, not a mistake: " + r.all());
        assertFalse(r.says("Missing required parameter"), "the old refusal is back: " + r.all());

        // No extension is registered under the test home, so this is the built-in store — and
        // every verb it advertises must be one it actually answers.
        for (String verb : BuiltinMemory.VERBS) {
            assertTrue(r.says("oss memory " + verb), "did not offer \"" + verb + "\": " + r.all());
        }
        assertTrue(r.says("oss ext add"), "never says how to attach more: " + r.all());
    }

    @Test
    @DisplayName("bare `oss run` names the route that works with nothing attached")
    void runNamesThePackRoute() {
        Cli.Result r = Cli.run("run");

        assertTrue(r.ok(), r.all());
        assertFalse(r.says("Missing required parameter"), r.all());
        // A runner extension is one route and not the common one. Printing only "attach an
        // extension" would hide the engine that ships inside from the person most likely to
        // need it.
        assertTrue(r.says("--pack"), "hid the built-in engine: " + r.all());
    }

    @Test
    @DisplayName("a verb still dispatches, and an unknown one still fails")
    void namingAVerbIsUnchanged() {
        // The listing is what happens when the verb is ABSENT. Everything else about the
        // dispatcher has to be exactly as it was, including refusing a verb nobody offers.
        Cli.Result r = Cli.run("memory", "not-a-verb");

        assertFalse(r.ok(), "an unknown verb must still fail: " + r.all());
        assertTrue(r.says("not-a-verb"), "did not say which verb it refused: " + r.all());
    }
}
