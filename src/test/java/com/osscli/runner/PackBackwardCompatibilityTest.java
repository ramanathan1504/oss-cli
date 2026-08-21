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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a pack written before the format changed still runs.
 *
 * <p>Every pack in existence when {@code pack.json} arrived was a {@code pack.sh}, and they belong
 * to people who did not ask for a new format. A migration nobody can postpone is a migration that
 * breaks somebody's Tuesday, so the old shape keeps working and keeps winning where both exist --
 * a directory holding both is one mid-migration, and the script is the one that has been tested.
 *
 * <p>These drive the real {@code engine.sh} rather than asserting about it. The selection logic is
 * fifteen lines of shell that decides which file to source, and the first version of the
 * declarative branch set the variable and was silently overwritten two lines later: a bug no test
 * about Java could have seen, because Java was not the part that was wrong.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the engine is POSIX shell; oss says so itself on Windows")
class PackBackwardCompatibilityTest {

    /** A pack.sh in the shape the engine has always sourced. */
    private static void legacyPack(Path dir, String name) throws IOException {
        Files.writeString(dir.resolve("pack.sh"), """
                PACK_NAME='%s'
                PACK_DESC='written before pack.json existed'
                PACK_CONFIGS_DIR='configs'
                PACK_APPS_DIR='apps'
                VERSIONS=('1.0.0' '2.0.0')
                DEFAULT_VERSION='2.0.0'
                APPS=('alpha' 'beta')
                APPS_2X_ONLY=()
                pack_module_path() { printf '%%s' "apps/$1"; }
                """.formatted(name));
    }

    /**
     * Source a pack the way the engine does and report what it ended up with.
     *
     * <p>The selection block is extracted from the engine itself rather than restated here: a test
     * carrying its own copy of the rule agrees with itself whatever the engine does.
     */
    private static Map<String, String> select(Path root, Map<String, String> env) throws Exception {
        Path engine = Path.of("runner/engine.sh");
        assertTrue(Files.isRegularFile(engine), "engine.sh is not where this test expects it");

        String script = """
                set -euo pipefail
                ROOT="$1"; shift
                %s
                printf 'PACK_FILE=%%s\\n' "$PACK_FILE"
                printf 'BENCH_PACK=%%s\\n' "$BENCH_PACK"
                # Asked about the pack's OWN first app. Hardcoding a name here made the module
                # assertion measure the argument rather than the template: a pack declaring gamma
                # was asked for alpha and dutifully answered apps/alpha.
                [ -f "$PACK_FILE" ] && . "$PACK_FILE" && printf 'PACK_NAME=%%s\\nAPPS=%%s\\nMODULE=%%s\\n' \\
                    "$PACK_NAME" "${APPS[*]}" "$(pack_module_path "${APPS[0]}")"
                """.formatted(selectionBlock(engine));

        Path runner = Files.createTempFile("select-", ".sh");
        Files.writeString(runner, script);
        List<String> cmd = new ArrayList<>(List.of("bash", runner.toString(), root.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        Files.deleteIfExists(runner);

        Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                parsed.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return parsed;
    }

    /** The engine's own pack-selection lines, lifted out so the test cannot drift from them. */
    private static String selectionBlock(Path engine) throws IOException {
        String text = Files.readString(engine, StandardCharsets.UTF_8);
        int from = text.indexOf("BENCH_PACK=\"${BENCH_PACK:-}\"");
        assertTrue(from > 0, "engine.sh no longer has the selection block this test drives");
        int to = text.indexOf("[[ -f $PACK_FILE ]]", from);
        assertTrue(to > from, "engine.sh no longer guards a missing pack where this test expects");
        return text.substring(from, to);
    }

    @Test
    @DisplayName("a pack.sh at the root is still found and sourced")
    void legacyPackStillLoads(@TempDir Path dir) throws Exception {
        legacyPack(dir, "legacy");

        Map<String, String> got = select(dir, Map.of());

        assertEquals(dir.resolve("pack.sh").toString(), got.get("PACK_FILE"));
        assertEquals("legacy", got.get("PACK_NAME"));
        assertEquals("alpha beta", got.get("APPS"));
        assertEquals("apps/alpha", got.get("MODULE"));
    }

    @Test
    @DisplayName("BENCH_PACK still selects a pack under packs/<name>/")
    void legacyMultiPackLayoutStillWorks(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("packs/mine");
        Files.createDirectories(nested);
        legacyPack(nested, "mine");

        Map<String, String> got = select(dir, Map.of("BENCH_PACK", "mine"));

        assertEquals(nested.resolve("pack.sh").toString(), got.get("PACK_FILE"));
        assertEquals("mine", got.get("PACK_NAME"));
    }

    @Test
    @DisplayName("a rendered pack is used when one is handed over")
    void renderedPackIsHonoured(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pack.json"), "{\"name\":\"declared\",\"apps\":[\"gamma\"]}");
        Path rendered = dir.resolve("rendered.sh");
        Files.writeString(rendered, PackFile.find(dir).orElseThrow().toShell());

        Map<String, String> got = select(dir, Map.of("OSS_PACK_FILE", rendered.toString()));

        assertEquals(rendered.toString(), got.get("PACK_FILE"));
        assertEquals("declared", got.get("PACK_NAME"));
        assertEquals("gamma", got.get("APPS"));
        assertEquals("apps/gamma", got.get("MODULE"));
    }

    @Test
    @DisplayName("a handed-over pack is not overwritten by the selection that follows it")
    void theRenderedBranchWins(@TempDir Path dir) throws Exception {
        // The bug the first draft had: the declarative branch set PACK_FILE and the BENCH_PACK
        // selection two lines later replaced it, so the pack was ignored rather than refused --
        // and BENCH_PACK being set is exactly when that happened.
        legacyPack(dir, "onDisk");
        Path rendered = dir.resolve("rendered.sh");
        Files.writeString(
                rendered, "PACK_NAME='handedOver'\nAPPS=('x')\npack_module_path() { printf '%s' \"apps/$1\"; }\n");

        Map<String, String> got = select(dir, Map.of("OSS_PACK_FILE", rendered.toString(), "BENCH_PACK", "mine"));

        assertEquals("handedOver", got.get("PACK_NAME"), "the rendered pack lost to the selection below it");
    }

    @Test
    @DisplayName("pack.sh wins over pack.json, because it is the one that has been tested")
    void theScriptWinsWhileBothExist(@TempDir Path dir) throws Exception {
        legacyPack(dir, "fromScript");
        Files.writeString(dir.resolve("pack.json"), "{\"name\":\"fromJson\",\"apps\":[\"zzz\"]}");

        // Engine.run only renders when there is no pack.sh, so the engine never sees OSS_PACK_FILE
        // for this directory. Asserted at the Java boundary, which is where that decision is made.
        assertTrue(Files.isRegularFile(dir.resolve("pack.sh")));
        assertTrue(PackFile.find(dir).isPresent(), "the json is readable; it is simply not preferred");

        Map<String, String> got = select(dir, Map.of());
        assertEquals("fromScript", got.get("PACK_NAME"));
    }

    @Test
    @DisplayName("a directory that is not a pack is still refused, not silently defaulted")
    void anEmptyDirectoryIsStillRefused(@TempDir Path dir) throws Exception {
        Map<String, String> got = select(dir, Map.of());

        // The engine falls back to packs/log4j/pack.sh, which does not exist here. What matters is
        // that nothing was sourced -- a pack that silently sweeps nothing is the failure the
        // engine's own guard was written for.
        assertFalse(got.containsKey("PACK_NAME"), "something was sourced from a directory with no pack");
    }
}
