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
package com.osscli.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the one way of saying something behaves in both of its two forms.
 *
 * <p>The half worth guarding is the plain one. Colour that fails to appear is a dull afternoon;
 * colour that appears in a redirected file is a report nobody can read, and a grep that silently
 * stops matching because the word it wants has an escape sequence welded to its front.
 */
class OutTest {

    private static final String ESC = "\u001b";

    private final PrintStream realOut = System.out;

    @AfterEach
    void putEverythingBack() {
        System.setOut(realOut);
        Out.forceColour(null);
    }

    private String printed(boolean colour, Runnable body) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        Out.forceColour(colour);
        try {
            body.run();
        } finally {
            System.setOut(realOut);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("nothing coloured reaches a redirect")
    void plainIsActuallyPlain() {
        String text = printed(false, () -> {
            Out.title("PR #1  owner/name");
            Out.section("what it is");
            Out.kv("author", "somebody");
            Out.ok("it worked");
            Out.warn("it did not");
            Out.hint("oss sync", "fetch what changed");
        });
        assertFalse(text.contains(""), "an escape survived into plain output: " + text);
        assertTrue(text.contains("PR #1  owner/name"));
        assertTrue(text.contains("author"));
    }

    @Test
    @DisplayName("and every one of those puts its colour back afterwards")
    void colourAlwaysCloses() {
        String text = printed(true, () -> {
            Out.title("t");
            Out.section("s");
            Out.kv("k", "v");
            Out.ok("o");
            Out.warn("w");
            Out.hint("oss doctor", "why");
        });
        // Every sequence starts with ESC; half of them should be the reset, because a painted
        // string is code + text + off. An odd count means a colour was still on when the command
        // ended, which is how a shell prompt turns brass and stays that way.
        int sequences = text.split(ESC, -1).length - 1;
        int resets = text.split(java.util.regex.Pattern.quote(ESC + "[0m"), -1).length - 1;
        assertTrue(sequences > 0, "colour was forced on, so there should be some");
        assertEquals(
                resets,
                sequences - resets,
                "every colour must be turned off again, or it bleeds into whatever prints next");
    }

    @Test
    @DisplayName("a column of facts lines up, whether or not it is coloured")
    void keysAlign() {
        for (boolean colour : new boolean[] {false, true}) {
            String text = printed(colour, () -> {
                Out.kv("author", "Xvalue");
                Out.kv("comments", "Yvalue");
            });
            List<String> lines = List.of(text.split("\n"));
            int first = valueColumn(lines.get(0), "Xvalue");
            int second = valueColumn(lines.get(1), "Yvalue");
            assertEquals(first, second, "values must start in the same column (colour=" + colour + ")");
        }
    }

    /** Where the value starts, ignoring escapes -- which is what the eye sees. */
    private int valueColumn(String line, String value) {
        String visible = line.replaceAll("\\[[0-9;]*m", "");
        return visible.indexOf(value);
    }

    @Test
    @DisplayName("NO_COLOR and a pipe are both honoured without asking the caller")
    void theDecisionIsNotTheCallers() {
        // Not settable from a test, so this asserts the shape rather than the value: whatever
        // colour() decides, every helper must agree with it. A helper that painted unconditionally
        // would be the one that puts escapes in a redirect.
        Out.forceColour(false);
        assertFalse(Out.cmd("oss sync").contains(""));
        assertFalse(Out.good("yes").contains(""));
        assertFalse(Out.bad("no").contains(""));
        assertFalse(Out.faint("aside").contains(""));
        Out.forceColour(true);
        assertTrue(Out.cmd("oss sync").contains(""));
        assertTrue(Out.good("yes").contains(""));
    }
}
