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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a typed line works with a terminal and without one.
 *
 * <p>The interactive half cannot be asserted here: JLine writes its redraw straight to the
 * terminal, and a pseudo-terminal driven from a test sees none of it -- not the candidate list, not
 * even the echo of what was typed. It was checked by hand instead, through a pty, and what that
 * found is written down below rather than left to be rediscovered.
 */
class PromptTest {

    private final InputStream realIn = System.in;

    @AfterEach
    void putStdinBack() {
        System.setIn(realIn);
    }

    @Test
    @DisplayName("with no terminal it still reads a line")
    void fallbackReads() {
        System.setIn(new ByteArrayInputStream("why does rollover skip a file\n".getBytes(StandardCharsets.UTF_8)));
        try (Prompt p = Prompt.open(List.of("review", "search"))) {
            assertEquals("why does rollover skip a file", p.line("  › "));
        }
    }

    @Test
    @DisplayName("and returns null at the end of input rather than throwing")
    void endOfInputIsNotAnError() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        try (Prompt p = Prompt.open(List.of())) {
            assertNull(p.line("  › "), "ctrl-d is how somebody leaves a prompt, not a failure to report");
        }
    }

    @Test
    @DisplayName("the option that lists completions is the one that lists completions")
    void ambiguousTabOffersTheChoices() throws IOException {
        // The trap, and it cost a round of testing to find. `se` with search, serve and setup
        // behind it left the line as typed and printed nothing, which is the same silence as a
        // prompt that has stopped responding: the reader tabs again, gets nothing again, and
        // concludes completion is broken when it is working and declining to guess between three.
        //
        // AUTO_LIST is the fix. LIST_AMBIGUOUS reads as though it were the fix and does the
        // opposite -- it holds the list back until a SECOND tab -- so setting both cancelled the
        // first out and restored exactly the behaviour being fixed. Asserted at the source, because
        // the two names are close enough that the next person will reach for the wrong one too.
        String source = Files.readString(Path.of("src/main/java/com/osscli/ui/Prompt.java"));
        assertTrue(source.contains("Option.AUTO_LIST"), "an ambiguous tab must show the choices");
        assertFalse(
                source.contains("setOpt(LineReader.Option.LIST_AMBIGUOUS)"),
                "LIST_AMBIGUOUS delays the list to a second tab and undoes AUTO_LIST");
    }
}
