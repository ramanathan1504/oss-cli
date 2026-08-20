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
package com.osscli.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That a description of the change is not printed as a concern about it. */
class FindingsTest {

    private static final List<String> CHANGED_4249 = List.of(
            "log4j-core/src/main/java/org/apache/logging/log4j/core/util/Throwables.java",
            "log4j-jpa/src/main/java/org/apache/logging/log4j/core/appender/db/jpa/converter/ThrowableAttributeConverter.java",
            "src/changelog/.2.x.x/4249_fix-circular-exception.xml");

    @Test
    @DisplayName("what the local model actually answered on 4249 is not reported as findings")
    void theRealNonFindingsAreRejected() {
        // Verbatim from qwen2.5:0.5b, run against this pull request. Three true sentences about the
        // subject of the change, printed under "Concerns" beside a claimed confidence of 80%.
        Findings.Located located = Findings.locate(
                List.of("Circular exception handling", "Causal chain tracking", "Conversion to database columns"),
                CHANGED_4249);

        assertEquals(List.of(), located.concerns());
        assertEquals(3, located.unlocated());
    }

    @Test
    @DisplayName("a concern that names a changed file is kept")
    void locatedConcernsSurvive() {
        Findings.Located located = Findings.locate(
                List.of(
                        "ThrowableAttributeConverter — the loop guard and the contains() check both detect the cycle",
                        "Nothing in particular"),
                CHANGED_4249);

        assertEquals(1, located.concerns().size());
        assertTrue(located.concerns().get(0).startsWith("ThrowableAttributeConverter"));
        assertEquals(1, located.unlocated());
    }

    @Test
    @DisplayName("the file name counts whether or not the extension is written")
    void classNameMatchesToo() {
        assertEquals(
                1,
                Findings.locate(List.of("Throwables.java leaks"), CHANGED_4249)
                        .concerns()
                        .size());
        assertEquals(
                1,
                Findings.locate(List.of("Throwables leaks"), CHANGED_4249)
                        .concerns()
                        .size());
    }

    @Test
    @DisplayName("with no file list nothing is filtered, because nothing can be located")
    void unknownFilesKeepEverything() {
        // Suppressing every concern here would turn "we could not check" into "there are none".
        Findings.Located located = Findings.locate(List.of("something real", "something else"), List.of());

        assertEquals(2, located.concerns().size());
        assertEquals(0, located.unlocated());
    }
}
