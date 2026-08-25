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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Typing a number means "show me that one".
 *
 * <p>It used to be embedded and compared by cosine similarity, which asks the model what a bare
 * integer is <i>about</i>. Searching "4226" returned five unrelated issues between 0.13 and 0.26
 * similarity, none of them 4226, while 4226 sat in the same store with a title on it — a search
 * that reported success and answered a question nobody asked.
 */
class SearchByNumberTest {

    private static Issue issue(long number, String title) {
        return new Issue(number, title, "", "open", 0, null, null, null, null, null, null, null);
    }

    private static Map<String, Issue> store() {
        Map<String, Issue> m = new LinkedHashMap<>();
        m.put("r_3876", issue(3876, "Document jvmrunargs lookup"));
        m.put("r_4226", issue(4226, "Fix multi-second startup delay in CronTriggeringPolicy"));
        return m;
    }

    private static SearchCommand asking(String query) {
        SearchCommand c = new SearchCommand();
        c.query = query;
        return c;
    }

    @Test
    @DisplayName("a bare number finds exactly that issue")
    void numberIsExact() {
        var found = asking("4226").byNumber(store());
        assertTrue(found.isPresent(), "4226 is in the store");
        assertEquals(4226L, found.get().number());
    }

    @Test
    @DisplayName("surrounding space is not a different question")
    void spaceIsTrimmed() {
        assertTrue(asking("  4226 ").byNumber(store()).isPresent());
    }

    @Test
    @DisplayName("a number this store does not have falls through to the model")
    void unknownNumberIsStillASearch() {
        assertTrue(asking("999999").byNumber(store()).isEmpty(), "no exact answer, so search properly");
    }

    @Test
    @DisplayName("a query that merely contains a number is a sentence, and stays one")
    void wordsWithANumberAreASearch() {
        assertTrue(asking("4226 startup delay").byNumber(store()).isEmpty());
        assertTrue(asking("log4j2").byNumber(store()).isEmpty());
    }

    @Test
    @DisplayName("something longer than a number is refused rather than guessed at")
    void absurdlyLongDigitsDoNotThrow() {
        assertTrue(asking("99999999999999999999999999").byNumber(store()).isEmpty());
    }
}
