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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Attach a tool of your own, then call it: {@code ext add} then {@code run --name}.
 *
 * <p>This is the whole extension contract, and it is two commands that have never been tested
 * together. {@code ext add} has tests for parsing a manifest. {@code run} has tests for dispatch.
 * Between them sits the thing a reader is actually promised -- "a directory with one JSON file, run
 * as a child process, so it can be written in anything" -- and nothing checked that the file
 * registered by the first command is the program executed by the second.
 */
class ExtensionJourneyTest {

    private static boolean noShell() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** A runner extension in the shape the documentation describes: one manifest, one program. */
    private static void anExtension(Path dir) throws Exception {
        Files.writeString(dir.resolve("oss-ext.json"), """
                {
                  "name": "orders-bench",
                  "kind": "runner",
                  "supports": "owner/name",
                  "exec": "./bench.sh",
                  "verbs": { "list": "list", "run": "run" }
                }
                """);
        Path exec = dir.resolve("bench.sh");
        Files.writeString(exec, """
                #!/bin/sh
                echo "orders-bench here, verb=$1"
                """);
        exec.toFile().setExecutable(true);
    }

    @Test
    @DisplayName("a manifest that registers is a program that runs")
    void addThenRun(@TempDir Path home, @TempDir Path ext) throws Exception {
        Assumptions.assumeFalse(noShell(), "the extension's exec is a shell script");
        anExtension(ext);

        Journey.Ran added = Journey.oss(home, ext, "ext", "add", ext.toString());
        assertEquals(0, added.code(), added.all());
        assertTrue(added.all().contains("orders-bench"), added.all());

        // It must be visible to the command that lists what is attached, not only to the one that
        // attached it -- those read the same registry file and could disagree about it.
        Journey.Ran listed = Journey.oss(home, ext, "ext", "list");
        assertTrue(listed.all().contains("orders-bench"), listed.all());

        // And the program itself must actually run. This is the step nothing owned: the manifest
        // parsed, the registry held it, and whether `exec` was ever invoked was untested.
        Journey.Ran ran = Journey.oss(home, ext, "run", "--name", "orders-bench", "list");
        assertTrue(ran.all().contains("orders-bench here"), "the extension's own program never ran: " + ran.all());
    }

    @Test
    @DisplayName("removing it takes the verbs away too")
    void removeThenRun(@TempDir Path home, @TempDir Path ext) throws Exception {
        Assumptions.assumeFalse(noShell());
        anExtension(ext);
        Journey.oss(home, ext, "ext", "add", ext.toString());

        Journey.Ran removed = Journey.oss(home, ext, "ext", "remove", "orders-bench");
        assertEquals(0, removed.code(), removed.all());

        // A registry that forgets an extension while the runner still dispatches to it is the
        // failure worth checking: the two halves must agree about what is attached.
        Journey.Ran ran = Journey.oss(home, ext, "run", "--name", "orders-bench", "list");
        assertNotEquals(0, ran.code(), "a removed extension still ran: " + ran.all());
        assertFalse(ran.all().contains("orders-bench here"), ran.all());
    }

    @Test
    @DisplayName("a manifest pointing at a program that is not there is refused, not registered")
    void aBrokenManifestIsRefused(@TempDir Path home, @TempDir Path ext) throws Exception {
        Assumptions.assumeFalse(noShell());
        Files.writeString(ext.resolve("oss-ext.json"), """
                { "name": "ghost", "kind": "runner", "exec": "./not-here.sh", "verbs": { "list": "list" } }
                """);

        Journey.Ran added = Journey.oss(home, ext, "ext", "add", ext.toString());

        // Registering it and failing at call time moves the error away from the decision that
        // caused it, which is the shape of every bug that takes an afternoon.
        assertNotEquals(0, added.code(), "an extension whose exec does not exist was accepted: " + added.all());
    }
}
