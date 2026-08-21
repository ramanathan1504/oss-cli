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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code oss run} answering with nothing attached and no pack.
 *
 * <p>The asymmetry this closes: {@code memory}/{@code kb} has been built in — an archive extension
 * takes over when one is registered, and the core answers when none is — while {@code run}/
 * {@code bench} had no built-in at all. Typed on a fresh install it printed
 * <em>"error no pack in …"</em>, which is the tool refusing to do anything until the user has
 * written a file in a format they have not read yet.
 *
 * <p>Typed through the real command tree, because the bug was never in the runner: it was in what
 * the dispatcher did with a verb before the runner ever saw it.
 */
class RunBuiltinTest {

    @BeforeAll
    static void safeHome() {
        String base = AppPaths.BASE_DIR.toString();
        assertTrue(
                base.contains("target") || base.contains("test"),
                "REFUSING TO RUN: base directory is " + base + ", which looks like a real store.");
    }

    @Test
    @DisplayName("oss run, typed bare, names the built-in verbs before it mentions a pack")
    void bareRunNamesTheBuiltin() {
        Cli.Result r = Cli.run("run");

        assertTrue(r.ok(), "asking what is available is a question, not a mistake: " + r.all());
        for (String verb : com.osscli.runner.BuiltinRunner.VERBS) {
            assertTrue(r.says("oss run " + verb), "oss run should list `" + verb + "`:\n" + r.all());
        }
    }

    @Test
    @DisplayName("oss run detect answers in a directory with no pack at all")
    void detectNeedsNoPack(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("go.mod"), "module example\n");

        Cli.Result r = Cli.run("run", "detect", dir.toString());

        // This is the whole point. Before, every verb here ended at "error no pack in <cwd>".
        assertTrue(r.ok(), "detect must not need a pack: " + r.all());
        assertTrue(r.says("go"), "and must name what it found:\n" + r.all());
        assertTrue(r.says("go.mod"), "with the file that proves it:\n" + r.all());
    }

    @Test
    @DisplayName("oss run doctor reports rather than fails when there is nothing set up")
    void doctorIsAWarningOnAFreshDirectory(@TempDir Path dir) {
        Cli.Result r = Cli.run("run", "doctor", dir.toString());

        assertEquals(0, r.exitCode(), "a health check must not fail because nothing is configured: " + r.all());
        assertTrue(r.says("pack"), "the pack is one of the questions:\n" + r.all());
    }

    @Test
    @DisplayName("oss run init writes a pack, and the second run refuses to overwrite it")
    void initThenRefuse(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        Cli.Result first = Cli.run("run", "init", dir.toString());
        assertTrue(first.ok(), "init should write a starter pack: " + first.all());
        assertTrue(Files.isRegularFile(dir.resolve("pack.md")), "pack.md was not written");

        Cli.Result second = Cli.run("run", "init", dir.toString());
        assertEquals(2, second.exitCode(), "a pack that exists must stop init: " + second.all());
    }

    @Test
    @DisplayName("a verb that is neither the core's nor a pack's still reaches the engine's own error")
    void unknownVerbStillGoesToThePack(@TempDir Path dir) {
        // The dispatch rule has to keep working in both directions: `matrix` is the engine's, so it
        // must not be swallowed by the built-in just because no pack is present here.
        Cli.Result r = Cli.run("run", "--pack", dir.toString(), "matrix");

        // Only the exit code, on purpose: the engine is a child process with inherited streams, so
        // what it prints goes to the terminal's own stderr and never through the swapped one here.
        // Asserting on its text would pass for the wrong reason -- the string is simply absent.
        assertTrue(r.exitCode() != 0, "no pack in an empty directory: " + r.all());
    }
}
