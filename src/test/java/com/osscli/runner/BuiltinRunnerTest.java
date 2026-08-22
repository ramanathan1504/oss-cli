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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.memory.BuiltinMemory.Check;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner that answers with nothing attached.
 *
 * <p>Written against real directories rather than mocks, because the whole class is about reading
 * files that are actually there: a detector tested against a fake filesystem agrees with whatever
 * the fake was told, which is how "179 readable files imported as 0" got through a green suite.
 */
class BuiltinRunnerTest {

    // ==========================================
    // Detection
    // ==========================================

    @Test
    @DisplayName("a pom.xml is a Maven project, and the wrapper beats the PATH")
    void detectsMaven(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        Project p = Project.detect(dir).orElseThrow();
        assertEquals(Project.Tool.MAVEN, p.tool());
        assertEquals("pom.xml", p.evidence());
        assertTrue(p.testCommand().contains("test"), "maven tests with `test`: " + p.testCommand());

        Path wrapper = dir.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\n");
        // Windows has no execute bit, so setExecutable returns false there and this asserted a
        // property of the filesystem rather than of the detector. A wrapper IS the POSIX
        // ./mvnw case; the Windows equivalent is mvnw.cmd, which needs no bit at all.
        boolean posix = !System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        if (!posix) {
            return;
        }
        assertTrue(wrapper.toFile().setExecutable(true), "could not make the wrapper executable");

        // The wrapper pins a version; `mvn` is whatever this machine happens to have. A project
        // that ships one means that one.
        assertTrue(
                Project.detect(dir).orElseThrow().launcher().endsWith("mvnw"),
                "the checkout's own wrapper must win over the PATH");
    }

    @Test
    @DisplayName("a package.json declares its own commands, and missing ones are not invented")
    void readsNpmScripts(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), "{\"scripts\": {\"test\": \"jest\"}}");

        Project p = Project.detect(dir).orElseThrow();
        assertEquals(Project.Tool.NODE, p.tool());
        assertFalse(p.testCommand().isEmpty(), "the test script is declared");
        // `npm run build` with no build script exits 1 with "Missing script", which reads as a
        // broken project rather than as a project that does not build that way.
        assertTrue(p.buildCommand().isEmpty(), "no build script means no build command");
    }

    @Test
    @DisplayName("a Makefile offers only the targets it actually declares")
    void readsMakeTargets(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Makefile"), "test:\n\techo hi\n\nCFLAGS := -O2\n");

        Project p = Project.detect(dir).orElseThrow();
        assertEquals(Project.Tool.MAKE, p.tool());
        assertFalse(p.testCommand().isEmpty(), "test: is a target");
        assertTrue(p.buildCommand().isEmpty(), "build: is not");
    }

    @Test
    @DisplayName("every ecosystem is recognised, and both of a repository's build systems are reported")
    void detectsTheRest(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("go.mod"), "module example\n");
        assertEquals(Project.Tool.GO, Project.detect(dir).orElseThrow().tool());

        Files.writeString(dir.resolve("Cargo.toml"), "[package]\n");
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\n");
        Files.writeString(dir.resolve("build.gradle"), "plugins {}\n");
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        List<Project.Tool> tools =
                Project.detectAll(dir).stream().map(Project::tool).toList();
        // Maven first, because everything that runs uses the first and a reader has to be able to
        // tell which one that is. The rest are still listed: a repository carrying two build
        // systems must not look like one the tool half-read.
        assertEquals(Project.Tool.MAVEN, tools.get(0));
        assertTrue(tools.containsAll(List.of(Project.Tool.GRADLE, Project.Tool.PYTHON, Project.Tool.GO)), "" + tools);
    }

    @Test
    @DisplayName("an empty directory declares nothing, and that is an answer rather than an error")
    void emptyDirectoryIsNotAFailure(@TempDir Path dir) {
        assertEquals(Optional.empty(), Project.detect(dir));
        assertEquals(0, BuiltinRunner.run("detect", List.of(dir.toString())), "detect explains, it does not fail");
    }

    // ==========================================
    // init
    // ==========================================

    @Test
    @DisplayName("init writes a pack the pack reader can read back")
    void initWritesAReadablePack(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(dir.resolve("apps").resolve("hello"));

        assertEquals(0, BuiltinRunner.run("init", List.of(dir.toString())));

        Path written = dir.resolve("pack.md");
        assertTrue(Files.isRegularFile(written), "init must write pack.md");

        // The point of generating it: the thing that ships must parse with the reader that ships.
        // A starter file the tool cannot read back would be a five-minute detour into the source.
        PackFile pack = PackFile.find(dir).orElseThrow();
        assertEquals(dir.getFileName().toString(), pack.name());
        assertTrue(pack.apps().contains("hello"), "the app under apps/ should be listed: " + pack.apps());
    }

    @Test
    @DisplayName("init refuses to overwrite a pack somebody wrote")
    void initNeverOverwrites(@TempDir Path dir) throws IOException {
        String mine = "{\"name\": \"mine\", \"apps\": [\"a\"]}";
        Files.writeString(dir.resolve("pack.json"), mine);

        assertNotEquals(0, BuiltinRunner.run("init", List.of(dir.toString())), "an existing pack must stop init");
        assertEquals(mine, Files.readString(dir.resolve("pack.json")), "and must be left exactly as it was");
    }

    @Test
    @DisplayName("init reads the repository out of .git/config rather than asking for it")
    void initFindsTheOriginRepository(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(
                dir.resolve(".git").resolve("config"),
                "[remote \"origin\"]\n\turl = git@github.com:owner/name.git\n",
                StandardCharsets.UTF_8);

        assertEquals("owner/name", BuiltinRunner.originRepository(dir));

        Files.writeString(
                dir.resolve(".git").resolve("config"),
                "[remote \"origin\"]\n\turl = https://github.com/owner/name\n",
                StandardCharsets.UTF_8);
        assertEquals("owner/name", BuiltinRunner.originRepository(dir), "https remotes count too");
    }

    // ==========================================
    // build and test
    // ==========================================

    @Test
    @DisplayName("nothing to build is refused by name, not attempted")
    void refusesWhenThereIsNothingToBuild(@TempDir Path dir) {
        assertEquals(2, BuiltinRunner.run("build", List.of(dir.toString())));
        assertEquals(2, BuiltinRunner.run("test", List.of(dir.toString())));
    }

    @Test
    @DisplayName("a project that declares no test command says so instead of guessing one")
    void refusesWhenTheProjectDeclaresNoCommand(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), "{\"name\": \"x\"}");

        assertEquals(2, BuiltinRunner.run("test", List.of(dir.toString())), "no test script: refuse, do not invent");
    }

    // ==========================================
    // doctor
    // ==========================================

    @Test
    @DisplayName("a directory with no pack is a warning, never a failure")
    void doctorOnAFreshDirectory(@TempDir Path dir) {
        List<Check> checks = BuiltinRunner.health(dir);

        assertFalse(checks.isEmpty(), "doctor must check something");
        assertTrue(
                checks.stream().noneMatch(c -> c.status() == Check.Status.FAIL),
                "a fresh directory has nothing broken in it: " + checks);
        assertTrue(checks.stream().anyMatch(c -> c.name().equals("pack")), "the pack is one of the questions");
        assertEquals(0, BuiltinRunner.run("doctor", List.of(dir.toString())), "and it exits 0");
    }

    @Test
    @DisplayName("a pack that will not parse is the one thing doctor calls broken")
    void doctorFailsOnAnUnreadablePack(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), "{ this is not json");

        List<Check> checks = BuiltinRunner.health(dir);
        assertTrue(
                checks.stream().anyMatch(c -> c.name().equals("pack") && c.status() == Check.Status.FAIL),
                "an invalid pack fails every matrix verb, and today only says so mid-run: " + checks);
    }

    // ==========================================
    // The verb list
    // ==========================================

    @Test
    @DisplayName("an unknown verb lists what there is, and no built-in verb shadows the engine")
    void unknownVerbAndNoCollisions() {
        assertEquals(2, BuiltinRunner.run("nonsense", List.of()));
        for (String verb : BuiltinRunner.VERBS) {
            assertTrue(BuiltinRunner.supports(verb), verb + " is listed but not supported");
        }
        // The dispatch rule is "a built-in verb is handled in the core, everything else goes to the
        // pack". A name in both places would make that rule ambiguous in the one direction the
        // user cannot see, so the collision is asserted against rather than remembered.
        for (String engineVerb :
                List.of("list", "run", "matrix", "coverage", "repro", "pr", "review", "hub", "clean")) {
            assertFalse(BuiltinRunner.supports(engineVerb), engineVerb + " belongs to the engine and is now shadowed");
        }
    }
}
