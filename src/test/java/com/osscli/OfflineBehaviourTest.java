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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.github.Reachability;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the commands say when the machine cannot reach GitHub.
 *
 * <p>Turning the wifi off broke six commands in three different ways at once, and the suite was
 * green throughout. It could not have caught any of them: every test either had a network or mocked
 * the layer above the socket, so the one exception a disconnected machine actually raises — a
 * {@link ConnectException} carrying a null message — was never once constructed.
 *
 * <p>These run the real commands with the API pointed at a host that does not resolve. That produces
 * the identical failure from the identical place, which is the only way to assert on what a user
 * offline is left holding.
 */
class OfflineBehaviourTest {

    /** Reserved by RFC 2606 to never resolve, so this is offline everywhere, forever. */
    private static final String NOWHERE = "https://api.invalid";

    @BeforeEach
    void unplug() {
        System.setProperty("oss.github.api", NOWHERE);
        Reachability.reset();
    }

    @AfterEach
    void plugBackIn() {
        System.clearProperty("oss.github.api");
        Reachability.reset();
    }

    // ==========================================
    // The diagnosis itself
    // ==========================================

    @Test
    @DisplayName("a connect failure with no message never reaches the user as the word null")
    void nullMessageIsNeverPrinted() {
        // The exact object the JDK hands back on a disconnected machine. getMessage() is null, and
        // every catch block in the tool used to print it unguarded.
        ConnectException bare = new ConnectException();
        bare.initCause(new UnresolvedAddressException());

        String described = Reachability.describe(bare);

        assertFalse(described.isBlank(), "describe() must always produce a sentence");
        assertNotEquals("null", described);
        assertFalse(described.contains("null"), "the word null must not survive into user text: " + described);
        assertTrue(described.contains("no network"), "and it should say what is actually wrong: " + described);
    }

    @Test
    @DisplayName("an exception with no message and no known cause still yields a sentence")
    void unknownFailureStillReads() {
        String described = Reachability.describe(new IllegalStateException());

        assertFalse(described.isBlank());
        assertFalse(described.contains("null"), described);
        assertTrue(described.contains("IllegalStateException"), "name the class rather than say nothing: " + described);
    }

    @Test
    @DisplayName("a throwable that causes itself does not spin forever")
    void selfCausedThrowableTerminates() {
        // Defensive, but the loop walks getCause() without a visited set and a cycle here would hang
        // the command rather than fail it, which is the worst way for a diagnostic to break.
        RuntimeException loop = new RuntimeException("round and round") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(Reachability.isNetwork(loop));
        assertTrue(Reachability.describe(loop).contains("round and round"));
    }

    @ParameterizedTest(name = "{0} is a network failure: {1}")
    @CsvSource({"unresolved,true", "unknownhost,true", "connect,true", "notfound404,false", "badcreds,false"})
    @DisplayName("only failures that happened before an answer count as offline")
    void onlyPreAnswerFailuresCount(String kind, boolean expected) {
        // A 401 and a 404 are answers -- GitHub was reached and said no. Calling those "offline"
        // would send someone to check their wifi over a permissions problem.
        Throwable t =
                switch (kind) {
                    case "unresolved" -> new java.io.IOException("wrapped", new UnresolvedAddressException());
                    case "unknownhost" -> new UnknownHostException("api.github.com");
                    case "connect" -> new ConnectException();
                    case "notfound404" -> new java.io.IOException("Repository or endpoint not found (404)");
                    default -> new java.io.IOException("GitHub rejected the token (401 Bad credentials)");
                };

        assertTrue(Reachability.isNetwork(t) == expected, kind + " misclassified");
    }

    @Test
    @DisplayName("a set of unreadable things is blamed on the network only once the network has failed")
    void unreadableReasonFollowsTheEvidence() {
        assertTrue(
                Reachability.whyUnreadable().contains("private"),
                "with no failure seen, the old wording is still the honest one");

        Reachability.describe(new ConnectException()); // one failure is enough to change the answer

        assertTrue(Reachability.whyUnreadable().contains("no network"), Reachability.whyUnreadable());
        assertFalse(
                Reachability.whyUnreadable().contains("private"),
                "it must stop offering three wrong explanations once it knows the real one");
    }

    // ==========================================
    // The commands, typed
    // ==========================================

    @Test
    @DisplayName("oss issue offline explains itself instead of printing 'error  null'")
    void issueOfflineExplainsItself() {
        Cli.Result r = Cli.run("issue", "4143", "--repo", "owner/name");

        assertNotEquals(0, r.exitCode(), "it could not fetch the issue, so it must not report success");
        assertFalse(r.says("error  null"), "the exact output the user got:\n" + r.all());
        assertTrue(r.says("no network"), "it should name the cause:\n" + r.all());
        assertTrue(r.says("oss search"), "and what still works without one:\n" + r.all());
    }

    @Test
    @DisplayName("oss pr offline explains itself instead of printing 'error  null'")
    void prOfflineExplainsItself() {
        Cli.Result r = Cli.run("pr", "4240", "--repo", "owner/name");

        assertNotEquals(0, r.exitCode());
        assertFalse(r.says("error  null"), r.all());
        assertTrue(r.says("no network"), r.all());
    }

    @Test
    @DisplayName("oss review offline answers in a sentence, not forty lines of stack")
    void reviewOfflineDoesNotDumpStack() {
        Cli.Result r = Cli.run("review", "4240", "-r", "owner/name");

        assertNotEquals(0, r.exitCode());
        // The catch named IllegalArgumentException, and a connect failure is not one, so it escaped
        // into picocli and the user got jdk.internal.net.http frames.
        assertFalse(r.says("jdk.internal.net.http"), "the stack trace is back:\n" + r.all());
        assertFalse(r.says("\tat "), "the stack trace is back:\n" + r.all());
        assertTrue(r.says("no network"), "and it should say what happened:\n" + r.all());
    }

    @Test
    @DisplayName("nothing offline blames a pull request for being private or deleted")
    void offlineNeverBlamesTheRepository() {
        // hub reported seventeen pull requests as "private, deleted, or no token" over a pulled
        // cable -- three explanations, all wrong, each of which sends the reader hunting.
        Cli.Result hub = Cli.run("hub");

        if (hub.says("unreachable")) {
            assertFalse(
                    hub.says("private, deleted, or no token"),
                    "it guessed a cause it had no evidence for:\n" + hub.all());
        }
    }
}
