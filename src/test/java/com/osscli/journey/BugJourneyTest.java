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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Report a fault, and have nothing leave the machine that you did not send.
 *
 * <p>The unit tests below this cover what the report contains. What they cannot cover is the thing
 * that actually matters about this command, because it is a property of the whole run rather than
 * of any one class: <b>a person who is never asked is a person from whom nothing is posted.</b>
 * These runs have no console -- the process is started by a test, which is exactly the shape of a
 * script, a CI job or a pipe -- and the assertion is that the fake GitHub was written to zero times.
 *
 * <p>The second thing only a journey sees is the redaction, end to end. A crash is written to the
 * store the way a real one would be, carrying a home path, a key and a repository name, and the
 * report that comes back out is searched for all three. Every step in between has its own test and
 * they all pass; what nobody owned was whether the three of them, run in order by a real process,
 * still produce a document that is safe to publish.
 */
class BugJourneyTest {

    private static final String KEY = "ghp_" + "A".repeat(36);
    private static final String THEIR_REPO = "someorg/their-project";

    /** A crash on disk in the shape {@code Crash.remember()} writes, so `--last` has something to find. */
    private static void aRememberedCrash(Path home) throws Exception {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("last-crash.txt"),
                String.join(
                        "\n",
                        "command\toss hub --repo " + THEIR_REPO + " --key " + KEY,
                        "type\tjava.lang.IllegalStateException",
                        "message\tno ledger under " + System.getProperty("user.home") + "/.oss-cli",
                        "version\toss 4.1.1",
                        "platform\tMac OS X · java 21",
                        "stack",
                        "java.lang.IllegalStateException: no ledger\n"
                                + "\tat com.osscli.cli.HubCommand.call(HubCommand.java:71)\n"));
    }

    @Test
    @DisplayName("describing a bug prints the whole report and posts nothing")
    void describeAndSeeIt(@TempDir Path home, @TempDir Path cwd) throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            Journey.Ran ran = Journey.ossAgainst(github, home, cwd, "bug", "the board page is blank");

            assertEquals(0, ran.code(), ran.all());
            assertTrue(ran.all().contains("what would be posted"), ran.all());
            assertTrue(ran.all().contains("the board page is blank"), ran.all());
            // The promise. Nothing here could have confirmed anything, so nothing may have been
            // sent -- and this is checked at the server rather than by reading the output, because
            // the output is exactly what would look right if it had posted anyway.
            assertTrue(github.posted().isEmpty(), "something was posted with nobody to confirm it: " + github.posted());
        }
    }

    @Test
    @DisplayName("--print never posts, whatever else is true")
    void printPostsNothing(@TempDir Path home, @TempDir Path cwd) throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            Journey.Ran ran = Journey.ossAgainst(github, home, cwd, "bug", "--print", "sync hangs");

            assertEquals(0, ran.code(), ran.all());
            assertTrue(github.posted().isEmpty(), github.posted().toString());
            // Not even the duplicate search: --print says do not go outward, and a lookup is
            // outward. It also means --print works with the wifi off.
            assertFalse(
                    github.sawPathContaining("/search/issues"), github.asked().toString());
        }
    }

    @Test
    @DisplayName("the last error is reportable afterwards, with nothing private in it")
    void reportTheLastCrash(@TempDir Path home, @TempDir Path cwd) throws Exception {
        aRememberedCrash(home);

        try (FakeGitHub github = new FakeGitHub()) {
            Journey.Ran ran = Journey.ossAgainst(github, home, cwd, "bug", "--last", "--print");

            assertEquals(0, ran.code(), ran.all());
            String shown = ran.all();

            // The fault survives. This is a bug report or it is nothing.
            assertTrue(shown.contains("IllegalStateException"), shown);
            assertTrue(shown.contains("HubCommand.java:71"), shown);

            // Nothing else does.
            assertFalse(shown.contains(KEY), "an API key reached the report");
            assertFalse(shown.contains(System.getProperty("user.home")), "the home directory reached the report");
            assertFalse(shown.contains("their-project"), "somebody else's project was named in the report");
            assertTrue(shown.contains("owner/name"), shown);
        }
    }

    @Test
    @DisplayName("nothing remembered says so, rather than filing something invented")
    void nothingToReport(@TempDir Path home, @TempDir Path cwd) throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            Journey.Ran ran = Journey.ossAgainst(github, home, cwd, "bug", "--last");

            assertEquals(0, ran.code(), ran.all());
            assertTrue(ran.all().contains("Nothing remembered"), ran.all());
            assertTrue(github.posted().isEmpty(), github.posted().toString());
        }
    }

    @Test
    @DisplayName("with no token it still builds the report and names where to put it")
    void noTokenStillWorks(@TempDir Path home, @TempDir Path cwd) throws Exception {
        // The one requirement this tool has is a token, and this is the command most likely to be
        // run by somebody who has not set one up yet -- they are here because something broke.
        //
        // With no credential reachable at all, keychain included. The first version of this cleared
        // the environment and passed on a laptop for the wrong reason: the keychain still answered,
        // so the run took the "have a token" path and printed the same paste address by a different
        // route. The branch this test is named after was reached first on a CI runner, where
        // getGitHubToken() threw and the crash reporter offered to file a bug about it.
        Journey.Ran ran = Journey.ossWithNoCredentialAnywhere(home, cwd, "bug", "sync hangs on a large repository");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(ran.all().contains("sync hangs on a large repository"), ran.all());
        assertTrue(ran.all().contains("No GitHub token"), "it did not take the no-token path: " + ran.all());
        assertTrue(ran.all().contains("issues/new"), "it did not say where to paste it: " + ran.all());
        assertFalse(ran.all().contains("\tat com.osscli"), "a stack trace was printed: " + ran.all());
    }

    @Test
    @DisplayName("a refusal names a next step and does not print a stack trace")
    void refusalIsUsable(@TempDir Path home, @TempDir Path cwd) throws Exception {
        // Nothing said, and no console to ask at. What every refusal here shares: non-zero, a next
        // step named, no stack trace -- asserted rather than the particular wording, because which
        // wall a run hits first depends on the machine it is on.
        Journey.Ran ran = Journey.oss(home, cwd, "bug");

        assertEquals(1, ran.code(), ran.all());
        assertTrue(ran.all().contains("oss bug"), ran.all());
        assertFalse(ran.all().contains("\tat com.osscli"), "a stack trace was printed: " + ran.all());
    }
}
