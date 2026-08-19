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
package com.osscli.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That "write like me" degrades to "write" rather than to a broken prompt.
 *
 * <p>The corpus is empty in a test run, which is also the state of every new install, so this
 * pins the case that matters most: an instruction promising examples and then showing none is
 * worse than the instruction on its own, and it is the version that ships to somebody who has
 * not synced anything yet.
 */
class OwnVoiceTest {

    @Test
    @DisplayName("with nothing in the archive, the instruction is handed back untouched")
    void anEmptyArchiveChangesNothing() {
        String instruction = "Summarise this change in one sentence.";

        String prompt = OwnVoice.inTheUsersVoice(instruction, "a pull request about rolling file appenders");

        // Not "instruction plus an empty examples section". A new install has an empty corpus and
        // must not be told to imitate a list of nothing.
        assertEquals(instruction, prompt, "an empty corpus must leave the prompt alone");
    }

    @Test
    @DisplayName("nothing to write about means nothing to retrieve")
    void noSubjectMeansNoLookup() {
        assertTrue(OwnVoice.samples(null).isEmpty());
        assertTrue(OwnVoice.samples("   ").isEmpty());
    }

    @Test
    @DisplayName("the instruction always survives, whatever the archive returns")
    void theInstructionIsNeverLost() {
        String instruction = "Answer in one sentence.";

        String prompt = OwnVoice.inTheUsersVoice(instruction, "rolling file appenders");

        // Whether or not examples were found, the thing the model was asked to do has to be in
        // there. A voice section that displaced the question would produce something in the right
        // register about the wrong subject.
        assertTrue(prompt.startsWith(instruction), prompt);
        assertFalse(prompt.contains("---\n\n"), "an empty example block leaked into the prompt: " + prompt);
    }
}
