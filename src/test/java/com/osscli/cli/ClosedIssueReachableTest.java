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

    @Test
    @DisplayName("chat no longer sends the user to a command that cannot help")
    void chatAdviceIsReachable() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/ChatCommand.java"));

        assertTrue(
                src.contains("oss issue {} --repo {} fetches it, and keeps it."),
                "chat must name the command that actually retrieves a closed issue");
        assertFalse(
                src.contains("oss sync -r {} brings it down first."),
                "the old advice loops: sync reports '0 saved' and leaves the user where they started");
    }

    @Test
    @DisplayName("issue keeps what it fetched, rather than printing and discarding it")
    void issueCommandPersists() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/IssueCommand.java"));

        assertTrue(src.contains("SqliteStorage.saveIssues"), "issue must store the payload it already has");
        assertTrue(src.contains("kept locally"), "and say so, because the point is that the next command now works");
    }

    @Test
    @DisplayName("a storage failure does not turn a successful read into an error")
    void storingIsABonusNotTheJob() throws IOException {
        // Reading the issue is what was asked for. If the insert fails, the user should still
        // get what they came for rather than an error about a side effect they did not request.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/IssueCommand.java"));
        int save = src.indexOf("SqliteStorage.saveIssues");
        int catchAfter = src.indexOf("catch", save);

        assertTrue(save > 0 && catchAfter > save, "the save must sit inside its own try/catch");
        assertTrue(
                src.substring(save, Math.min(src.length(), catchAfter + 400)).contains("LOGGER.debug"),
                "a failed save should be a debug note, not a failure");
    }
}
