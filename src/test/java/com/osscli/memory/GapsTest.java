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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the notes do not cover, written down instead of printed.
 *
 * <p>{@code coverage} prints a scorecard and it scrolls away. The half worth keeping is the short
 * list — the areas scoring nothing — and it is only useful if it survives the terminal: filed as a
 * note it is retrievable, it joins the corpus, and next month's run can be compared against it.
 */
class GapsTest {

    private static final List<Coverage.Area> AREAS = List.of(
            new Coverage.Area("Appenders", 9, 140, "log4j-appenders.md"),
            new Coverage.Area("Filters", 2, 11, "one-filter.md"),
            new Coverage.Area("Lookups", 0, 0, ""),
            new Coverage.Area("Garbage-free logging", 0, 0, ""));

    @Test
    @DisplayName("the note leads with what is missing, not with what is done")
    void missingComesFirst() {
        String note = BuiltinMemory.gapNote("log4j", AREAS);

        int nothing = note.indexOf("## Nothing at all");
        int covered = note.indexOf("## Covered");
        assertTrue(nothing > 0 && covered > nothing, "the point of the report is the gap, so it goes first");

        assertTrue(note.contains("## Nothing at all (2)"), note);
        assertTrue(note.contains("- Lookups"));
        assertTrue(note.contains("- Garbage-free logging"));
    }

    @Test
    @DisplayName("thin and covered are told apart, because one afternoon is not experience")
    void thinIsNotCovered() {
        String note = BuiltinMemory.gapNote("log4j", AREAS);

        assertTrue(note.contains("## Thin — one or two notes (1)"), note);
        assertTrue(note.contains("Filters — 2 note(s)"), note);
        assertTrue(note.contains("## Covered (1)"), note);
        assertTrue(note.contains("Appenders — 9 notes, 140 mentions"), note);
    }

    @Test
    @DisplayName("the report claims only what a count can support")
    void noJudgementAboutTheReader() {
        String note = BuiltinMemory.gapNote("log4j", AREAS);

        // An area with no notes is a thing not written down yet. Anything stronger than that is the
        // measurement pretending to be an assessment.
        assertTrue(note.contains("not written down yet"), note);
        for (String overreach : List.of("you should", "weak", "poor", "failing", "beginner")) {
            assertFalse(note.toLowerCase(java.util.Locale.ROOT).contains(overreach), overreach);
        }
    }

    @Test
    @DisplayName("a yardstick every area passes is reported as that, not as an empty section")
    void nothingMissingIsSaidOutLoud() {
        String note = BuiltinMemory.gapNote("log4j", List.of(new Coverage.Area("Appenders", 9, 140, "a.md")));

        assertTrue(note.contains("Every declared area has at least one note."), note);
    }

    @Test
    @DisplayName("an empty yardstick does not produce a note claiming full coverage")
    void emptyYardstickIsHonest() {
        String note = BuiltinMemory.gapNote("log4j", List.of());

        // Zero of zero is not completeness; it is nothing to measure against. Reporting "0 missing"
        // for a yardstick nobody wrote would be the cheerful version of the silent-cap bug.
        assertTrue(note.contains("## Nothing at all (0)"), note);
        assertTrue(note.contains("## Covered (0)"), note);
        assertTrue(note.contains("None yet."), note);
    }

    @Test
    @DisplayName("gaps is offered by the built-in, with nothing attached")
    void gapsIsBuiltIn() {
        assertTrue(BuiltinMemory.VERBS.contains("gaps"));
        assertTrue(BuiltinMemory.supports("gaps"));
    }
}
