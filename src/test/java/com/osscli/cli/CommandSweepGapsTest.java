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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.memory.BuiltinMemory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gaps that only appeared when every command was actually run.
 *
 * <p>Each of these shipped, passed a full unit suite, and failed the first time somebody typed the
 * command. They have nothing in common except that: a format string counted wrong, a directory
 * happened to contain a binary, a shell script was handed no argument, a number was an issue rather
 * than a pull request, an extension declared five verbs instead of six.
 *
 * <p>None of them could have been found by reading the code, and all of them were found in one
 * afternoon by running it.
 */
class CommandSweepGapsTest {

    // ==========================================
    // analyze: a format string that counted wrong
    // ==========================================

    @Test
    @DisplayName("the severity prompt has an argument for every placeholder")
    void analyzePromptIsWellFormed() throws IOException {
        // oss analyze died on the FIRST issue, every run, with
        // java.util.MissingFormatArgumentException: Format specifier '%s'.
        // The template names the repository, the title and the body; only two were passed. The
        // command was completely unusable and nothing in the suite noticed, because nothing ran it.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/AnalyzeCommand.java"));
        int open = src.indexOf("You are an expert maintainer for the '%s'");
        int close = src.indexOf("\"\"\",", open);
        int args = src.indexOf(");", close);

        long placeholders =
                src.substring(open, close).chars().filter(c -> c == '%').count();
        String argList = src.substring(close, args);
        long supplied = argList.chars().filter(c -> c == ',').count(); // one comma per argument

        assertEquals(placeholders, supplied, "each %s needs an argument: " + argList.strip());
    }

    @Test
    @DisplayName("an issue with no body does not become a literal null in the prompt")
    void analyzeHandlesAnEmptyBody() throws IOException {
        // GitHub issues are routinely filed with an empty body. "Issue Body: null" is a worse
        // prompt than "Issue Body: (no description)", and costs nothing to avoid.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/AnalyzeCommand.java"));
        assertTrue(src.contains("(no description)"), "a null body should be described, not printed as null");
    }

    // ==========================================
    // alias: a directory that contains binaries
    // ==========================================

    @Test
    @DisplayName("a binary sharing the alias directory does not break the listing")
    void aliasListingSurvivesABinary(@TempDir Path dir) throws IOException {
        // ~/.local/bin is where people keep executables. Files.readString throws
        // MalformedInputException on the first byte that is not UTF-8, so `oss alias --list`
        // answered "error  Input length = 1" -- a complaint about a byte, on a command about
        // names, caused by a file that has nothing to do with it. Reproduced here exactly.
        Files.write(dir.resolve("some-binary"), new byte[] {0x7f, 'E', 'L', 'F', (byte) 0xff, (byte) 0xfe, 0x00, 0x01});
        Files.writeString(
                dir.resolve("a-shim"), "#!/bin/sh\n# created by `oss alias` — safe to delete\nexec oss \"$@\"\n");

        assertDoesNotThrow(() -> {
            for (Path p : Files.list(dir).toList()) {
                readLeniently(p);
            }
        });
    }

    /** The rule AliasCommand uses: bytes, decoded leniently, never an exception. */
    private static String readLeniently(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 64 * 1024) {
                return "";
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    @Test
    @DisplayName("the listing reads bytes, not a String, so it cannot throw on encoding")
    void aliasUsesTheSafeRead() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/AliasCommand.java"));

        assertTrue(src.contains("carriesMarker"), "both the listing and the removal should share one guarded read");
        assertFalse(
                src.contains("Files.readString(p).contains(MARKER)"),
                "readString on an arbitrary file in ~/.local/bin is the bug that was fixed");
    }

    // ==========================================
    // backlog: a shell script handed nothing
    // ==========================================

    @Test
    @DisplayName("bare backlog uses the configured repository instead of leaking shell usage")
    void backlogFallsBackToTheDefaultRepository() throws IOException {
        // `oss backlog` printed the underlying script's usage -- naming a positional OWNER/REPO
        // this command does not document, and env tunables no reader of `oss backlog --help` has
        // ever seen. Every other command falls back to default.repository when none is named.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/BacklogCommand.java"));

        assertTrue(src.contains("default.repository"), "backlog should honour the configured default");
        assertTrue(src.contains("which repository?"), "and say so plainly when there is not one");
    }

    // ==========================================
    // pr / issue: a 404 is an answer, not a null
    // ==========================================

    @Test
    @DisplayName("asking for a pull request that is an issue is explained, not thrown")
    void prExplainsAMissingPullRequest() throws IOException {
        // getJson returns null for a 404 -- deliberately, since absent is not an error at that
        // layer. Handing that to readTree produced `argument "content" is null`: a Jackson
        // complaint about a parameter, in answer to a question about a pull request.
        //
        // Issues and pull requests share one numbering sequence on GitHub, so asking `oss pr` for
        // an issue number is an ordinary mistake rather than a rare one.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/PrCommand.java"));

        assertTrue(src.contains("has no pull request #"), "say which number, and that it is not a PR");
        assertTrue(src.contains("one numbering sequence"), "and why the mistake is easy to make");
        assertTrue(src.contains("oss issue "), "and what to run instead");
    }

    @Test
    @DisplayName("issue does the same for a number that does not exist")
    void issueExplainsAMissingIssue() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/IssueCommand.java"));
        assertTrue(src.contains("has no issue #"), "a 404 should be a sentence about the issue");
    }

    // ==========================================
    // memory: a command the tool advertises then refuses
    // ==========================================

    @Test
    @DisplayName("the built-in memory knows which verbs it can answer")
    void builtinMemoryDeclaresItsVerbs() {
        assertTrue(BuiltinMemory.supports("search"), "search is the verb the file hint suggests");
        assertTrue(BuiltinMemory.supports("file"));
        assertTrue(BuiltinMemory.supports("index"));
        // harvest used to belong to an archive extension, and this asserted that it did. It is
        // built in now, because installing oss-cli has to be enough: the half of the corpus that is
        // your own record of your own work cannot require a second repository to collect.
        assertTrue(BuiltinMemory.supports("harvest"), "your own work is collectable with nothing attached");
        // digest is built in too now. map counts which notes mention a topic; digest reads them and
        // says what was worked out -- and that needs no archive extension, only notes with a shape.
        assertTrue(BuiltinMemory.supports("digest"), "reading your notes should not require an extension");
        assertTrue(BuiltinMemory.supports("import"), "an export is the only route in for a chat product");
    }

    @Test
    @DisplayName("an attached archive missing a verb costs the verb's richer form, not the verb")
    void memoryFallsBackRatherThanRefusing() throws IOException {
        // `oss memory file` ends by printing `oss memory search "<terms>"` as the next step. With
        // an archive attached that declared file, index, harvest, map, digest and doctor -- but
        // not search -- the suggestion was refused. The tool advertised a command and then
        // rejected it, which is worse than not offering it at all.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/ExtCommand.java"));

        assertTrue(src.contains("whenExtensionCannot"), "there must be a path for a verb the extension lacks");
        assertTrue(src.contains("does not do \\\"" + "\" + verb"), "and it should name the verb it could not do");
        assertTrue(
                src.contains("BuiltinMemory.supports(verb)"),
                "falling back only for verbs the built-in can actually answer");
    }
}
