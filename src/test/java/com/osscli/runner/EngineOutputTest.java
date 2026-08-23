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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the matrix engine writes when nobody is watching.
 *
 * <p>The engine is the half of this tool whose output actually gets redirected -- it runs builds,
 * so it runs in CI, in cron, and behind {@code > build.log}. It emitted its colour unconditionally
 * from fourteen places, which put raw control bytes into every one of those. The Java half has been
 * careful about exactly this for releases, and {@code Live}'s own comment explains why; this file
 * had never been asked the question.
 *
 * <p>Run as a process rather than reasoned about, because the property under test <em>is</em> what
 * arrives in a pipe. A test that read the source looking for escape sequences would pass on a
 * script that printed them through a variable, and fail on one that named an escape it never used.
 */
class EngineOutputTest {

    private static final Path ENGINE = Path.of("runner", "engine.sh");

    /** The byte every ANSI sequence starts with. Nothing here needs to know the rest. */
    private static final String ESC = "\u001b";

    private static boolean unavailable() {
        return !Files.isRegularFile(ENGINE)
                || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String stderrOf(Path workingDir, boolean noColor, String... argv)
            throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(
                List.of("bash", ENGINE.toAbsolutePath().toString()));
        cmd.addAll(List.of(argv));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir.toFile());
        if (noColor) {
            pb.environment().put("NO_COLOR", "1");
        }
        // The whole point: both streams are pipes, which is what a redirected run looks like.
        Process p = pb.start();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        p.getInputStream().readAllBytes();
        p.waitFor();
        return err;
    }

    @Test
    @DisplayName("an error into a pipe carries no escape sequences")
    void noEscapesWhenRedirected(@TempDir Path empty) throws Exception {
        Assumptions.assumeFalse(unavailable(), "the engine is POSIX shell, and says so on Windows");
        // An empty directory has no pack, which is the shortest path to the engine's own error --
        // and it was the first line anybody ever saw with a raw escape in it.
        String err = stderrOf(empty, false, "list");

        assertTrue(err.contains("no pack in"), "the error itself must still be there: " + err);
        assertFalse(err.contains(ESC), "raw ANSI in a redirected run");
    }

    @Test
    @DisplayName("NO_COLOR is honoured, the way the rest of the tool honours it")
    void noColorIsHonoured(@TempDir Path empty) throws Exception {
        Assumptions.assumeFalse(unavailable());
        String err = stderrOf(empty, true, "list");

        assertFalse(err.contains(ESC), "NO_COLOR was set and colour was written anyway");
    }

    @Test
    @DisplayName("the help it ships names nobody's project and no home directory")
    void theShippedHelpIsGeneric() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(ENGINE));
        String src = Files.readString(ENGINE);
        // Only the header block, which is what `oss run help` prints. The body may legitimately
        // name a flag alias it has to go on accepting.
        String header = src.substring(0, src.indexOf("set -euo pipefail"));

        assertFalse(header.contains("~/apache"), "the shipped help offered a path from one person's machine");
        assertFalse(
                header.toLowerCase(Locale.ROOT).contains("workout"),
                "a worked example naming somebody's repository reads as 'this tool is for that project'");
        assertTrue(header.contains("--version"), "the generic spelling is the one to teach");
    }
}
