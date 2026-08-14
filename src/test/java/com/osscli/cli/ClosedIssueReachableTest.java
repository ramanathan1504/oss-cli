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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.AppPaths;
import com.osscli.model.Issue;
import com.osscli.storage.DatabaseManager;
import com.osscli.storage.SqliteStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a closed issue can be reached at all.
 *
 * <p>{@code sync} saves only <b>open</b> issues, and only those changed since its watermark. So a
 * closed issue could never arrive in local storage by any route, and {@code oss chat 4129} answered
 * with advice that could not work:
 *
 * <pre>
 * Issue #4129 is not in the local data for 'apache/logging-log4j2'.
 *   oss sync -r apache/logging-log4j2 brings it down first.
 * </pre>
 *
 * <p>Running that printed "Open Issues Saved: 0" and changed nothing, so the user ran it again. A
 * closed issue is exactly the kind somebody goes back to read — it is closed because it was
 * resolved, and the resolution is the interesting part.
 *
 * <p>{@code issue <n> --repo <r>} already fetched the whole payload in order to print it. It now
 * keeps it, which costs one insert and turns a dead end into a route.
 */
class ClosedIssueReachableTest {

    private static final String REPO = "owner/closed-test";

    @BeforeAll
    static void schema() throws Exception {
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store.");
        DatabaseManager.initializeSchema();
    }

    private static Issue issue(long number, String state) {
        return new Issue(
                number,
                "a closed thing worth rereading",
                "the body",
                state,
                0,
                "2026-05-25T02:59:48Z",
                "2026-06-01T00:00:00Z",
                null,
                List.of(),
                null,
                "CONTRIBUTOR",
                "https://github.com/" + REPO + "/issues/" + number);
    }

    private static boolean stored(long number) throws SQLException {
        return SqliteStorage.loadIssues(REPO).stream().anyMatch(i -> i.number() == number);
    }

    @Test
    @DisplayName("a closed issue can be stored and read back")
    void closedIssuesAreStorable() throws SQLException {
        // The storage layer never refused them; nothing simply ever offered one.
        SqliteStorage.saveIssues(REPO, List.of(issue(4129, "closed")));

        assertTrue(stored(4129), "a closed issue must survive a round trip through storage");
    }

    @Test
    @DisplayName("state is kept, so a closed issue is not silently rewritten as open")
    void stateSurvives() throws SQLException {
        SqliteStorage.saveIssues(REPO, List.of(issue(4130, "closed")));

        String state = SqliteStorage.loadIssues(REPO).stream()
                .filter(i -> i.number() == 4130)
                .map(Issue::state)
                .findFirst()
                .orElse("");
        assertTrue("closed".equals(state), "state came back as '" + state + "'");
    }

    // ==========================================
    // The advice
    // ==========================================

    // ==========================================
    // Behaviour, not source text
    // ==========================================

    /** A real GitHub issues payload, trimmed but not simplified: the shape the API actually sends. */
    private static final String CLOSED_PAYLOAD = """
            {
              "number": 4129,
              "title": "Log4J pattern layout inconsistencies when locale is specified",
              "body": "the report as filed",
              "state": "closed",
              "comments": 7,
              "created_at": "2026-05-25T02:59:48Z",
              "updated_at": "2026-06-01T10:00:00Z",
              "labels": [{"name": "bug"}, {"name": "layouts"}],
              "user": {"login": "xzel23"},
              "author_association": "CONTRIBUTOR",
              "html_url": "https://github.com/owner/closed-test/issues/4129",
              "reactions": {"total_count": 3},
              "locked": false,
              "node_id": "I_kwDO"
            }
            """;

    @Test
    @DisplayName("running the keep step turns a fetched payload into a row")
    void keepActuallyPersists() throws Exception {
        // This is the test the grep-based one could not be. It executes the code: if the call
        // were ever moved somewhere unreachable, or the parse broke on a field GitHub added,
        // this fails and a source search for "saveIssues" would not.
        JsonNode payload = new ObjectMapper().readTree(CLOSED_PAYLOAD);

        assertTrue(IssueCommand.keep(REPO, 4129, payload), "keep() should report success");
        assertTrue(stored(4129), "and the issue must actually be in local storage afterwards");
    }

    @Test
    @DisplayName("what was stored is what was fetched")
    void theStoredRowIsFaithful() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(CLOSED_PAYLOAD);
        IssueCommand.keep(REPO, 4129, payload);

        Issue back = SqliteStorage.loadIssues(REPO).stream()
                .filter(i -> i.number() == 4129)
                .findFirst()
                .orElseThrow(() -> new AssertionError("not stored"));

        assertTrue("closed".equals(back.state()), "state was '" + back.state() + "'");
        assertTrue(back.title().contains("locale is specified"), "title was '" + back.title() + "'");
        assertTrue(back.body().contains("as filed"), "body was '" + back.body() + "'");
    }

    @Test
    @DisplayName("unknown fields from a newer API do not stop it being kept")
    void unknownFieldsAreTolerated() throws Exception {
        // GitHub adds fields without warning. A parse that failed on them would reintroduce the
        // dead end quietly, on some future Tuesday, for reasons unrelated to this code.
        JsonNode payload = new ObjectMapper()
                .readTree(CLOSED_PAYLOAD.replace("\"locked\": false", "\"locked\": false, \"invented_field\": 1"));

        assertTrue(IssueCommand.keep(REPO, 4129, payload), "an unrecognised field must not prevent storage");
    }

    @Test
    @DisplayName("a payload it cannot use is reported false, never thrown")
    void badPayloadDoesNotThrow() throws Exception {
        // The caller has already printed the issue by this point. Throwing here would turn a
        // successful read into a failed command over a side effect nobody asked for.
        JsonNode notAnIssue = new ObjectMapper().readTree("{\"number\": \"not-a-number\"}");

        assertFalse(IssueCommand.keep(REPO, 9999, notAnIssue), "an unusable payload is a false, not an exception");
        assertFalse(stored(9999), "and nothing partial should be left behind");
    }

    @Test
    @DisplayName("chat no longer sends the user to a command that cannot help")
    void chatAdviceIsReachable() throws IOException {
        // Still a source assertion, deliberately: the thing being pinned IS the sentence, and
        // the sentence is what cost the user two round trips.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/ChatCommand.java"));

        assertTrue(
                src.contains("oss issue {} --repo {} fetches it, and keeps it."),
                "chat must name the command that actually retrieves a closed issue");
        assertFalse(
                src.contains("oss sync -r {} brings it down first."),
                "the old advice loops: sync reports '0 saved' and leaves the user where they started");
    }
}
