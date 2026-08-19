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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack as data: read, not run.
 *
 * <p>The old format was a bash file the engine sourced, so "point oss at this pack" and "run this
 * person's shell script" were the same sentence. These pin the two properties that buys: a pack
 * says when it applies, and nothing it says reaches a shell unquoted.
 */
class PackFileTest {

    private static final String LOG4J = """
            {
              "name": "log4j",
              "description": "Log4j across a matrix",
              "useWhen": { "repository": "apache/logging-log4j2", "files": ["log4j-core/pom.xml"] },
              "versions": ["2.24.1", "2.26.1"],
              "defaultVersion": "2.26.1",
              "apps": ["core-java", "db"],
              "modulePath": "apps/{app}"
            }
            """;

    @Test
    @DisplayName("a pack.json is found and read")
    void jsonIsRead(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), LOG4J);

        PackFile pack = PackFile.find(dir).orElseThrow();

        assertEquals("log4j", pack.name());
        assertEquals("Log4j across a matrix", pack.description());
    }

    @Test
    @DisplayName("a pack.md carries the same object, so one file can explain itself to both readers")
    void markdownIsRead(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.md"), "# My pack\n\nWhat it is.\n\n```json\n" + LOG4J + "\n```\n");

        assertEquals("log4j", PackFile.find(dir).orElseThrow().name());
    }

    @Test
    @DisplayName("no pack file is not an error, because most directories are not packs")
    void absenceIsEmpty(@TempDir Path dir) throws IOException {
        assertEquals(Optional.empty(), PackFile.find(dir));
    }

    @Test
    @DisplayName("a pack missing what it needs says which field, at load")
    void incompleteIsRefusedByName(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), "{\"name\":\"mine\"}");

        // Named at load rather than somewhere deep in a sweep, which is the whole reason the old
        // format listed its five variables at the top of the file.
        IOException e = assertThrows(IOException.class, () -> PackFile.find(dir));
        assertTrue(e.getMessage().contains("apps"), e.getMessage());
    }

    @Test
    @DisplayName("a pack says when it applies, and one that does not claims nothing")
    void applicabilityIsDeclared(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), LOG4J);
        PackFile pack = PackFile.find(dir).orElseThrow();

        assertTrue(pack.appliesTo("apache/logging-log4j2", dir));
        assertTrue(pack.appliesTo("APACHE/LOGGING-LOG4J2", dir), "a repository name is not case sensitive");
        assertFalse(pack.appliesTo("apache/kafka", dir));

        // By file, for a project the repository name does not identify.
        Files.createDirectories(dir.resolve("log4j-core"));
        Files.writeString(dir.resolve("log4j-core/pom.xml"), "<project/>");
        assertTrue(pack.appliesTo("someone/fork", dir));
    }

    @Test
    @DisplayName("a pack with no useWhen answers false, rather than claiming everything")
    void silenceIsNotAClaim(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), "{\"name\":\"mine\",\"apps\":[\"a\"]}");

        // One pack in a folder of them becoming the answer to every question is how a wrong pack
        // gets used without anyone choosing it.
        assertFalse(PackFile.find(dir).orElseThrow().appliesTo("anyone/anything", dir));
    }

    @Test
    @DisplayName("what the engine reads is the pack, rendered")
    void renderingMatchesTheEnginesShape(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), LOG4J);

        String shell = PackFile.find(dir).orElseThrow().toShell();

        assertTrue(shell.contains("PACK_NAME='log4j'"), shell);
        assertTrue(shell.contains("VERSIONS=('2.24.1' '2.26.1')"), shell);
        assertTrue(shell.contains("APPS=('core-java' 'db')"), shell);
        assertTrue(shell.contains("DEFAULT_VERSION='2.26.1'"), shell);
        assertTrue(shell.contains("pack_module_path()"), shell);
    }

    @Test
    @DisplayName("nothing a pack says reaches the shell unquoted")
    void valuesCannotEscape(@TempDir Path dir) throws IOException {
        // A pack is data all the way through, or it is data until somebody puts a backtick in a
        // version number. This is the difference between reading a pack and running one.
        Files.writeString(
                dir.resolve("pack.json"),
                "{\"name\":\"x'; touch /tmp/pwned; '\",\"apps\":[\"a$(whoami)\"],\"versions\":[\"`id`\"]}");

        String shell = PackFile.find(dir).orElseThrow().toShell();

        assertFalse(shell.contains("PACK_NAME='x'; touch"), "a quote closed the assignment: " + shell);
        assertTrue(shell.contains("'\\''"), "the quote should have been escaped: " + shell);
        // $(...) and `...` are inert inside single quotes, so they may appear as text.
        assertTrue(shell.contains("APPS=('a$(whoami)')"), shell);
    }
}
