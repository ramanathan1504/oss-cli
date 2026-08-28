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
package com.osscli.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the record of what landed is complete, and honest about what it is not.
 *
 * <p>The commands here run against a repository the test builds, so they assert about git rather
 * than about whatever happens to be checked out on the machine running them.
 */
class ContributionsTest {

    private static void run(Path dir, String... cmd) throws IOException {
        Contributions.git(dir, List.of(cmd));
    }

    /** A tiny repository with a history somebody could plausibly have. */
    private static void repo(Path dir) throws IOException {
        run(dir, "git", "init", "--quiet", "--initial-branch=main");
        run(dir, "git", "config", "user.name", "Ramanathan");
        run(dir, "git", "config", "user.email", "rama@example.org");
        run(dir, "git", "config", "commit.gpgsign", "false");
    }

    private static void commit(Path dir, String file, String message) throws IOException {
        java.nio.file.Files.writeString(dir.resolve(file), "x\ny\n");
        run(dir, "git", "add", file);
        run(dir, "git", "commit", "--quiet", "-m", message);
    }

    // ==========================================
    // Whose work is it
    // ==========================================

    @Test
    @DisplayName("a GitHub login is not a git author, and both are searched")
    void identitiesCoverBothNames(@TempDir Path dir) throws IOException {
        // Searching this history for the login -- the only name the tool knew -- matched nothing,
        // while the commits were authored as "Ramanathan <rama@example.org>". The command reported
        // "no commits of yours" about forty of them.
        repo(dir);

        List<String> names = Contributions.identities(dir, "ramanathan1504");

        assertTrue(names.contains("ramanathan1504"), names.toString());
        assertTrue(names.contains("Ramanathan"), names.toString());
        assertTrue(names.contains("rama@example.org"), names.toString());
    }

    @Test
    @DisplayName("a repository with no identity of its own falls back to the machine's")
    void unconfiguredRepositoriesUseTheGlobalIdentity(@TempDir Path dir) throws IOException {
        // `git config --get` reads the global file when the repository sets nothing, and that is
        // the behaviour worth having: commits made here would carry the global identity, so that
        // is the name to search for. The login is always included whatever git says.
        run(dir, "git", "init", "--quiet", "--initial-branch=main");

        List<String> names = Contributions.identities(dir, "someone");

        assertTrue(names.contains("someone"), names.toString());
        assertFalse(names.isEmpty());
    }

    @Test
    @DisplayName("a co-authored commit is yours, and the note says which kind")
    void coAuthoredCommitsCount(@TempDir Path dir) throws IOException {
        // git log --author found 22 commits; counting trailers as well found 40. A record that
        // counts only what you pushed yourself understates the contribution by nearly half.
        repo(dir);
        // Authored by somebody else, with your name in the trailer -- which is the shape that
        // git log --author misses entirely, and it is nearly half of this history.
        run(dir, "git", "config", "user.name", "Volkan");
        run(dir, "git", "config", "user.email", "volkan@example.org");
        commit(dir, "a.txt", "Someone else's change\n\nCo-authored-by: Ramanathan <rama@example.org>");
        run(dir, "git", "branch", "-f", "origin/main");

        List<Contributions.Landing> landed = Contributions.landed(dir, List.of("Ramanathan"));

        assertEquals(1, landed.size(), "a commit with your name only in the trailer is still your work");
        assertTrue(landed.get(0).coAuthored(), "a trailer match must be recorded as co-authorship, not as sole");
    }

    @Test
    @DisplayName("one change that reached two branches is one piece of work")
    void forwardMergesAreNotCountedTwice(@TempDir Path dir) throws IOException {
        repo(dir);
        commit(dir, "a.txt", "Fix the thing (#4171)");
        run(dir, "git", "branch", "-f", "origin/2.x");
        run(dir, "git", "branch", "-f", "origin/main");

        assertEquals(1, Contributions.landed(dir, List.of("Ramanathan")).size());
    }

    // ==========================================
    // What landed
    // ==========================================

    @Test
    @DisplayName("the pull request is the last number on the subject, not the first")
    void theMergeNumberWins() {
        // The squash convention puts the merge last. Earlier numbers are the issues it fixed --
        // worth reading, and not this commit's pull request.
        assertEquals(4134, Contributions.prNumberIn("Fix changelog issue of PR references (#2250, #4124, #4134)"));
        assertEquals(4171, Contributions.prNumberIn("Add native tracing fields (#4171)"));
        assertEquals(0, Contributions.prNumberIn("A commit with no pull request"));
        assertEquals(0, Contributions.prNumberIn(null));
    }

    @Test
    @DisplayName("a commit that touched a binary file does not crash the count")
    void binaryFilesCountAsZero(@TempDir Path dir) throws IOException {
        // git reports "-" for both numbers on a binary file. Parsing that as an integer is a crash
        // on the one commit in forty that touched an image.
        repo(dir);
        java.nio.file.Files.write(dir.resolve("logo.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0, 1, 2, 3});
        run(dir, "git", "add", "logo.png");
        run(dir, "git", "commit", "--quiet", "-m", "Add a logo (#1)");
        String sha = Contributions.git(dir, List.of("git", "rev-parse", "HEAD")).strip();

        Contributions.Diffstat stat = Contributions.diffstat(dir, sha);

        assertEquals(List.of("logo.png"), stat.files());
        assertEquals(0, stat.insertions());
    }

    @Test
    @DisplayName("a commit message full of punctuation still parses back")
    void separatorsSurviveRealSubjects(@TempDir Path dir) throws IOException {
        // Newline-delimited output cannot be parsed back: these subjects carry backticks, colons,
        // brackets and pipes, and the bodies are multi-line by definition.
        repo(dir);
        commit(
                dir,
                "a.txt",
                "[main] Fix `RegexFilter` NPE when useRawMsg is null (#3265 port) (#4152)\n\nBody: with | pipes\nand newlines");
        run(dir, "git", "branch", "-f", "origin/main");

        List<Contributions.Landing> landed = Contributions.landed(dir, List.of("Ramanathan"));

        assertEquals(1, landed.size());
        assertTrue(
                landed.get(0).subject().startsWith("[main] Fix `RegexFilter` NPE"),
                landed.get(0).subject());
        assertEquals(4152, landed.get(0).pr());
        assertTrue(landed.get(0).message().contains("pipes"), landed.get(0).message());
    }

    @Test
    @DisplayName("a checkout with no release branches reports nothing rather than everything")
    void withoutReleaseBranchesNothingLanded(@TempDir Path dir) throws IOException {
        // A commit on a working branch has not landed. Counting it would make the record a list of
        // what was attempted.
        repo(dir);
        commit(dir, "a.txt", "Work in progress (#1)");

        assertTrue(Contributions.landed(dir, List.of("Ramanathan")).isEmpty());
    }

    // ==========================================
    // The note
    // ==========================================

    private static Contribution.Landed landed(List<Contribution.Remark> remarks, boolean coAuthored) {
        return new Contribution.Landed(
                "owner/name",
                4171,
                "Add native tracing fields to LogEvent",
                "b6ba7d0af60783e98fbe52aee4f9ea3e70deed25",
                "2.x",
                "2026-07-06",
                "Fixes #1976 by introducing a TraceContextProvider SPI.",
                List.of("log4j-core/src/main/java/A.java"),
                1376,
                26,
                "* Add tracing fields to RingBufferLogEvent",
                remarks,
                List.of("2026-07-04 - opened by @someone", "2026-07-06 - merged"),
                coAuthored);
    }

    @Test
    @DisplayName("a note carries the headings the digest already mines")
    void notesFeedTheDigest() {
        String note = Contribution.noteFor(landed(List.of(), false), "log4j");

        for (String heading : List.of("The Problem (What & Where)", "The Solution (How)", "The \"Why\"")) {
            assertTrue(note.contains("## " + heading), "missing " + heading);
        }
    }

    @Test
    @DisplayName("a change that merged unopposed says so rather than showing an empty section")
    void silenceIsStated() {
        // An empty section could equally mean the fetch failed. Which it was is worth knowing.
        String note = Contribution.noteFor(landed(List.of(), false), "log4j");

        assertTrue(note.contains("merged without discussion"), note);
    }

    @Test
    @DisplayName("the review is kept with who said it and where in the diff")
    void remarksKeepTheirAuthorAndLine() {
        String note = Contribution.noteFor(
                landed(List.of(new Contribution.Remark("rgoers", "2026-07-05", "A.java:42", "Is one needed?")), false),
                "log4j");

        assertTrue(note.contains("**rgoers**"), note);
        assertTrue(note.contains("A.java:42"), note);
        assertTrue(note.contains("Is one needed?"), note);
    }

    @Test
    @DisplayName("co-authorship is recorded, never quietly upgraded to sole authorship")
    void coAuthorshipIsStated() {
        assertTrue(Contribution.noteFor(landed(List.of(), true), "log4j").contains("role: co-author"));
        assertFalse(Contribution.noteFor(landed(List.of(), false), "log4j").contains("role: co-author"));
    }

    @Test
    @DisplayName("the same change files to the same name twice")
    void filingIsStable() {
        assertEquals(Contributions.nameFor(landed(List.of(), false)), Contributions.nameFor(landed(List.of(), false)));
        assertTrue(Contributions.nameFor(landed(List.of(), false)).startsWith("2026-07-06-pr-4171-"));
    }
}
