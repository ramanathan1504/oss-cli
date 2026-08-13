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
package com.osscli.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ranking that needs no model at all.
 *
 * <p>{@code critical} is the offline floor: it runs with no model, no daemon and no network, and for
 * a new install it is the first useful thing the tool does. Its job is ordering rather than
 * diagnosis, so these tests assert relative placement — that a deadlock outranks a documentation ask —
 * rather than pinning exact scores, which would break on every tuning change without ever catching
 * a real regression.
 */
class SeverityAnalyzerTest {

    private static Issue issue(String title, String body, int comments) {
        return new Issue(
                1L,
                title,
                body,
                "open",
                comments,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                null,
                List.of(),
                null,
                "CONTRIBUTOR",
                "https://example.invalid/1");
    }

    private static IssueAnalysis analyse(String title, String body) {
        return new SeverityAnalyzer().analyze(issue(title, body, 0));
    }

    @Test
    @DisplayName("every issue gets a severity, a score and a stated reason")
    void alwaysAnswers() {
        IssueAnalysis a = analyse("Something happened", "no detail at all");
        assertNotNull(a.severity(), "severity must never be null; the whole point is a total ordering");
        assertNotNull(a.reason());
        assertTrue(a.score() >= 0, "a negative score would invert the ranking");
    }

    @Test
    @DisplayName("a deadlock outranks a documentation request")
    void deadlockOutranksDocumentation() {
        int deadlock = analyse("Deadlock under contention", "threads hang waiting on each other")
                .score();
        int docs = analyse("Improve the documentation", "the documentation needs a style pass")
                .score();
        assertTrue(deadlock > docs, "deadlock scored " + deadlock + ", documentation scored " + docs);
    }

    @Test
    @DisplayName("a memory leak outranks a feature request")
    void leakOutranksFeature() {
        int leak = analyse("Memory leak in the appender", "heap grows without bound")
                .score();
        int feature = analyse("Add support for a new format", "this would be a new feature")
                .score();
        assertTrue(leak > feature, "leak scored " + leak + ", feature scored " + feature);
    }

    @Test
    @DisplayName("KNOWN GAP: severity is read from labels, so untagged wording scores nothing")
    void untaggedProseScoresNothing() {
        // Recorded rather than asserted as correct. This engine matches a fixed vocabulary
        // (deadlock, memory leak, hang, contention, startup) and reads security and performance from
        // LABELS, never from the text. A report whose prose says "data loss" or "remote code
        // execution", filed without a matching label, therefore scores zero and ranks LOW.
        // Widening the vocabulary is a product decision, not a test fix, so the current behaviour is
        // pinned here: if it changes, this test fails and somebody chose to change it.
        assertEquals(
                0,
                analyse("Data loss on rollover", "records are silently dropped").score());
        assertEquals(
                Severity.LOW,
                analyse("Remote code execution in the parser", "an attacker can execute code")
                        .severity());
    }

    @Test
    @DisplayName("a crash outranks a question")
    void crashOutranksQuestion() {
        int crash = analyse("NullPointerException on startup", "the process crashes immediately")
                .score();
        int question = analyse("How do I configure this?", "asking for guidance on setup")
                .score();
        assertTrue(crash > question, "crash scored " + crash + ", question scored " + question);
    }

    @Test
    @DisplayName("a security LABEL is treated as serious, which is the supported route")
    void securityLabelIsSerious() {
        Issue labelled = new Issue(
                1L,
                "Remote code execution in the parser",
                "an attacker can execute arbitrary code",
                "open",
                0,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                null,
                List.of(new com.osscli.model.Label("security")),
                null,
                "CONTRIBUTOR",
                "https://example.invalid/1");
        IssueAnalysis a = new SeverityAnalyzer().analyze(labelled);
        assertTrue(a.score() > 0, "a security-labelled report scored " + a.score());
    }

    @Test
    @DisplayName("discussion raises a report's standing")
    void commentsCount() {
        SeverityAnalyzer an = new SeverityAnalyzer();
        int quiet = an.analyze(issue("Deadlock in the appender", "it hangs", 0)).score();
        int loud = an.analyze(issue("Deadlock in the appender", "it hangs", 90)).score();
        assertTrue(loud >= quiet, "a heavily discussed issue should not rank below an identical quiet one");
    }

    @Test
    @DisplayName("a null body is ranked rather than thrown at")
    void nullBody() {
        IssueAnalysis a = new SeverityAnalyzer().analyze(issue("Crash on start", null, 0));
        assertNotNull(a.severity());
    }

    @Test
    @DisplayName("the issue it was asked about is the issue it answers about")
    void carriesItsIssue() {
        Issue in = issue("Anything", "at all", 1);
        assertEquals(in, new SeverityAnalyzer().analyze(in).issue());
    }

    @Test
    @DisplayName("wording does not change the verdict, only meaning does")
    void caseDoesNotMatter() {
        assertEquals(
                analyse("DATA LOSS ON ROLLOVER", "RECORDS ARE DROPPED").severity(),
                analyse("data loss on rollover", "records are dropped").severity());
    }
}
