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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import com.osscli.model.Label;
import com.osscli.serve.Askable;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That these stay linear when the corpus is not small.
 *
 * <p>Ratios rather than wall-clock. A machine under load makes any absolute millisecond budget
 * flaky, and the failure worth catching is not "slow today" but "quadratic" — the shape that is
 * unnoticeable on ten items and unusable on ten thousand. A real archive here is 15,937 issues and
 * 34,828 passages, so ten thousand is the size that matters rather than an unlikely one.
 */
class HarvestScaleTest {

    private static Issue at(int n) {
        return new Issue(
                n,
                "a title for item " + n,
                "a body with enough words in it to be worth measuring, repeated: " + "x".repeat(200),
                "open",
                0,
                null,
                null,
                null,
                List.of(new Label("bug"), new Label("appenders")),
                null,
                null,
                "https://github.com/owner/name/pull/" + n);
    }

    /** How long a run of {@code n} notes takes, after a warm-up the JIT has seen. */
    private static long nanosFor(int n) {
        for (int i = 0; i < 1_000; i++) {
            BuiltinMemory.harvestNote(at(i), java.util.List.of());
        }
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            BuiltinMemory.harvestNote(at(i), java.util.List.of());
            BuiltinMemory.harvestName(at(i));
        }
        return System.nanoTime() - start;
    }

    @Test
    @DisplayName("ten times the items costs about ten times the work, not a hundred")
    void harvestScalesLinearly() {
        long small = Math.max(nanosFor(1_000), 1);
        long large = nanosFor(10_000);

        // Ten times the input. Linear would be ~10x; quadratic would be ~100x. Thirty allows for a
        // loaded machine and still fails loudly on the shape that matters.
        double ratio = (double) large / small;
        assertTrue(ratio < 30, "ten times the items took " + Math.round(ratio) + "x the time");
    }

    @Test
    @DisplayName("looking a question up does not walk the table")
    void questionLookupIsConstant() {
        long start = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            Askable.byKey("doctor");
            Askable.byKey("hub");
            Askable.byKey("not a question");
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        // 600k lookups. A map answers instantly; a scan of the list per call would not.
        assertTrue(ms < 2_000, "600,000 lookups took " + ms + "ms");
    }
}
