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
package com.osscli.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.release.Surface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the page can only ask, and only ask things that exist.
 *
 * <p>The page dispatches these, and a browser has no terminal to confirm an outward write at. The
 * first test below is the reason dispatching from a page is allowed at all: if a command that
 * writes ever reaches this table, the build stops.
 */
class AskableTest {

    @Test
    @DisplayName("nothing on the table writes anything")
    void everyQuestionOnlyReads() {
        List<String> offences = new ArrayList<>();
        for (Askable.Question q : Askable.all()) {
            String verb = q.argv().get(0);
            if (Askable.WRITES.contains(verb)) {
                offences.add(q.key() + " runs '" + verb + "', which changes something");
            }
            for (String token : q.argv()) {
                // --record, --write, --comment, --sync: every flag that files or posts.
                if (token.startsWith("--")
                        && List.of(
                                        "--record",
                                        "--write",
                                        "--comment",
                                        "--sync",
                                        "--force",
                                        "--install",
                                        "--approve-upstream")
                                .contains(token)) {
                    offences.add(q.key() + " passes " + token + ", which writes");
                }
            }
        }
        assertTrue(offences.isEmpty(), "a browser cannot confirm an outward write: " + offences);
    }

    @Test
    @DisplayName("every command on the table is one this program has")
    void everyCommandExists() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(Path.of("release-surface.json")));

        for (Askable.Question q : Askable.all()) {
            String verb = q.argv().get(0);
            assertTrue(surface.commands().containsKey(verb), q.key() + " runs '" + verb + "', which is not a command");
            for (String token : q.argv()) {
                if (token.startsWith("--")) {
                    assertTrue(
                            surface.commands().get(verb).contains(token),
                            q.key() + " passes " + token + ", which " + verb + " does not have");
                }
            }
        }
    }

    @Test
    @DisplayName("every question says what it asks, in a sentence")
    void everyQuestionExplainsItself() {
        for (Askable.Question q : Askable.all()) {
            // This sentence is the button's hover text. There is no other documentation for the
            // page and there should not be: hovering says what a button asks and what it runs.
            assertNotNull(q.asks(), q.key());
            assertTrue(q.asks().length() > 40, q.key() + " does not say what it answers: " + q.asks());
            // Not required to end in a question mark. "This one pull request in full: what you
            // recorded, and what has happened to it since" answers a question without asking one,
            // and rewriting it into interrogative form would make it worse to hover over.
            assertTrue(Character.isUpperCase(q.asks().charAt(0)), q.key() + " hover text is not a sentence");
            assertFalse(q.empty().isBlank(), q.key() + " has nothing to say when the answer is empty");
        }
    }

    @Test
    @DisplayName("a slow question is allowed longer than a quick one")
    void timeoutsAreDeliberate() {
        for (Askable.Question q : Askable.all()) {
            assertTrue(q.timeoutSeconds() >= 60, q.key() + " is allowed less than a minute");
            assertTrue(q.timeoutSeconds() <= 600, q.key() + " would hold the page for ten minutes");
        }
        // duplicates compares every open issue against every other; search is one query.
        assertTrue(Askable.byKey("duplicates").timeoutSeconds()
                > Askable.byKey("search").timeoutSeconds());
    }

    @Test
    @DisplayName("a question that needs typing says so")
    void argumentsAreDeclared() {
        assertTrue(Askable.byKey("search").needsArgument());
        assertTrue(Askable.byKey("followup-one").needsArgument());
        assertFalse(Askable.byKey("doctor").needsArgument());
        assertEquals("text", Askable.byKey("search").arg());
    }

    @Test
    @DisplayName("a key the page does not have answers nothing")
    void unknownKeysAreRefused() {
        // The page posts a key; anything not on the table must not become a command line.
        assertNull(Askable.byKey("rm"));
        assertNull(Askable.byKey("sync"));
        assertNull(Askable.byKey(null));
    }

    @Test
    @DisplayName("the page asks this build, not whatever oss is on the PATH")
    void asksItsOwnBuild() {
        java.util.List<String> argv = ServeCommand.ownExecutable();

        // A page served by this build must ask this build. Falling through to the installed command
        // would answer from a different version than the one being looked at -- which is how a page
        // and its tool come to disagree, the thing the whole design exists to prevent.
        assertFalse(argv.isEmpty());
        if (argv.size() > 1) {
            assertEquals("-jar", argv.get(1));
            assertTrue(argv.get(2).endsWith(".jar"), argv.get(2));
        } else {
            assertEquals("oss", argv.get(0));
        }
    }

    @Test
    @DisplayName("query parsing survives what a browser actually sends")
    void queryParsing() {
        assertEquals(
                "circular reference",
                ServeCommand.query("q=search&arg=circular%20reference").get("arg"));
        assertEquals(
                "search",
                ServeCommand.query("q=search&arg=circular%20reference").get("q"));
        // Empty, absent, and malformed all mean "nothing asked for", never a crash.
        assertTrue(ServeCommand.query(null).isEmpty());
        assertTrue(ServeCommand.query("").isEmpty());
        assertTrue(ServeCommand.query("=novalue&alsobad").isEmpty());
        // An ampersand inside a value is why this decodes rather than splits naively.
        assertEquals("a&b", ServeCommand.query("arg=a%26b").get("arg"));
    }

    @Test
    @DisplayName("the questions keep the order they are offered in")
    void orderIsPreserved() {
        // Map.copyOf returns an unordered map. The page listed doctor first and search fourth,
        // which is not the order anybody chose -- and "in the order they are offered" was already
        // written on the accessor.
        List<String> keys = Askable.all().stream().map(Askable.Question::key).toList();

        // hub and pick first: they are the board, and the board is what the page opens on.
        assertEquals(
                List.of(
                        "hub",
                        "pick",
                        "search",
                        "duplicates",
                        "followup",
                        "followup-one",
                        "critical",
                        "prs",
                        "triage",
                        "hidden-critical",
                        "doctor"),
                keys);
    }

    @Test
    @DisplayName("only a row with a real verdict offers \"since I reviewed\"")
    void sinceIsDrawnOnlyWhereItAnswers() {
        // The ledger writes "none" for a row recorded but not judged. Drawing the button there
        // would offer to report what changed since a verdict that was never given.
        assertTrue(ServeCommand.hasVerdict("take"));
        assertTrue(ServeCommand.hasVerdict("changes"));
        assertFalse(ServeCommand.hasVerdict("none"));
        assertFalse(ServeCommand.hasVerdict(" none "));
        assertFalse(ServeCommand.hasVerdict(""));
        assertFalse(ServeCommand.hasVerdict(null));
    }

    @Test
    @DisplayName("a port already answering is named, not guessed at")
    void occupantIsNamedFromItsOwnPage() {
        // `oss serve` said "Another instance may already be serving", which on the machine that
        // found this was wrong: `oss run hub` defaults to the same port, so the occupant was a
        // different surface of the same tool.
        assertEquals("\"oss run hub\"", ServeCommand.titleOf("<html><head><title>oss run hub</title>"));
        assertEquals("\"oss\"", ServeCommand.titleOf("<TITLE> oss </TITLE>"));
        // Not HTML, no title, or nothing at all: there is nothing to name, and saying so beats
        // inventing a name for it.
        assertNull(ServeCommand.titleOf("{\"json\":true}"));
        assertNull(ServeCommand.titleOf(""));
        assertNull(ServeCommand.titleOf(null));
    }

    @Test
    @DisplayName("the page keeps the places the board and the questions are drawn into")
    void thePageStillHasItsBoard() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/osscli/serve/ServeCommand.java"));

        // Every section renders into a named container. A page that loses one of them fails
        // silently -- the fetch succeeds and nothing appears.
        for (String id : List.of(
                "id=\"wn\"",
                "id=\"wsaid\"",
                "id=\"wlist\"",
                "id=\"wthem\"",
                "id=\"oneof\"",
                "id=\"picks\"",
                "id=\"sweep\"",
                "id=\"askhost\"",
                "id=\"builtin\"",
                "id=\"skills\"")) {
            assertTrue(source.contains(id), "the page no longer draws " + id);
        }
        // Asking and doing must not look alike: the ask button is the dashed one.
        assertTrue(source.contains(".ask{"), "the ask style is gone");
        assertTrue(source.contains("border:1px dashed"), "ask buttons are no longer dashed");
    }

    @Test
    @DisplayName("the page is told the command it can go and type")
    void payloadNamesTheCommand() {
        List<java.util.Map<String, Object>> payload = ServeCommand.questionsPayload();

        assertEquals(Askable.all().size(), payload.size());
        java.util.Map<String, Object> hub = payload.stream()
                .filter(q -> "hub".equals(q.get("key")))
                .findFirst()
                .orElseThrow();

        // Spelled as a person would type it: the reader can run the same thing in a terminal and
        // get the same answer, which is the claim the page rests on.
        assertEquals("oss hub", hub.get("runs"));
        assertEquals("", hub.get("arg"));

        java.util.Map<String, Object> followup = payload.stream()
                .filter(q -> "followup".equals(q.get("key")))
                .findFirst()
                .orElseThrow();
        assertEquals("oss followup --changed", followup.get("runs"));

        // Every entry carries the sentence it answers; that is the hover text and there is no
        // other documentation for this page.
        for (java.util.Map<String, Object> q : payload) {
            assertFalse(String.valueOf(q.get("asks")).isBlank(), String.valueOf(q.get("key")));
        }
    }

    @Test
    @DisplayName("a command that writes a file is barred, even though the file is local")
    void writingLocallyIsStillWriting() {
        // report writes markdown through MarkdownReportWriter, and backlog shells out to a script
        // that writes an HTML page into the working directory. Neither shows up in a grep of its
        // own command class -- the write is a class away, or a process away -- which is how both
        // nearly reached this table on the strength of one.
        assertTrue(Askable.WRITES.contains("report"));
        assertTrue(Askable.WRITES.contains("backlog"));
        assertNull(Askable.byKey("report"));
        assertNull(Askable.byKey("backlog"));
    }

    @Test
    @DisplayName("the board's verdict rule is cheap enough to run on every row")
    void verdictCheckIsCheap() {
        long start = System.nanoTime();
        for (int i = 0; i < 500_000; i++) {
            ServeCommand.hasVerdict(i % 3 == 0 ? "none" : "take");
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        // It runs once per row on every page load, and a ledger grows for as long as somebody keeps
        // reviewing. Half a million in under a second leaves no room for it to become the reason a
        // laptop's fan comes on.
        assertTrue(ms < 1_000, "500,000 checks took " + ms + "ms");
    }
}
