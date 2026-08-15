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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Commands run as typed, through the real entry point.
 *
 * <p>Every bug found in the command sweep had passed a full unit suite. A format string with three
 * placeholders and two arguments reads correctly. So does {@code Files.readString} on a directory
 * that happens to contain a binary, and a shell script handed no argument, and a {@code readTree}
 * of a body that is null only on a 404. None of them were visible in the source; all of them were
 * obvious the first time the command was typed.
 *
 * <p>So these type them. {@link Cli} drives the same {@link RootCommand} and the same
 * picocli configuration that {@code Main} builds, and asserts on the exit code and on what reached
 * the screen — which is the whole of what a user gets.
 *
 * <p>Confined to commands that need no network and no model: those two are the province of
 * {@code RetryBehaviourTest} and a live run. What is checked here is the part that was breaking —
 * parsing, wiring, refusals, and the sentences a user is left holding.
 */
class EndToEndCommandTest {

    @BeforeAll
    static void safeHome() {
        // The build points OSS_CLI_HOME at target/test-home. Asserted rather than assumed, because
        // these run real commands and one of them writes.
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store.");
    }

    // ==========================================
    // The tree itself
    // ==========================================

    @Test
    @DisplayName("the root command lists its subcommands and exits cleanly")
    void helpWorks() {
        Cli.Result r = Cli.run("--help");

        assertTrue(r.ok(), "--help should exit 0, got " + r.exitCode());
        for (String expected : new String[] {"sync", "review", "search", "chat", "history", "doctor"}) {
            assertTrue(r.says(expected), "--help should list " + expected + ":\n" + r.all());
        }
    }

    @ParameterizedTest(name = "oss {0} --help")
    @ValueSource(
            strings = {
                "sync",
                "review",
                "search",
                "prompt",
                "chat",
                "history",
                "doctor",
                "issue",
                "pr",
                "analyze",
                "backlog",
                "alias",
                "memory",
                "run",
                "model",
                "backup",
                "triage",
                "critical",
                "duplicates",
                "hub",
                "followup"
            })
    @DisplayName("every command can describe itself")
    void everyCommandHasHelp(String command) {
        // A command whose --help throws is one nobody can discover. Cheap to check, and it walks
        // the whole option model of each command rather than only the ones a test remembered.
        Cli.Result r = Cli.run(command, "--help");

        assertTrue(r.ok(), "oss " + command + " --help exited " + r.exitCode() + ":\n" + r.all());
        assertFalse(r.all().isBlank(), "oss " + command + " --help printed nothing");
    }

    @Test
    @DisplayName("an unknown command is refused, not ignored")
    void unknownCommandFails() {
        Cli.Result r = Cli.run("definitely-not-a-command");

        assertNotEquals(0, r.exitCode(), "an unknown command must not report success");
        assertTrue(r.says("Unknown") || r.says("Unmatched"), "and should say so:\n" + r.all());
    }

    // ==========================================
    // The gaps the sweep found
    // ==========================================

    @Test
    @DisplayName("alias --list survives a directory containing a binary")
    void aliasListDoesNotThrowOnBinaries(@org.junit.jupiter.api.io.TempDir Path bin) throws IOException {
        // Answered "error  Input length = 1" on any machine with a binary in ~/.local/bin, which
        // is what that directory is for. The message named a byte in answer to a question about
        // names, and nothing in the suite ran the command.
        //
        // The directory is planted rather than borrowed. The first version of this test read the
        // developer's real ~/.local/bin and PASSED with the bug reintroduced, because that machine
        // no longer happened to hold a file that triggered it. A test that depends on the machine
        // it runs on proves nothing on any other machine.
        Files.write(bin.resolve("a-binary"), new byte[] {0x7f, 'E', 'L', 'F', (byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        Files.writeString(
                bin.resolve("buddy"), "#!/bin/sh\n# created by `oss alias` — safe to delete\nexec oss \"$@\"\n");

        System.setProperty("oss.alias.bin", bin.toString());
        try {
            Cli.Result r = Cli.run("alias", "--list");

            assertTrue(r.ok(), "alias --list exited " + r.exitCode() + ":\n" + r.all());
            assertFalse(r.says("Input length"), "the encoding failure is back:\n" + r.all());
            assertTrue(r.says("buddy"), "the shim beside the binary should still be listed:\n" + r.all());
        } finally {
            System.clearProperty("oss.alias.bin");
        }
    }

    @Test
    @DisplayName("backlog with no repository explains itself instead of leaking shell usage")
    void backlogExplainsInsteadOfLeaking() {
        Cli.Result r = Cli.run("backlog");

        // Either it found a configured default and ran, or it asked for a repository in this
        // program's own words. What it must never do is print the underlying script's usage,
        // which names a positional this command does not document.
        assertFalse(r.says("OWNER/REPO"), "the shell script's usage leaked through:\n" + r.all());
        assertFalse(r.says("env tunables"), "the shell script's usage leaked through:\n" + r.all());
    }

    @Test
    @DisplayName("chat on an unsynced issue names a command that can actually fetch it")
    void chatAdviceLeadsSomewhere() {
        Cli.Result r = Cli.run("chat", "99999999", "-r", "owner/nothing-here");

        assertNotEquals(0, r.exitCode(), "an issue that is not local cannot be chatted about");
        if (r.says("not in the local data")) {
            assertTrue(r.says("oss issue"), "it must name the command that fetches a closed issue:\n" + r.all());
            assertFalse(
                    r.says("brings it down first"),
                    "the old advice loops -- sync fetches only open issues:\n" + r.all());
        }
    }

    @Test
    @DisplayName("memory search is answered rather than refused")
    void memorySearchIsAnswered() {
        // `oss memory file` prints `oss memory search` as its own next step. With an archive
        // attached that declares no search verb, that suggestion used to be rejected.
        Cli.Result r = Cli.run("memory", "search", "a phrase that matches nothing at all");

        assertFalse(r.says("does not offer the verb"), "a suggested command was refused:\n" + r.all());
    }

    // ==========================================
    // Writing
    // ==========================================

    @Test
    @DisplayName("doctor reports without needing anything configured")
    void doctorRuns() {
        Cli.Result r = Cli.run("doctor");

        // doctor exits non-zero when an OPTIONAL prerequisite is missing -- it is a report, not a
        // gate -- so the exit code is not the assertion. What matters is that it produced one.
        assertTrue(r.says("data directory"), "doctor should report the data directory:\n" + r.all());
        assertTrue(r.says("schema"), "and the schema version:\n" + r.all());
    }

    @Test
    @DisplayName("a command that writes writes under the test home, never near the real store")
    void writesStayInTheTestHome(@org.junit.jupiter.api.io.TempDir Path to) throws IOException {
        Cli.Result r = Cli.run("backup", "--to", to.toString());

        if (r.ok()) {
            try (var files = Files.list(to)) {
                assertTrue(files.findAny().isPresent(), "backup reported success and wrote nothing to " + to);
            }
        }
        // Whatever happened, it must not have touched the user's real directory.
        assertFalse(
                r.says(System.getProperty("user.home") + "/.oss-cli/data"),
                "a test wrote against the real store:\n" + r.all());
    }

    // ==========================================
    // That the harness is the real thing
    // ==========================================

    @Test
    @DisplayName("the harness configures the dispatchers exactly as Main does")
    void harnessMatchesMain() throws IOException {
        // If these drift, every test above is exercising a command tree that does not ship. The
        // dispatcher wiring is the part most easily forgotten, and the one whose absence produced
        // a real bug: `oss run list --apps` printed picocli's usage because --apps was claimed
        // here instead of reaching the extension.
        String main = Files.readString(Path.of("src/main/java/com/osscli/Main.java"));
        String harness = Files.readString(Path.of("src/test/java/com/osscli/Cli.java"));

        assertTrue(main.contains("setStopAtPositional(true)"), "Main should still configure the dispatchers");
        assertTrue(harness.contains("setStopAtPositional(true)"), "and the harness must do the same");
        assertTrue(
                harness.contains("List.of(\"run\", \"memory\")"),
                "for the same two dispatchers Main names, or the harness is a different program");

        // Bootstrap too. Skipping it is not a small difference: a probe that did so had eleven
        // commands leaking `no such table` from SQLite rather than answering, because the schema
        // Main guarantees had never been created.
        assertTrue(main.contains("AppPaths.bootstrap()"), "Main should still bootstrap paths first");
        assertTrue(harness.contains("AppPaths.bootstrap()"), "and the harness must do the same");
        assertTrue(main.contains("DatabaseManager.initializeSchema()"), "Main should still create the schema");
        assertTrue(harness.contains("initializeSchema()"), "and the harness must do the same");

        // Argument rewriting too. Main drops a pasted `#` comment before parsing; a harness that
        // parses the raw array is testing a program nobody runs.
        assertTrue(main.contains("withoutPastedComment(args)"), "Main should still strip a pasted comment");
        assertTrue(harness.contains("withoutPastedComment(args)"), "and the harness must do the same");
    }

    @Test
    @DisplayName("a dispatcher's own flags reach the extension, not picocli")
    void dispatcherPassthroughSurvives() {
        // The regression this wiring exists for. --apps is unknown to `oss run`; without
        // stopAtPositional picocli claims it and prints usage instead of dispatching.
        Cli.Result r = Cli.run("run", "list", "--apps");

        assertFalse(
                r.says("Unknown option: '--apps'"),
                "picocli claimed a flag that belongs to the extension:\n" + r.all());
    }

    @Test
    @DisplayName("exit codes come back, so a script can branch on them")
    void exitCodesArePropagated() {
        assertEquals(0, Cli.run("--help").exitCode(), "--help is a success");
        assertNotEquals(0, Cli.run("definitely-not-a-command").exitCode(), "an unknown command is not");
    }
}
