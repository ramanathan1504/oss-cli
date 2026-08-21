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
package com.osscli.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The embedding runtime is pinned, and a bump has to be a decision rather than a Tuesday.
 *
 * <p>onnxruntime 1.29 runs the same model and does not produce the same vectors. Measured by
 * embedding 30 real passages from a live store with both runtimes: cosine between the two versions
 * of the <em>same text</em> is 0.9978 median, 0.9930 worst, 25 of 30 below 0.999. A quantised
 * model's kernels changed; this is not rounding.
 *
 * <p>Nothing in the store would catch the mix. Every vector is written with the model that produced
 * it and every read filters on that — but the model name is identical across runtimes, and both
 * emit 384 dimensions, so the shape check cannot see it either. Vectors written by one and queried
 * by the other compare as though they share a space they do not quite share, which is the
 * "plausible nonsense instead of an error" this repository already has a rule about.
 *
 * <p>So the version is asserted here. Dependabot will propose 1.29 again next week, CI will fail,
 * and somebody will have to answer the real question — re-embed 51,540 passages, or stay put —
 * instead of merging a green tick.
 */
class EmbedderRuntimePinTest {

    /** Raise this only in the same change that re-embeds the corpus. */
    private static final String PINNED = "1.22.0";

    @Test
    @DisplayName("onnxruntime stays pinned until somebody re-embeds the corpus")
    void runtimeIsPinned() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        Matcher m = Pattern.compile("<artifactId>onnxruntime</artifactId>.*?<version>([^<]+)</version>", Pattern.DOTALL)
                .matcher(pom);
        assertTrue(m.find(), "onnxruntime is not in pom.xml at all");
        assertEquals(
                PINNED,
                m.group(1).strip(),
                "onnxruntime moved. Same model, different vectors: 0.9978 median cosine against the "
                        + "pinned runtime, 0.9930 worst, measured on 30 real passages. Every stored vector "
                        + "was written by " + PINNED + " and nothing records the runtime, so the mix is "
                        + "invisible. Re-embed everything in this change, or put it back.");
    }

    @Test
    @DisplayName("and the reason is written where the version is, not only here")
    void thePomSaysWhy() throws IOException {
        // A pin with no reason beside it gets bumped by the next person who sees an old version
        // number. The comment is the part that survives being read in a hurry.
        String pom = Files.readString(Path.of("pom.xml"));
        int at = pom.indexOf("<artifactId>onnxruntime</artifactId>");
        String around = pom.substring(at, Math.min(pom.length(), at + 2000));

        assertTrue(around.contains("PINNED"), "the pin is not explained next to the version");
        assertTrue(around.contains("re-embed"), "the explanation must name what a bump costs");
    }
}
