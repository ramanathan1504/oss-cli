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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pack written as data answers what a pack written as a program answered.
 *
 * <p>Every assertion here <b>runs</b> the generated shell rather than reading it. Asserting on
 * generated text proves the renderer emitted what the test expected it to emit, which is the same
 * belief twice; the question is what bash does with it, and bash is available.
 *
 * <p>That distinction is not theoretical here. The first version of this renderer quoted its
 * patterns -- {@code [[ "$4" == '3.*' ]]} -- which reads correctly and is a literal comparison, so
 * every version rule in every generated pack silently never fired. A pack that skips no cells looks
 * exactly like a pack with nothing to skip. Reading the output did not show it; running it did.
 */
class PackHooksAreDataTest {

    /** One pack using every hook that has a declarative form. */
    private static final String PACK = """
            {
              "name": "demo",
              "description": "every hook, as data",
              "apps": ["core", "db", "nosql", "web", "old"],
              "appsNewestMajorCannotBuild": ["old", "web"],
              "versions": ["2.24.0", "3.0.0"],
              "defaultVersion": "2.24.0",
              "modulePathFor": { "nosql": "apps/db" },
              "mainClass": "org.example.{app}.Main",
              "minJavaFor": { "old": 8, "*": 17 },
              "minVersionFor": { "nosql": "2.25.0" },
              "requiresConfigFor": { "db": "appender-jdbc" },
              "requiresAppFor": { "*/appender-network": "web" },
              "interactiveApps": ["web"],
              "skipWhen": [
                { "version": "3.*", "appIn": "appsNewestMajorCannotBuild", "reason": "{app} has no 3.x release path" },
                { "version": "3.*", "javaBelow": 17, "reason": "3.x requires Java 17+" },
                { "version": "3.*", "config": "properties/*", "reason": "properties format removed in 3.x" }
              ],
              "buildFlags": ["-Dlib.version={version}"],
              "buildFlagsWhen": [ { "version": "3.*", "flags": ["-P3x"] } ],
              "alwaysJvmArgs": ["-Dscript.enable=groovy"],
              "jvmArgsFor": { "web": ["-Dselftest=true"] },
              "configArgs": [
                { "config": "*/legacy/*", "args": ["-Dcompat=true"] },
                { "version": "3.*", "args": ["-Dconfiguration.location={config}"] },
                { "args": ["-DconfigurationFile={config}"] }
              ],
              "configArgsAlso": [
                { "app": "web*", "configNot": "*/legacy/*", "args": ["-Dlogging.config={config}"] }
              ],
              "gradleVersionFlag": "-PlibVersion={version}",
              "upstreamRepo": { "env": "DEMO_UPSTREAM", "default": "someone/theirs" },
              "sourceClone": { "3x": "~/src/v3", "*": "~/src/v2" },
              "sourceCloneHint": "set DEMO_CLONE",
              "shell": "pack_modules_on_classpath() { sort -u; }\\n"
            }
            """;

    @Test
    @DisplayName("every hook answers what the hand-written shell answered")
    void hooksAnswer(@TempDir Path dir) throws Exception {
        assertEquals("", ask(dir, "pack_skip_reason core x 17 2.24.0"), "nothing to skip on 2.x");
        assertEquals("old has no 3.x release path", ask(dir, "pack_skip_reason old x 17 3.0.0"));
        assertEquals("3.x requires Java 17+", ask(dir, "pack_skip_reason core x 11 3.0.0"));
        assertEquals("properties format removed in 3.x", ask(dir, "pack_skip_reason core properties/a 17 3.0.0"));

        assertEquals("17", ask(dir, "pack_min_java_for core"), "the * key is the default");
        assertEquals("8", ask(dir, "pack_min_java_for old"));
        assertEquals("2.25.0", ask(dir, "pack_min_version_for nosql"));
        assertEquals("appender-jdbc", ask(dir, "pack_requires_config_for db"));
        assertEquals("", ask(dir, "pack_requires_config_for core"));
        assertEquals("apps/db", ask(dir, "pack_module_path nosql"));
        assertEquals("apps/core", ask(dir, "pack_module_path core"));
        assertEquals("org.example.db.Main", ask(dir, "pack_main_class_for db"));
        assertEquals("-PlibVersion=3.0.0", ask(dir, "pack_gradle_version_flag 3.0.0"));
        assertEquals("set DEMO_CLONE", ask(dir, "pack_source_clone_hint"));
        assertEquals("-Dscript.enable=groovy", ask(dir, "pack_always_jvm_args"));
        assertEquals("-Dselftest=true", ask(dir, "pack_jvm_args web"));
        assertEquals("", ask(dir, "pack_jvm_args core"));
        assertEquals("function", ask(dir, "type -t pack_modules_on_classpath"), "the shell escape hatch survives");
    }

    @Test
    @DisplayName("a pattern is still a pattern after quoting")
    void patternsGlob(@TempDir Path dir) throws Exception {
        // The bug this file exists for. Every one of these is a glob that a literal comparison
        // would answer "no" to, leaving a pack that skips nothing and looks healthy doing it.
        assertEquals("web", ask(dir, "pack_requires_app_for xml/appender-network"), "*/ prefix must glob");
        assertEquals("", ask(dir, "pack_requires_app_for xml/plain"));
        assertEquals("old has no 3.x release path", ask(dir, "pack_skip_reason old x 17 3.0.0"), "3.* must glob");
        assertEquals("", ask(dir, "pack_skip_reason old x 17 2.24.0"), "and must not match 2.24.0");
        assertEquals("-Dlib.version=3.0.0 -P3x", ask(dir, "pack_build_flags 3.0.0"));
        assertEquals("-Dlib.version=2.24.0", ask(dir, "pack_build_flags 2.24.0"));
    }

    @Test
    @DisplayName("exclusive rules stay exclusive, and additive rules stack on the winner")
    void chainsAreNotAllIfs(@TempDir Path dir) throws Exception {
        // Emitted as three separate ifs at first, which sent a 2.x property alongside the 3.x one.
        // Passing the wrong one does not error -- it falls back to a default configuration and
        // logs to the console, so a whole column passes while testing nothing.
        assertEquals("-Dcompat=true", ask(dir, "pack_config_args core /p/legacy/a.xml 3.0.0"));
        assertEquals("-Dconfiguration.location=/p/xml/a.xml", ask(dir, "pack_config_args core /p/xml/a.xml 3.0.0"));
        assertEquals("-DconfigurationFile=/p/xml/a.xml", ask(dir, "pack_config_args core /p/xml/a.xml 2.24.0"));

        assertEquals(
                "-DconfigurationFile=/p/xml/a.xml -Dlogging.config=/p/xml/a.xml",
                ask(dir, "pack_config_args web /p/xml/a.xml 2.24.0"),
                "the additive rule goes on top of whichever branch won");
        assertEquals(
                "-Dcompat=true",
                ask(dir, "pack_config_args web /p/legacy/a.xml 2.24.0"),
                "and its own condition still excludes it");
    }

    @Test
    @DisplayName("a value the person running it may override, and nothing more")
    void environmentIsNamedNotInterpolated(@TempDir Path dir) throws Exception {
        assertEquals("someone/theirs", ask(dir, "pack_upstream_repo"));
        assertEquals("a/b", ask(dir, "DEMO_UPSTREAM=a/b pack_upstream_repo"));
        assertEquals(System.getenv("HOME") + "/src/v3", ask(dir, "pack_source_clone 3x"), "~/ expands");
    }

    @Test
    @DisplayName("a hostile value is still data")
    void valuesCannotEscape(@TempDir Path dir) throws Exception {
        String hostile = """
                {
                  "name": "x", "apps": ["a"], "versions": ["1"], "defaultVersion": "1",
                  "minJavaFor": { "a": "8'; touch /tmp/oss-pack-escape; echo '" },
                  "skipWhen": [ { "app": "*", "reason": "$(touch /tmp/oss-pack-escape2)`id`" } ]
                }
                """;
        Path packed = dir.resolve("pack.json");
        Files.writeString(packed, hostile);
        String shell = PackFile.find(dir).orElseThrow().toShell();
        Files.writeString(dir.resolve("p.sh"), shell);
        run(dir, "pack_min_java_for a; echo; pack_skip_reason a b 17 1");
        assertTrue(Files.notExists(Path.of("/tmp/oss-pack-escape")), "a value ran as a command");
        assertTrue(Files.notExists(Path.of("/tmp/oss-pack-escape2")), "a reason ran as a command");
    }

    /** Source the generated pack and ask it one thing, as bash sees it. */
    private String ask(Path dir, String call) throws IOException, InterruptedException {
        if (Files.notExists(dir.resolve("p.sh"))) {
            Files.writeString(dir.resolve("pack.json"), PACK);
            Files.writeString(
                    dir.resolve("p.sh"), PackFile.find(dir).orElseThrow().toShell());
        }
        return run(dir, call).trim().replace("\n", " ");
    }

    private String run(Path dir, String call) throws IOException, InterruptedException {
        // _pack_in_list is the engine's, not the pack's: a rule that quoted the list it asks
        // about would be a second copy of that list, and the copy is the one that goes stale.
        String script = "_pack_in_list() { local n=\"$1\"; shift; local c;"
                + " for c in \"$@\"; do [[ \"$c\" == \"$n\" ]] && return 0; done; return 1; }\n"
                + ". " + dir.resolve("p.sh") + "\n"
                + call + "\n";
        Path runner = dir.resolve("run.sh");
        Files.writeString(runner, script);
        ProcessBuilder pb = new ProcessBuilder("bash", runner.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }
}
