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
package com.osscli.bug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What would be posted, checked before anything can post it. */
class BugReportTest {

    private static final String HOME = "/Users/someone-real";
    private static final Set<String> REPOS = Set.of("apache/logging-log4j2");

    private static Crash nasty() {
        return new Crash(
                "oss hub --repo apache/logging-log4j2 --token ghp_" + "A".repeat(36),
                "java.lang.IllegalStateException",
                "no ledger under " + HOME + "/.oss-cli",
                """
                java.lang.IllegalStateException: no ledger
                \tat com.osscli.cli.HubCommand.call(HubCommand.java:71)
                \tat picocli.CommandLine.execute(CommandLine.java:2170)
                """,
                "oss 4.1.1",
                "Mac OS X 25.5.0 · java 21");
    }

    @Test
    @DisplayName("nothing private reaches the body, whichever field it came in through")
    void theWholeReportIsRedacted() {
        // Field by field rather than once at the end: the command, the message and the stack are
        // three separate strings and it only takes one of them being appended raw.
        String body = BugReport.of(nasty(), "it broke in " + HOME, HOME, REPOS).body();

        assertFalse(body.contains("ghp_"), body);
        assertFalse(body.contains(HOME), body);
        assertFalse(body.contains("logging-log4j2"), body);
        assertFalse(body.contains("someone-real"), body);
        assertTrue(body.contains("owner/name"), body);
        // And still a bug report.
        assertTrue(body.contains("IllegalStateException"), body);
        assertTrue(body.contains("HubCommand.java:71"), body);
    }

    @Test
    @DisplayName("the title is redacted too, which is the field a reader sees first")
    void titleIsRedacted() {
        BugReport report =
                BugReport.drafted("hub crashes on apache/logging-log4j2", "summary", nasty(), null, HOME, REPOS);

        assertFalse(report.title().contains("logging-log4j2"), report.title());
    }

    @Test
    @DisplayName("a title too long for a list is cut rather than allowed to be cut for us")
    void titleIsCapped() {
        BugReport report = BugReport.drafted("x".repeat(300), "s", nasty(), null, HOME, Set.of());

        assertTrue(report.title().length() <= 100, "length " + report.title().length());
        assertTrue(report.title().endsWith("…"));
    }

    @Test
    @DisplayName("two machines hitting one fault produce one signature")
    void signatureIgnoresWhatDiffersBetweenMachines() {
        Crash a = nasty();
        Crash b = new Crash(
                a.command(), a.type(), "no ledger under /Users/other/.oss-cli", a.stack(), a.version(), a.platform());

        // The message carries a path, so a signature that included it would file the same bug once
        // per user.
        assertEquals(a.signature(), b.signature());
        assertEquals("oss:java.lang.IllegalStateException:com.osscli.cli.HubCommand.call", a.signature());
    }

    @Test
    @DisplayName("the signature is in the body, because that is how the next report finds this one")
    void signatureTravels() {
        assertTrue(BugReport.of(nasty(), null, HOME, REPOS).body().contains("<!-- oss-signature: "));
    }

    @Test
    @DisplayName("a model's answer is taken apart into the two fields that were asked for")
    void parsesTheModel() {
        BugReport report = BugReport.fromModel("""
                TITLE: hub: IllegalStateException when the ledger is missing
                SUMMARY: Running hub with no ledger throws instead of reporting.
                The stack points at HubCommand.call.
                """, nasty(), null, HOME, REPOS);

        assertTrue(report.drafted());
        assertEquals("hub: IllegalStateException when the ledger is missing", report.title());
        assertTrue(report.body().startsWith("Running hub with no ledger throws instead of reporting."), report.body());
        assertTrue(report.body().contains("The stack points at HubCommand.call."), report.body());
    }

    @Test
    @DisplayName("a model that answered in some other shape does not get to name the issue")
    void fallsBackWhenTheShapeIsWrong() {
        // Posting the first line of an essay as a title is worse than posting the exception, and
        // the difference has to be visible: drafted() is what the confirmation prints.
        BugReport report = BugReport.fromModel("Sure! Here is what I think happened…", nasty(), null, HOME, REPOS);

        assertFalse(report.drafted());
        assertTrue(report.title().startsWith("oss: IllegalStateException"), report.title());
    }

    @Test
    @DisplayName("no model at all still produces a report worth filing")
    void theFloor() {
        // The floor, in the sense the term index is the floor for search: not a degraded mode that
        // apologises. A stack, a build and the command is a better issue than most.
        BugReport report = BugReport.of(nasty(), "the board is blank", HOME, REPOS);

        assertFalse(report.drafted());
        assertTrue(report.body().contains("**What I was doing**"));
        assertTrue(report.body().contains("the board is blank"));
        assertTrue(report.body().contains("**Ran**"));
        assertTrue(report.body().contains("**Build**"));
        assertTrue(report.body().contains("**Stack**"));
    }

    @Test
    @DisplayName("a report somebody typed is titled by what they typed")
    void handWrittenTitle() {
        // It read "oss: reported by hand — the board page is blank", where every word before the
        // dash is machinery the reader steps over to reach the sentence.
        BugReport report =
                BugReport.of(Crash.byHand("the board page is blank"), "the board page is blank", HOME, Set.of());

        assertEquals("the board page is blank", report.title());
    }

    @Test
    @DisplayName("two people describing two different faults do not share one signature")
    void handWrittenReportsHaveNoSignature() {
        // They did, and it was "oss:reported by hand:unknown" for every one of them -- so the second
        // person to type `oss bug` would have been told their bug was already filed, by an issue
        // about something else. A duplicate check that is wrong is worse than no duplicate check.
        Crash a = Crash.byHand("the board is blank");
        Crash b = Crash.byHand("sync hangs on a large repository");

        assertEquals("", a.signature());
        assertEquals("", b.signature());
        assertFalse(BugReport.of(a, "the board is blank", HOME, Set.of()).body().contains("oss-signature"));
    }

    @Test
    @DisplayName("a hand-written report does not print a heading with nothing under it")
    void noEmptySections() {
        String body = BugReport.of(Crash.byHand("the board is blank"), "the board is blank", HOME, Set.of())
                .body();

        // "Ran: oss" and an empty stack are headings that answer nothing while looking like they did.
        assertFalse(body.contains("**Ran**"), body);
        assertFalse(body.contains("**Stack**"), body);
        assertTrue(body.contains("**Build**"), body);
        // Nor the same sentence twice: the title of a one-line report already is the sentence.
        assertFalse(body.contains("**What I was doing**"), body);
    }

    @Test
    @DisplayName("a trace too long to read is shortened from the middle, where the reflection is")
    void trimsTheMiddle() {
        StringBuilder deep = new StringBuilder("java.lang.RuntimeException: x\n");
        for (int i = 0; i < 200; i++) {
            deep.append("\tat some.Frame")
                    .append(i)
                    .append(".run(Frame.java:")
                    .append(i)
                    .append(")\n");
        }
        String trimmed = BugReport.trimmed(deep.toString());

        assertTrue(trimmed.contains("frames elided"), trimmed);
        // The top is where the fault is; the bottom is how it was reached. Both survive.
        assertTrue(trimmed.contains("Frame0.run"), trimmed);
        assertTrue(trimmed.contains("Frame199.run"), trimmed);
        assertTrue(trimmed.lines().count() < 50, "still " + trimmed.lines().count() + " lines");
    }

    @Test
    @DisplayName("a short trace is left exactly as it is")
    void shortTracesAreNotTouched() {
        assertEquals("a\nb", BugReport.trimmed("a\nb"));
    }

    @Test
    @DisplayName("the prompt tells the model not to add anything it was not given")
    void promptForbidsInvention() {
        String prompt = BugReport.prompt(nasty(), null, HOME, REPOS);

        // The redactor cannot take out a path a model invented, because it never saw it.
        assertTrue(prompt.contains("Do not add any path, username, email, API key or repository name"), prompt);
        assertFalse(prompt.contains("ghp_"), "the prompt itself carried a key to the API");
        assertFalse(prompt.contains(HOME), "the prompt itself carried the home directory");
    }
}
