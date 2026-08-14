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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a model is never given so much memory that the machine stops responding.
 *
 * <p>Ollama loads a model too large for the machine rather than refusing it. The operating system
 * then swaps, and the laptop stops responding for minutes — measured at ten on an 8 GB Apple-silicon
 * machine with a 7B model and a browser open. It cannot be read like an error and it cannot be
 * cancelled; it has to be waited out. This is the arithmetic that prevents it.
 */
class MachineMemoryTest {

    private static final long GB = 1_000_000_000L;

    @Test
    @DisplayName("half the free memory is offered, and half is kept")
    void halfAndHalf() {
        // The rule stated plainly: 2 GB free on an 8 GB machine means 1 GB for the model and 1 GB
        // left for everything the user already has open.
        MachineMemory m = MachineMemory.of(8 * GB, 2 * GB);

        assertEquals(GB, m.usableBytes(), "a model may have half of what is free");
        assertEquals(GB, m.reserveBytes(), "the other half stays with the user");
        assertEquals(
                m.availableBytes(), m.usableBytes() + m.reserveBytes(), "the two halves must account for all of it");
    }

    @Test
    @DisplayName("a busy machine still offers something, rather than nothing")
    void aLoadedMachineIsNotLockedOut() {
        // This is the case a fixed reserve got wrong. With 2.2 GB free and a 2 GB reserve
        // subtracted, 0.1 GB was left and every model was refused -- including a 0.5B one that had
        // just run perfectly well moments earlier. A rule that forbids what demonstrably works is
        // not protecting anyone; it has only turned the feature off.
        MachineMemory m = MachineMemory.of(8_589_934_592L, 2_200_000_000L);

        assertTrue(m.usableBytes() > 1_000_000_000L, "a 0.5B model must still be allowed on a loaded laptop");
        assertTrue(m.usableBytes() < 4_700_000_000L, "a 7B model must not be");
    }

    @Test
    @DisplayName("the share holds at every size, so no machine is a special case")
    void theRuleScales() {
        for (long freeGb : new long[] {1, 2, 4, 8, 16, 64}) {
            MachineMemory m = MachineMemory.of(128 * GB, freeGb * GB);
            assertEquals(freeGb * GB / 2, m.usableBytes(), freeGb + " GB free should offer half");
            assertTrue(m.reserveBytes() > 0, "something is always kept back");
        }
    }

    @Test
    @DisplayName("an unreadable machine is unknown, and unknown is never a refusal")
    void unknownIsNotZero() {
        // The check exists to prevent a freeze. Refusing on no evidence would be its own failure:
        // a machine whose memory cannot be read is not a machine that has none.
        assertFalse(MachineMemory.UNKNOWN.known());
        assertEquals("memory unknown", MachineMemory.UNKNOWN.toString());
    }

    @Test
    @DisplayName("reading this machine never throws, whatever it is")
    void readingIsSafe() {
        MachineMemory m = MachineMemory.read();
        assertNotNull(m);
        if (m.known()) {
            assertTrue(m.totalBytes() > 0, "a known reading has a total");
            assertTrue(m.availableBytes() > 0, "a known reading has some free memory");
            assertTrue(m.availableBytes() <= m.totalBytes(), "free cannot exceed total");
        }
    }

    @Test
    @DisplayName("sizes are written the way a person would say them")
    void readableSizes() {
        assertEquals("0.5 GB", MachineMemory.human(500_000_000L));
        assertEquals("2.2 GB", MachineMemory.human(2_200_000_000L));
        // Past ten, the decimal is noise: nobody says "16.0 GB".
        assertEquals("16 GB", MachineMemory.human(16_000_000_000L));
    }

    // ==========================================
    // The verdict
    // ==========================================

    @Test
    @DisplayName("an unknown reading proceeds unchecked rather than refusing")
    void unknownVerdictProceeds() {
        ModelFit.Verdict v = new ModelFit.Verdict(false, true, "any:model", 0, MachineMemory.UNKNOWN, null);
        assertFalse(v.shouldRefuse(), "a check that could not be made must not become a refusal");
        assertTrue(v.explain().isEmpty(), "and it must not print an explanation for a decision it did not take");
    }

    @Test
    @DisplayName("a model that fits says nothing at all")
    void fittingIsSilent() {
        ModelFit.Verdict v =
                new ModelFit.Verdict(true, true, "qwen2.5:0.5b", 500_000_000L, MachineMemory.of(8 * GB, 4 * GB), null);
        assertFalse(v.shouldRefuse());
        assertTrue(v.explain().isEmpty(), "nothing is wrong, so there is nothing to say");
    }

    @Test
    @DisplayName("a model that does not fit names one that would")
    void refusalIsActionable() {
        // "Too big" is a complaint. "Too big, use this one" is an instruction, and the difference
        // is whether the user has to go and work out the answer themselves.
        ModelFit.Verdict v = new ModelFit.Verdict(
                true, false, "qwen2.5-coder:7b", 5_400_000_000L, MachineMemory.of(8 * GB, 2 * GB), "qwen2.5:0.5b");

        assertTrue(v.shouldRefuse());
        String said = String.join(" ", v.explain());
        assertTrue(said.contains("qwen2.5-coder:7b"), "it should name what was refused: " + said);
        assertTrue(said.contains("qwen2.5:0.5b"), "and what to use instead: " + said);
        assertTrue(said.contains("swap"), "and why, because the reason is not obvious: " + said);
    }

    @Test
    @DisplayName("when nothing installed fits, it says how to get something that does")
    void noAlternativeStillHelps() {
        ModelFit.Verdict v =
                new ModelFit.Verdict(true, false, "qwen3:14b", 11_000_000_000L, MachineMemory.of(8 * GB, 1 * GB), null);

        String said = String.join(" ", v.explain());
        assertTrue(said.contains("ollama pull"), "a dead end is not an answer: " + said);
    }
}
