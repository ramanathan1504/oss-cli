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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.github.Reachability;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Two failures found by pasting a documented example into a real terminal.
 *
 * <pre>
 * $ oss followup --record 4234 --repo owner/name --verdict blocked --note "..."
 * error  could not read owner/name#4234
 *
 * $ oss followup                 # every recorded PR, one line each
 * Invalid value for positional parameter at index 0 (&lt;only&gt;): '#' is not an int
 * [twenty lines of usage]
 * </pre>
 *
 * <p>The second is the documentation and the shell disagreeing. Examples put the explanation on the
 * same line, which is the universal convention; zsh has {@code interactive_comments} off, so it
 * hands {@code #} through as an argument. bash users never see it, which is why it survived sixty-one
 * documented example lines.
 *
 * <p>The first is one sentence covering four different problems: a pull request that does not exist,
 * a token that cannot see it, a rate limit, and a pulled cable. They have four different remedies and
 * the sentence suggested none of them.
 *
 * <p>The GitHub half runs against a stub serving dummy fixtures on localhost, so "404" and
 * "unreachable" are producible on demand and in CI, and nothing here touches a real project.
 */
class PastedAndDummyGitHubTest {

    // ==========================================
    // Pasted documentation
    // ==========================================

    @Nested
    @DisplayName("a command pasted out of the docs")
    class Pasted {

        @Test
        @DisplayName("runs, with the trailing comment discarded")
        void trailingCommentIsDropped() {
            assertArrayEquals(new String[] {"followup"}, Main.withoutPastedComment(new String[] {
                "followup", "#", "every", "recorded", "PR,", "one", "line", "each"
            }));

            assertArrayEquals(new String[] {"followup", "--changed"}, Main.withoutPastedComment(new String[] {
                "followup", "--changed", "#", "only", "the", "ones", "that", "moved"
            }));

            assertArrayEquals(new String[] {"search", "rollover compression"}, Main.withoutPastedComment(new String[] {
                "search", "rollover compression", "#", "your", "own", "data,", "no", "model", "needed"
            }));
        }

        @ParameterizedTest(name = "{0} is left alone")
        @CsvSource({"#4240", "##", "issue#12", "a#b", "'--note=# not a comment'"})
        @DisplayName("does not swallow an argument that merely contains a hash")
        void onlyABareHashCounts(String argument) {
            // Somebody typing `oss pr #4240` means the number. Discarding it would trade a clear
            // error for a confusing one -- the command would silently run against nothing.
            String[] args = {"pr", argument};

            assertArrayEquals(args, Main.withoutPastedComment(args));
        }

        @Test
        @DisplayName("is unchanged when there is no comment at all")
        void ordinaryArgumentsPassThrough() {
            String[] args = {"review", "4240", "-r", "owner/name", "--no-verdict"};

            assertArrayEquals(args, Main.withoutPastedComment(args));
            assertArrayEquals(new String[] {}, Main.withoutPastedComment(new String[] {}));
        }

        @Test
        @DisplayName("end to end: the pasted line is answered, not refused")
        void pastedLineIsAnswered() {
            // `model` and not `history`, though history is what the docs paste. The harness does
            // not redirect System.in, so a command that waits for a keypress waits forever -- and
            // `history` browses. The first version of this test hung the whole suite, which in CI
            // is a job that never ends rather than a test that fails.
            Cli.Result r = Cli.run("model", "#", "present", "or", "not");

            assertNotEquals(2, r.exitCode(), "a pasted comment must not be a usage error:\n" + r.all());
            assertFalse(r.says("is not an int"), r.all());
            assertFalse(r.says("Usage: oss model"), "it printed usage instead of running:\n" + r.all());
        }
    }

    // ==========================================
    // A GitHub made of dummy data
    // ==========================================

    @Nested
    @DisplayName("why a pull request could not be read")
    class WhyNot {

        private HttpServer server;
        private String previousApi;

        /** One pull request that exists, at #4240. Everything else 404s, as GitHub does. */
        private static final String DUMMY_PR = """
                {"number":4240,"state":"open","title":"Dummy: compressionLevel=0 throws in the rollover",
                 "merged_at":null,"head":{"sha":"0123456789abcdef0123456789abcdef01234567"},
                 "user":{"login":"dummy-author"},"base":{"ref":"main"}}""";

        @BeforeEach
        void startDummyGitHub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                boolean known = exchange.getRequestURI().getPath().endsWith("/pulls/4240");
                byte[] body = (known ? DUMMY_PR : "{\"message\":\"Not Found\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(known ? 200 : 404, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            previousApi = System.getProperty("oss.github.api");
            System.setProperty(
                    "oss.github.api", "http://127.0.0.1:" + server.getAddress().getPort());
            Reachability.reset();
        }

        @AfterEach
        void stopDummyGitHub() {
            server.stop(0);
            if (previousApi == null) {
                System.clearProperty("oss.github.api");
            } else {
                System.setProperty("oss.github.api", previousApi);
            }
            Reachability.reset();
        }

        @Test
        @DisplayName("GitHub answered 404: the pull request, not the connection, is the problem")
        void missingPullRequestSaysSo() {
            Cli.Result r = Cli.run("followup", "--record", "4234", "--repo", "dummy/repo", "--verdict", "blocked");

            assertNotEquals(0, r.exitCode());
            assertTrue(r.says("does not exist, or this token cannot see it"), r.all());
            assertFalse(r.says("could not read"), "the old sentence covered four problems at once:\n" + r.all());
            assertFalse(r.says("no network"), "GitHub answered, so the network is not the cause:\n" + r.all());
        }

        @Test
        @DisplayName("GitHub never answered: the connection, not the pull request, is the problem")
        void unreachableSaysSomethingElseEntirely() {
            System.setProperty("oss.github.api", "https://api.invalid");

            Cli.Result r = Cli.run("followup", "--record", "4240", "--repo", "dummy/repo", "--verdict", "blocked");

            assertNotEquals(0, r.exitCode());
            assertTrue(r.says("no network"), r.all());
            assertFalse(
                    r.says("does not exist"),
                    "a pull request that was never asked about must not be called missing:\n" + r.all());
        }

        @Test
        @DisplayName("a pull request that is there is recorded from the dummy data")
        void recordingWorksAgainstDummyData() {
            Cli.Result r = Cli.run("followup", "--record", "4240", "--repo", "dummy/repo", "--verdict", "blocked");

            assertEquals(0, r.exitCode(), "the stub served it, so it must record:\n" + r.all());
            assertTrue(r.says("recorded"), r.all());
            // The short SHA proves it recorded what the stub served rather than something cached.
            assertTrue(r.says("0123456"), "it should record the head it was given:\n" + r.all());
        }
    }
}
