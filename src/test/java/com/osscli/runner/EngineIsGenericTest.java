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
 * That the matrix engine works for a pack it has never seen.
 *
 * <p>The engine's design is that it does not know what it is walking: it forks JVMs and walks a
 * version x config x app matrix, and everything project-specific lives in the pack. That was true
 * of the architecture and not of the file. Until 4.0 it defaulted to {@code packs/log4j/pack.sh},
 * swept {@code core-java} and {@code xml/baseline-console} when told nothing, built
 * {@code apps/core-java} before any Gradle app, exempted versions beginning "3." from one
 * project's module rule, and accepted {@code --log4j} as a spelling of {@code --version}.
 *
 * <p>None of that is visible from reading a pack, and none of it failed a test, because the only
 * pack anybody ran had all those names in it. So this class writes the smallest legal pack -- the
 * four required declarations and {@code pack_module_path}, which is exactly what the documentation
 * tells people to write -- and runs the real script against it.
 */
class EngineIsGenericTest {

    private static final Path ENGINE = Path.of("runner", "engine.sh");

    private static boolean unavailable() {
        return !Files.isRegularFile(ENGINE)
                || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** The smallest pack the documentation says will load: four declarations and one function. */
    private static void minimalPack(Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.sh"), """
                PACK_NAME="orders"
                VERSIONS=(3.6.0 3.7.0)
                DEFAULT_VERSION=3.7.0
                APPS=(consumer)

                pack_module_path() {
                  case "$1" in
                    consumer) echo "apps/consumer" ;;
                    *)        return 1 ;;
                  esac
                }
                """);
    }

    private record Ran(int code, String out, String err) {}

    private static Ran run(Path packDir, String... argv) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(
                List.of("bash", ENGINE.toAbsolutePath().toString()));
        cmd.addAll(List.of(argv));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(packDir.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Ran(p.waitFor(), out, err);
    }

    @Test
    @DisplayName("a pack that defines only what is required loads and lists")
    void theSmallestLegalPackWorks(@TempDir Path pack) throws Exception {
        Assumptions.assumeFalse(unavailable());
        minimalPack(pack);

        Ran r = run(pack, "list");

        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("consumer"), r.out());
        assertTrue(r.out().contains("3.7.0"), r.out());
    }

    @Test
    @DisplayName("the optional hooks are optional — no 'command not found' on any stream")
    void optionalHooksAreOptional(@TempDir Path pack) throws Exception {
        Assumptions.assumeFalse(unavailable());
        minimalPack(pack);

        // A sweep is what calls them: five hooks, once per cell. A pack that left them out got
        // five `command not found` lines for every cell it swept.
        Ran r = run(pack, "matrix", "--scenario", "startup");

        assertFalse(r.err().contains("command not found"), r.err());
        assertFalse(r.out().contains("command not found"), r.out());
    }

    @Test
    @DisplayName("a pack with no configs still produces cells")
    void noConfigsIsStillAMatrix(@TempDir Path pack) throws Exception {
        Assumptions.assumeFalse(unavailable());
        minimalPack(pack);

        Ran r = run(pack, "matrix", "--scenario", "startup");

        // Two versions, one app, no configs. The cells must exist; whether they pass depends on
        // whether the module builds, which this pack has no source for.
        assertFalse(r.out().contains("0 pass, 0 fail, 0 skip"), "an empty sweep reads exactly like a clean one");
        assertTrue(r.out().contains("consumer"), r.out());
    }

    @Test
    @DisplayName("a sweep that produced no cells fails instead of reporting a clean run")
    void anEmptySweepIsNotAPass(@TempDir Path pack) throws Exception {
        Assumptions.assumeFalse(unavailable());
        // A pack that declares the axis and leaves it empty. The required-declaration check only
        // asks whether APPS exists, so this loads -- and then swept nothing, printed
        // "0 pass, 0 fail, 0 skip" and exited 0, which is the shape of a clean run.
        Files.writeString(pack.resolve("pack.sh"), """
                PACK_NAME="orders"
                VERSIONS=(3.7.0)
                DEFAULT_VERSION=3.7.0
                APPS=()

                pack_module_path() { return 1; }
                """);

        Ran r = run(pack, "matrix");

        assertNotEquals(0, r.code(), "0 pass, 0 fail, 0 skip and exit 0 is the shape of a clean sweep");
        assertTrue(r.err().contains("no cells"), r.err());
        assertFalse(r.err().contains("unbound variable"), "an empty axis is a pack mistake, not a crash: " + r.err());
    }

    @Test
    @DisplayName("a pack pointing at a module that is not there is told so, not shown a Maven stack")
    void aMissingModuleIsNamed(@TempDir Path pack) throws Exception {
        Assumptions.assumeFalse(unavailable());
        minimalPack(pack);

        Ran r = run(pack, "run", "consumer");

        assertNotEquals(0, r.code());
        assertTrue(r.err().contains("apps/consumer"), r.err());
        assertTrue(r.err().contains("pack_module_path"), "the message must name what decides it: " + r.err());
    }

    @Test
    @DisplayName("--log4j is gone; a flag named after one project has no place in this engine")
    void theAliasIsRemoved() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(ENGINE));
        String src = Files.readString(ENGINE);

        assertFalse(src.contains("--version|--log4j"), "the alias is still being accepted");
        assertFalse(src.contains("--log4j)"), "the alias is still being accepted");
        assertTrue(src.contains("--version)"), "--version is the spelling that remains");
    }

    @Test
    @DisplayName("no default anywhere is the name of something out of one pack")
    void noPackSpecificDefaults() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(ENGINE));
        // Comments may still explain what a line USED to say -- that is why the hook exists. Code
        // may not.
        String code = Files.readString(ENGINE)
                .lines()
                .filter(l -> !l.strip().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        for (String baked : List.of("core-java", "xml/baseline-console", "packs/log4j", "log4j")) {
            assertFalse(code.contains(baked), "the engine still hardcodes '" + baked + "'");
        }
    }
}
