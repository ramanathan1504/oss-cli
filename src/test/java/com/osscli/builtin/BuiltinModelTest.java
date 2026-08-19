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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tokenizer, and the guard that decides whether the model may load at all.
 *
 * <p>The weights themselves are not in the repository -- they are fetched and checksummed when a
 * release is built -- so nothing here runs the model. What is tested is everything that can be
 * wrong <em>without</em> the model saying so: a tokenizer that is subtly incorrect does not fail,
 * it produces text that reads like a model having a bad day, and a guard that is wrong takes an
 * 8 GB laptop down instead of printing a sentence.
 */
class BuiltinModelTest {

    @ParameterizedTest
    @DisplayName("every string survives a round trip through the tokenizer")
    @ValueSource(
            strings = {
                "Hello world",
                " leading space matters",
                "The diff adds 24 files in 2026.",
                "café · λ · 🌍",
                "  spaced\tand\nnewlines  ",
                "log4j-core/src/main/java/org/apache/logging/log4j/core/util/Throwables.java",
                "<|im_start|>user\nSummarise this.<|im_end|>\n"
            })
    void roundTripIsExact(String text) throws IOException {
        BpeTokenizer t = BpeTokenizer.shared();

        // Byte level means nothing is unrepresentable, so anything else is a bug in the tables or
        // the merges -- and it would show up as fluent nonsense rather than as an error.
        assertEquals(text, t.decode(t.encode(text), false), "round trip changed the text");
    }

    @Test
    @DisplayName("the chat markers are single tokens with the ids the model was trained on")
    void chatMarkersAreWhole() throws IOException {
        BpeTokenizer t = BpeTokenizer.shared();

        // Spelled out by BPE instead of matched whole, the model does not recognise its own turn
        // structure and answers as if it were completing a document.
        assertEquals(1, t.specialId("<|im_start|>"));
        assertEquals(2, t.specialId("<|im_end|>"));
        assertArrayEquals(new int[] {1}, t.encode("<|im_start|>"));
        assertEquals(49152, t.vocabSize());
    }

    @Test
    @DisplayName("a leading space is part of the token, not stripped from it")
    void spaceBelongsToTheWord() throws IOException {
        BpeTokenizer t = BpeTokenizer.shared();

        // The distinction the GPT-2 pattern exists to preserve. A tokenizer that normalises it
        // away gives the model input it has never seen, in a way that still decodes cleanly.
        assertEquals(1, t.encode(" the").length);
        assertTrue(t.encode(" the")[0] != t.encode("the")[0], "\" the\" and \"the\" must not be the same token");
    }

    @Test
    @DisplayName("digits are separate tokens, as the model's own pre-tokenizer declares")
    void digitsSplit() throws IOException {
        BpeTokenizer t = BpeTokenizer.shared();

        // individual_digits=true in tokenizer.json. Getting this wrong is invisible in a round trip
        // and changes every number the model reads.
        assertEquals(4, t.encode("2026").length);
    }

    @Test
    @DisplayName("the model is looked for beside the jar, and its absence is a sentence not a crash")
    void absenceIsReported() {
        // In a checkout there are no weights unless a release build put them there, so this test
        // asserts the shape of the answer rather than which branch it takes.
        String refusal = BuiltinModel.refusal().orElse("");
        if (BuiltinModel.isPresent()) {
            assertTrue(
                    refusal.isEmpty() || refusal.contains("memory"),
                    "present weights may only be refused for memory: " + refusal);
        } else {
            // Nothing is bundled, so the useful refusal names the way to supply a model rather than
            // the file that is absent: "model.onnx is missing" tells you nothing you can act on.
            assertTrue(
                    refusal.contains("OSS_BUILTIN_MODEL") && refusal.contains("oss llm"),
                    "the refusal must name both ways forward: " + refusal);
        }
    }

    @Test
    @DisplayName("what the guard needs is above what the model actually costs")
    void theFloorIsAboveTheMeasuredCost() {
        // 131 MB of weights, plus ONNX Runtime's arenas, plus the cache; measured at just under
        // 250 MB resident for a short answer. The floor is deliberately above that, because the
        // failure it prevents is not an error -- it is a laptop that stops responding.
        assertTrue(BuiltinModel.NEEDS_BYTES >= 300L * 1024 * 1024, "the floor is below what was measured");
    }

    @Test
    @DisplayName("no model is bundled, and the reason is written down where the next person will look")
    void nothingIsBundledAndTheMeasurementIsRecorded() throws IOException {
        // The obvious next move for anyone reading this class is to bundle a small model and wire
        // it into review. That was done, measured against five real pull requests, and undone: the
        // candidates invented facts when asked to summarise and scored 1-2 of 5 when asked to pick
        // a label, where three lines of keyword matching score 5 of 5 and cannot invent anything.
        //
        // The measurement lives in the class javadoc so it is read before the experiment is
        // repeated, and this pins both halves: the archive stays lean, and the reason stays with it.
        String workflow = Files.readString(Path.of(".github/workflows/dist.yml"));
        assertTrue(
                !workflow.contains(".onnx"),
                "a model is being bundled again — if it now passes the table in BuiltinModel, update that table too");

        String source = Files.readString(Path.of("src/main/java/com/osscli/builtin/BuiltinModel.java"));
        for (String evidence : List.of("1 of 5", "2 of 5", "invented", "OSS_BUILTIN_MODEL")) {
            assertTrue(source.contains(evidence), "the reason for not bundling must stay stated: missing " + evidence);
        }
    }

    @Test
    @DisplayName("a model supplied by hand is found through the environment")
    void anEnvironmentModelIsHonoured() {
        // The capability that does ship: point it at an ONNX decoder and it runs in this process,
        // no daemon, no key, no network. Asserted through the refusal text because setting an
        // environment variable inside a JVM is not possible without reaching around the platform.
        String refusal = BuiltinModel.refusal().orElse("");
        if (!BuiltinModel.isPresent()) {
            assertTrue(refusal.contains("OSS_BUILTIN_MODEL"), "the refusal must say how to supply one: " + refusal);
        }
    }
}
