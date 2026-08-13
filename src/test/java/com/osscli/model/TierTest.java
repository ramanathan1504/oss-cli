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
package com.osscli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which notes are yours.
 *
 * <p>The consequential direction is demotion. Calling your own work "collected" discounts it in
 * every future answer and is invisible when it happens, so the rule has to be conservative:
 * KNOWLEDGE unless the note itself says, twice over, that you had no part in it.
 */
class TierTest {

    private static String note(String role, String source) {
        return "---\ngithub: owner/name#4129\nmy_role: " + role + "\nsource: " + source + "\n---\n\n# a discussion\n";
    }

    @Test
    @DisplayName("no part in it, and found by scanning: collected")
    void uninvolvedAndScanned() {
        assertEquals(Tier.REFERENCE, Tier.of(note("none", "repo-scan")));
    }

    @Test
    @DisplayName("found by scanning, but you wrote in it: still yours")
    void scannedButAuthored() {
        assertEquals(Tier.KNOWLEDGE, Tier.of(note("author, reviewer", "repo-scan")));
        assertEquals(Tier.KNOWLEDGE, Tier.of(note("commenter", "repo-scan")));
        assertEquals(Tier.KNOWLEDGE, Tier.of(note("inline-reviewer", "repo-scan")));
    }

    @Test
    @DisplayName("found because you are involved: yours, whatever the role says")
    void involvedSource() {
        assertEquals(Tier.KNOWLEDGE, Tier.of(note("none", "involved")));
    }

    @Test
    @DisplayName("your own notes have no frontmatter and are yours by definition")
    void plainNote() {
        assertEquals(Tier.KNOWLEDGE, Tier.of("# Rollover leaves a zero-length file\n\nwhat I worked out\n"));
    }

    @Test
    @DisplayName("a recorded resolution is yours")
    void resolutionNote() {
        assertEquals(Tier.KNOWLEDGE, Tier.of("# owner/name — Issue #12\n\n**Source:** claude\n\nthe answer\n"));
    }

    @Test
    @DisplayName("nothing at all is not a reason to demote")
    void nullAndBlank() {
        assertEquals(Tier.KNOWLEDGE, Tier.of((String) null));
        assertEquals(Tier.KNOWLEDGE, Tier.of(""));
        assertEquals(Tier.KNOWLEDGE, Tier.of("   \n \n"));
    }

    @Test
    @DisplayName("only one half of the rule is not enough to demote")
    void oneHalfOnly() {
        assertEquals(Tier.KNOWLEDGE, Tier.of("---\nmy_role: none\n---\n"));
        assertEquals(Tier.KNOWLEDGE, Tier.of("---\nsource: repo-scan\n---\n"));
    }

    @Test
    @DisplayName("a pasted log quoting the frontmatter is not a claim about the note")
    void deepInBodyIgnored() {
        StringBuilder sb = new StringBuilder("# my own note\n\n");
        sb.append("filler. ".repeat(400));
        sb.append("\nmy_role: none\nsource: repo-scan\n");
        assertEquals(Tier.KNOWLEDGE, Tier.of(sb.toString()));
    }

    @Test
    @DisplayName("the stored value round-trips, and anything unrecognised falls back")
    void fromStoredValue() {
        assertEquals(Tier.REFERENCE, Tier.of("REFERENCE", Tier.KNOWLEDGE));
        assertEquals(Tier.KNOWLEDGE, Tier.of("KNOWLEDGE", Tier.REFERENCE));
        assertEquals(Tier.REFERENCE, Tier.of("reference", Tier.KNOWLEDGE));
        assertEquals(Tier.KNOWLEDGE, Tier.of(null, Tier.KNOWLEDGE));
        assertEquals(Tier.KNOWLEDGE, Tier.of("nonsense", Tier.KNOWLEDGE));
    }
}
