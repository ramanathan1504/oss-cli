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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A voice measured from the user's own writing, or no voice at all.
 *
 * <p>The machine this was written for holds 1,874 notes and almost none of them are the user's:
 * 1,024 harvested GitHub threads and 840 generated drafts. Measuring "style" over that corpus
 * returns the tool's own voice, handed back to its owner as theirs — so the properties worth
 * pinning down are what it refuses to say, as much as what it says.
 */
class VoiceProfileTest {

    private static List<String> repeat(String text, int times) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            out.add(text + " Sample " + i + " of the same hand writing the same way.");
        }
        return out;
    }

    @Test
    @DisplayName("nothing written means no profile, and no pretending otherwise")
    void emptyIsEmpty() {
        VoiceProfile none = VoiceProfile.of(List.of());
        assertEquals(0, none.samples());
        assertFalse(none.confident());
        assertEquals("", none.forPrompt(), "a model must not be told about a voice nobody has shown");
        assertTrue(none.markdown().contains("Nothing of yours was found"), none.markdown());
    }

    @Test
    @DisplayName("blank and null entries are not samples")
    void blanksDoNotCount() {
        List<String> written = new ArrayList<>();
        written.add(null);
        written.add("   ");
        written.add("");
        assertEquals(0, VoiceProfile.of(written).samples());
    }

    @Test
    @DisplayName("below the threshold it measures, files, and still tells the model nothing")
    void provisionalStaysOutOfThePrompt() {
        VoiceProfile few = VoiceProfile.of(repeat("A short sentence.", VoiceProfile.ENOUGH - 1));

        assertEquals(VoiceProfile.ENOUGH - 1, few.samples());
        assertFalse(few.confident());
        assertEquals("", few.forPrompt(), "hedged style guidance spends budget to imitate noise");
        assertTrue(few.markdown().contains("Provisional"), "and the file says which it is");
    }

    @Test
    @DisplayName("at the threshold the measurements reach generation")
    void enoughSamplesReachThePrompt() {
        VoiceProfile many = VoiceProfile.of(repeat("A short sentence.", VoiceProfile.ENOUGH));

        assertTrue(many.confident());
        String block = many.forPrompt();
        assertTrue(block.contains("words"), block);
        assertTrue(block.contains(String.valueOf(VoiceProfile.ENOUGH)), "and says how much evidence it rests on");
    }

    @Test
    @DisplayName("a paragraph is never less than one sentence")
    void headingsAndBulletsAreNotZeroSentenceParagraphs() {
        // Real output from this machine read "0.9 sentences per paragraph" -- impossible, and
        // printed as confidently as the numbers that were not. Markdown headings and bullets carry
        // no full stop; they are still one unit of writing each.
        String markdown = "# A heading\n\n- one bullet\n\n- another bullet\n\n## Another heading";
        String rendered = VoiceProfile.of(List.of(markdown)).markdown();

        assertFalse(rendered.contains("| 0.9 |"), rendered);
        for (String line : rendered.split("\n")) {
            if (line.startsWith("| sentences per paragraph")) {
                double value = Double.parseDouble(line.replaceAll("[^0-9.]", ""));
                assertTrue(value >= 1.0, "measured " + value + " sentences per paragraph");
            }
        }
    }

    @Test
    @DisplayName("spelling is reported only where the writer has actually shown one")
    void spellingNeedsEvidence() {
        assertEquals(
                "British",
                VoiceProfile.of(List.of("The behaviour and the colour of it.")).spelling());
        assertEquals(
                "American",
                VoiceProfile.of(List.of("The behavior and the color of it.")).spelling());
        assertEquals(
                null,
                VoiceProfile.of(List.of("The behaviour and the behavior, equally."))
                        .spelling(),
                "a tie is not a preference");
        assertEquals(null, VoiceProfile.of(List.of("Nothing decisive here.")).spelling());
    }

    @Test
    @DisplayName("reading this machine is never itself an error")
    void readingTheMachineDoesNotThrow() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(VoiceProfile::written);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(VoiceProfile::ofThisMachine);
    }
}
