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
package com.osscli.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A pack is data, and stays data however hostile it is.
 *
 * <p>The whole argument for the format is that reading a pack cannot run anything. That claim is
 * only worth what it survives, so this hands the parser and the renderer the things a pack file
 * would contain if somebody wanted the opposite: quotes that close an assignment, command
 * substitution, newlines, null bytes, control characters, enormous strings, and a few thousand
 * random ones on top.
 *
 * <p>The property asserted is not "nothing bad happened" -- it is that whatever the pack said comes
 * back out as <em>one shell word</em>. That is checkable: the rendering is sourced by a real bash,
 * and the value it ends up with has to equal the value that went in.
 */
class PackFuzzTest {

    /** Deterministic. A fuzz test that finds a failure you cannot reproduce is a rumour. */
    private static final long SEED = 20260819L;

    private static Path packWith(Path dir, String name, String app) throws IOException {
        // Built through Jackson so the JSON itself is always valid: what is being fuzzed is the
        // content of the values, not whether the file parses. Hand-rolling the JSON would test the
        // parser's tolerance for broken input, which is a different test and is below.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.createObjectNode();
        node.put("name", name);
        node.set("apps", mapper.createArrayNode().add(app));
        Files.writeString(dir.resolve("pack.json"), mapper.writeValueAsString(node));
        return dir;
    }

    @ParameterizedTest
    @DisplayName("a hostile value survives as exactly one word, whatever it contains")
    @ValueSource(
            strings = {
                "x'; touch /tmp/oss-pwned; '",
                "$(whoami)",
                "`id`",
                "${HOME}",
                "a\"b",
                "a\\b",
                "two words",
                "semi;colon",
                "pipe|pipe",
                "amp&&amp",
                "new\nline",
                "tab\there",
                "* ? [a-z]",
                "--flag",
                "-",
                "…unicode ☕ 🌍",
                "'"
            })
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the rendering is sourced by bash")
    void hostileValuesStayOneWord(String hostile, @TempDir Path dir) throws Exception {
        packWith(dir, hostile, hostile);

        String shell = PackFile.find(dir).orElseThrow().toShell();
        String readBack = sourceAndEcho(shell, "PACK_NAME");

        // Equal, not merely "not dangerous". A pack that arrives mangled is a pack that runs the
        // wrong thing quietly, which is the same class of failure as one that runs something else.
        assertEquals(hostile, readBack, "the value changed on the way through the shell");
    }

    @Test
    @DisplayName("a few thousand random values, and none of them escapes")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the rendering is sourced by bash")
    void randomValuesStayInside(@TempDir Path dir) throws Exception {
        Random random = new Random(SEED);
        StringBuilder everything = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            String value = randomShellBait(random);
            packWith(dir, value, "app");
            String shell = PackFile.find(dir).orElseThrow().toShell();
            everything.append(shell);
            // One assertion in the loop, checked in bulk below: sourcing 2000 packs one at a time
            // is 2000 processes, and the property is textual.
            assertFalse(
                    shell.contains("\n" + "PACK_NAME='" + value + "'\nPACK_NAME"),
                    "a value produced a second assignment: " + value);
        }
        // Every quote in the output is either one this renderer opened, closed, or escaped. An
        // unbalanced count means some value ended its own assignment.
        long quotes = everything.chars().filter(c -> c == '\'').count();
        assertEquals(0, quotes % 2, "odd number of quotes across 2000 renderings");
    }

    private static String randomShellBait(Random random) {
        String alphabet = "abc'\"\\`$(){}[]|&;<>*?!#~ \t\n\r-_.:,=/@%^+ é☕";
        int length = random.nextInt(24);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    @ParameterizedTest
    @DisplayName("a malformed pack is a sentence, not a stack trace")
    @ValueSource(
            strings = {
                "",
                "   ",
                "not json at all",
                "{",
                "[]",
                "null",
                "{\"name\": 12}",
                "{\"apps\": \"not-a-list\"}",
                "{\"name\":\"x\"}",
            })
    void malformedPacksAreRefusedReadably(String content, @TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), content);

        try {
            PackFile pack = PackFile.find(dir).orElseThrow();
            // Some of these are valid JSON with the wrong shape -- accepted only if the required
            // fields are there, in which case reading them must still not throw.
            pack.toShell();
        } catch (IOException e) {
            assertTrue(
                    e.getMessage().contains("pack.json") || e.getMessage().contains("apps"),
                    "the refusal must name the file or the field: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("a very large pack is read rather than refused")
    void aLargePackIsFine(@TempDir Path dir) throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.createObjectNode();
        node.put("name", "big");
        var apps = mapper.createArrayNode();
        for (int i = 0; i < 5000; i++) {
            apps.add("app-" + i);
        }
        node.set("apps", apps);
        Files.writeString(dir.resolve("pack.json"), mapper.writeValueAsString(node));

        String shell = PackFile.find(dir).orElseThrow().toShell();

        assertTrue(shell.contains("'app-4999'"), "the last app is missing");
        assertTrue(shell.length() > 40_000, "suspiciously short rendering: " + shell.length());
    }

    @Test
    @DisplayName("a pack.md whose json block is broken says so about the block")
    void brokenMarkdownBlock(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.md"), "# Mine\n\nno block here\n");

        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> PackFile.find(dir));
        assertTrue(e.getMessage().contains("json"), e.getMessage());
    }

    /** Source a rendering in a real bash and print back one variable, byte for byte. */
    private static String sourceAndEcho(String shell, String variable) throws Exception {
        Path script = Files.createTempFile("fuzz-", ".sh");
        Files.writeString(script, shell + "\nprintf '%s' \"$" + variable + "\"\n");
        ProcessBuilder pb = new ProcessBuilder(List.of("bash", script.toString()));
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        Files.deleteIfExists(script);
        return out;
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(
            value = org.junit.jupiter.api.condition.OS.WINDOWS,
            disabledReason = "the matrix engine is POSIX shell; on Windows it reaches for WSL, which "
                    + "a runner does not have — the job answered \"Windows Subsystem for Linux has no "
                    + "installed distributions\" instead of running the pack. `oss run` says the same "
                    + "thing to a Windows user, so this is the documented behaviour, not a gap.")
    @DisplayName("an app that runs out of another app's module is expressible")
    void modulePathExceptionsAreNamed() throws Exception {
        // A real pack runs nineteen applications out of eighteen directories: "nosql" is exercised
        // through the "db" module and has no directory of its own. A single template can only send
        // it to apps/nosql, which is not there -- so that pack had to keep pack.sh entirely.
        String json = """
                {
                  "name": "p",
                  "versions": ["1.0.0"],
                  "apps": ["db", "nosql"],
                  "modulePath": "apps/{app}",
                  "modulePathFor": { "nosql": "apps/db" }
                }
                """;

        Path packDir = Files.createTempDirectory("packjson-");
        Files.writeString(packDir.resolve("pack.json"), json);
        String shell = PackFile.find(packDir).orElseThrow().toShell();

        assertTrue(shell.contains("pack_module_path()"), shell);
        assertEquals("apps/db", moduleFor(shell, "nosql"));
        assertEquals("apps/db", moduleFor(shell, "db"));
        // Everything unnamed still falls through to the template.
        assertEquals("apps/core-java", moduleFor(shell, "core-java"));
    }

    /** What the generated shell answers for one app, by running it. */
    private static String moduleFor(String shell, String app) throws Exception {
        Path dir = Files.createTempDirectory("packfn-");
        Path script = dir.resolve("p.sh");
        Files.writeString(script, shell + "\npack_module_path \"$1\"\n");
        Process p = new ProcessBuilder("bash", script.toString(), app)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        p.waitFor();
        return out;
    }
}
