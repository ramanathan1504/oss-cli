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
package com.osscli.knowledge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the summariser cannot take the filing down with it.
 *
 * <p>It did. A template gained a placeholder and its argument list did not, so the first session
 * threw {@code MissingFormatArgumentException} and the whole run stopped -- 88 sessions unfiled
 * because of a paragraph none of them needed. The compiler cannot check a format string, so these
 * are the only place it gets checked.
 */
class EnrichmentTest {

    @Test
    @DisplayName("the prompt has an argument for every placeholder")
    void thePromptFormats() {
        // The bug exactly: this threw, and nothing above it caught it.
        String prompt = assertDoesNotThrow(() -> Enrichment.promptFor("a title", "log4j", "> some turns"));

        assertTrue(prompt.startsWith(Enrichment.PREAMBLE), prompt.substring(0, 60));
        assertTrue(prompt.contains("log4j"), "the subject is named");
        assertTrue(prompt.contains("a title"), "the session is named");
        assertTrue(prompt.contains("> some turns"), "the transcript is included");
        assertFalse(prompt.contains("%s"), "an unfilled placeholder means an argument is missing");
    }

    @Test
    @DisplayName("a summariser that throws costs the paragraph and nothing else")
    void failureIsContained() {
        // No model is reachable in a test run, which is itself the point: the answer is a note
        // without a summary, never an exception on the way to filing one.
        Enrichment.Summary summary = assertDoesNotThrow(() -> Enrichment.summarise("t", "log4j", "> turns", false));

        assertFalse(summary.present());
    }

    @Test
    @DisplayName("a model's preamble is stripped, and a one-word answer is not a summary")
    void answersAreCleaned() {
        assertTrue(Enrichment.clean("Here is a summary:\nThe policy fired twice, so the file was skipped.")
                .startsWith("The policy fired twice"));
        // A model failing to answer looks like a very short answer, and a note saying "Sure." is
        // worse than a note saying nothing.
        assertTrue(Enrichment.clean("Sure.") == null);
        assertTrue(Enrichment.clean("") == null);
        assertTrue(Enrichment.clean(null) == null);
    }

    @Test
    @DisplayName("no tier is named in the code, so the archive never depends on one vendor")
    void noVendorIsHardCoded() throws java.io.IOException {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/osscli/knowledge/Enrichment.java"));

        assertFalse(
                source.contains("CliClient.CLAUDE"),
                "the summariser must take whichever tool this install has, not one named here");
    }
}
