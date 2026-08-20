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

        assertEquals(List.of("search", "duplicates", "followup", "followup-one", "hidden-critical", "doctor"), keys);
    }
}
