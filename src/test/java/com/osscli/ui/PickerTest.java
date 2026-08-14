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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the picker behaves where there is no keyboard to read.
 *
 * <p>Tests run with no terminal attached, which is exactly the condition the fallback exists for, so
 * these cover the path most likely to be reached in anger: cron, CI, a piped invocation, Windows.
 * The key decoding is tested directly, since that is the part with the escape-sequence arithmetic in
 * it and the part a terminal test could not reach anyway.
 */
class PickerTest {

    @Test
    @DisplayName("an empty list chooses nothing rather than throwing")
    void emptyListIsNotAnError() {
        assertNull(Picker.choose("nothing here", List.of(), Object::toString, o -> List.of()));
        assertNull(Picker.choose("nothing here", null, Object::toString, o -> List.of()));
    }

    @Test
    @DisplayName("a list of one is chosen without asking")
    void singletonNeedsNoPrompt() {
        assertEquals("only", Picker.choose("pick", List.of("only"), s -> s, s -> List.of()));
    }

    @Test
    @DisplayName("without a terminal, a number picks the entry")
    void numberedFallbackSelects() {
        String chosen = withStdin("2\n", () -> Picker.choose("pick", List.of("a", "b", "c"), s -> s, s -> List.of()));
        assertEquals("b", chosen);
    }

    @Test
    @DisplayName("an empty answer cancels")
    void numberedFallbackCancels() {
        assertNull(withStdin("\n", () -> Picker.choose("pick", List.of("a", "b"), s -> s, s -> List.of())));
    }

    @Test
    @DisplayName("a number outside the list cancels rather than picking the nearest")
    void numberedFallbackRejectsOutOfRange() {
        assertNull(withStdin("9\n", () -> Picker.choose("pick", List.of("a", "b"), s -> s, s -> List.of())));
        assertNull(withStdin("0\n", () -> Picker.choose("pick", List.of("a", "b"), s -> s, s -> List.of())));
    }

    @Test
    @DisplayName("something that is not a number cancels rather than being guessed at")
    void numberedFallbackRejectsRubbish() {
        assertNull(withStdin("banana\n", () -> Picker.choose("pick", List.of("a", "b"), s -> s, s -> List.of())));
    }

    @Test
    @DisplayName("closed input cancels, so a piped invocation cannot hang")
    void closedStdinCancels() {
        assertNull(withStdin("", () -> Picker.choose("pick", List.of("a", "b"), s -> s, s -> List.of())));
    }

    @Test
    @DisplayName("arrow keys decode from their three-byte escape sequences")
    void arrowKeysDecode() throws Exception {
        assertEquals(read("\u001b[A"), read("k"), "up arrow and k are the same key");
        assertEquals(read("\u001b[B"), read("j"), "down arrow and j are the same key");
    }

    @Test
    @DisplayName("enter, q and ctrl-c are each distinct from the movement keys")
    void controlKeysDecode() throws Exception {
        int enter = read("\r");
        int newline = read("\n");
        int quit = read("q");
        int ctrlC = read("\u0003");

        assertEquals(enter, newline, "both line endings confirm");
        assertEquals(quit, ctrlC, "ctrl-c cancels, since raw mode swallowed the signal");
        org.junit.jupiter.api.Assertions.assertNotEquals(enter, quit);
        org.junit.jupiter.api.Assertions.assertNotEquals(enter, read("\u001b[A"));
    }

    @Test
    @DisplayName("a bare escape cancels instead of waiting for a sequence that is not coming")
    void bareEscapeCancels() throws Exception {
        assertEquals(read("q"), read("\u001b"), "escape on its own is a cancel, not a hang");
    }

    @Test
    @DisplayName("end of input cancels")
    void eofCancels() throws Exception {
        // Distinct from every movement key; the caller treats it the same as quit.
        int eof = read("");
        org.junit.jupiter.api.Assertions.assertNotEquals(eof, read("\u001b[A"));
        org.junit.jupiter.api.Assertions.assertNotEquals(eof, read("\r"));
    }

    private static int read(String bytes) throws Exception {
        InputStream in = new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8));
        return Picker.read(in);
    }

    /** Runs something with stdin replaced, and always puts the real one back. */
    private static <T> T withStdin(String input, java.util.function.Supplier<T> body) {
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            return body.get();
        } finally {
            System.setIn(original);
        }
    }
}
