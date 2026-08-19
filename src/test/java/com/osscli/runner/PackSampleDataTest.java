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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real pack, end to end.
 *
 * <p>The other tests take the format apart. This one puts it together: the actual shape of a pack
 * somebody maintains — eighteen applications, four version lines, apps the newest major cannot
 * build, a module layout — written as the file a person would write, rendered, sourced by bash, and
 * read back.
 *
 * <p>It exists because unit tests of a format agree with the format. The question this answers is
 * whether the whole path holds for something the size of a real pack, which is where an off-by-one
 * in an array or a template that works for {@code a} and not for {@code spring-boot-maven} shows up.
 */
class PackSampleDataTest {

    /** The log4j pack, as a pack file rather than as the bash it used to be. */
    private static final String REAL_PACK = """
            {
              "name": "log4j",
              "description": "Apache Log4j across a version x config x app matrix, on real JVMs",
              "useWhen": {
                "repository": ["apache/logging-log4j2", "ramanathan1504/logging-log4j2"],
                "files": ["log4j-core/pom.xml"]
              },
              "versions": ["2.24.1", "2.25.5", "2.26.0", "2.26.1", "3.0.0-beta3"],
              "defaultVersion": "2.26.1",
              "apps": [
                "core-java", "db", "network", "custom-plugins", "jms", "jpa", "smtp",
                "spring-boot-maven", "spring-boot-gradle", "spring-cloud-config",
                "bridges-in", "bridges-out", "bridges-to-jul", "log4j1-bridge",
                "jakarta-web", "javax-web", "java8-baseline", "jdbc-jndi"
              ],
              "appsNewestMajorCannotBuild": ["log4j1-bridge", "javax-web"],
              "appsDir": "apps",
              "configsDir": "configs",
              "modulePath": "apps/{app}"
            }
            """;

    @Test
    @DisplayName("a real pack reads back as itself")
    void theWholeThingParses(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pack.json"), REAL_PACK);

        PackFile pack = PackFile.find(dir).orElseThrow();

        assertEquals("log4j", pack.name());
        assertTrue(pack.description().contains("real JVMs"));
        assertTrue(pack.appliesTo("apache/logging-log4j2", dir), "the repository it is for");
        assertTrue(pack.appliesTo("ramanathan1504/logging-log4j2", dir), "a fork, listed second");
        assertTrue(pack.appliesTo(null, dir) == false, "no repository and no marker file is not a match");
    }

    @Test
    @DisplayName("the same pack written as markdown behaves identically")
    void markdownAndJsonAgree(@TempDir Path jsonDir, @TempDir Path mdDir) throws IOException {
        Files.writeString(jsonDir.resolve("pack.json"), REAL_PACK);
        Files.writeString(mdDir.resolve("pack.md"), """
                # The log4j pack

                What it runs, and why. The block below is the same object the tool reads.

                ```json
                %s
                ```
                """.formatted(REAL_PACK));

        // One rendering, two files. If these ever differ, one of the two readers is doing
        // something of its own -- and the whole reason pack.md exists is that the prose and the
        // declaration cannot drift.
        assertEquals(
                PackFile.find(jsonDir).orElseThrow().toShell(),
                PackFile.find(mdDir).orElseThrow().toShell());
    }

    @Test
    @DisplayName("every declared value survives a real bash, including the awkward ones")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the rendering is sourced by bash")
    void everythingSurvivesTheShell(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pack.json"), REAL_PACK);
        String shell = PackFile.find(dir).orElseThrow().toShell();

        Map<String, String> read = sourceAndReport(
                shell,
                List.of(
                        "PACK_NAME",
                        "PACK_DESC",
                        "DEFAULT_VERSION",
                        "PACK_APPS_DIR",
                        "PACK_CONFIGS_DIR",
                        "${#APPS[@]}",
                        "${#VERSIONS[@]}",
                        "${#APPS_2X_ONLY[@]}",
                        "${APPS[0]}",
                        "${APPS[17]}",
                        "${VERSIONS[4]}",
                        "$(pack_module_path spring-boot-maven)"));

        assertEquals("log4j", read.get("PACK_NAME"));
        assertEquals("2.26.1", read.get("DEFAULT_VERSION"));
        assertEquals("18", read.get("${#APPS[@]}"), "all eighteen applications");
        assertEquals("5", read.get("${#VERSIONS[@]}"));
        assertEquals("2", read.get("${#APPS_2X_ONLY[@]}"));
        assertEquals("core-java", read.get("${APPS[0]}"));
        assertEquals("jdbc-jndi", read.get("${APPS[17]}"), "the last app, where an off-by-one would land");
        assertEquals("3.0.0-beta3", read.get("${VERSIONS[4]}"), "a version that is not a plain number");
        // The template against a name with hyphens, which is most real app names.
        assertEquals("apps/spring-boot-maven", read.get("$(pack_module_path spring-boot-maven)"));
    }

    @Test
    @DisplayName("a pack that declares no optional fields still renders something the engine can source")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the rendering is sourced by bash")
    void theMinimalPackWorks(@TempDir Path dir) throws Exception {
        // Name and apps are the only required fields. Everything else has to have a default that
        // is correct rather than merely present, or the smallest possible pack is a broken one.
        Files.writeString(dir.resolve("pack.json"), "{\"name\":\"tiny\",\"apps\":[\"only\"]}");

        Map<String, String> read = sourceAndReport(
                PackFile.find(dir).orElseThrow().toShell(),
                List.of(
                        "PACK_NAME",
                        "PACK_APPS_DIR",
                        "PACK_CONFIGS_DIR",
                        "${#VERSIONS[@]}",
                        "$(pack_module_path only)"));

        assertEquals("tiny", read.get("PACK_NAME"));
        assertEquals("apps", read.get("PACK_APPS_DIR"));
        assertEquals("configs", read.get("PACK_CONFIGS_DIR"));
        assertEquals("0", read.get("${#VERSIONS[@]}"));
        assertEquals("apps/only", read.get("$(pack_module_path only)"));
    }

    @Test
    @DisplayName("a pack whose apps live somewhere else says so once, and the template follows")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the rendering is sourced by bash")
    void anUnusualLayout(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("pack.json"),
                "{\"name\":\"nested\",\"apps\":[\"web\"],\"appsDir\":\"src/apps\","
                        + "\"modulePath\":\"src/apps/{app}/module\"}");

        Map<String, String> read = sourceAndReport(
                PackFile.find(dir).orElseThrow().toShell(), List.of("PACK_APPS_DIR", "$(pack_module_path web)"));

        assertEquals("src/apps", read.get("PACK_APPS_DIR"));
        assertEquals("src/apps/web/module", read.get("$(pack_module_path web)"));
    }

    /** Source a rendering and print back a list of expressions, so shell decides what they mean. */
    private static Map<String, String> sourceAndReport(String shell, List<String> expressions) throws Exception {
        StringBuilder script = new StringBuilder(shell);
        for (String e : expressions) {
            String value = e.startsWith("$") ? e : "$" + e;
            script.append("\nprintf '%s\\n' \"").append(value).append("\"");
        }
        Path file = Files.createTempFile("sample-", ".sh");
        Files.writeString(file, script.toString());
        ProcessBuilder pb = new ProcessBuilder(List.of("bash", file.toString()));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        Files.deleteIfExists(file);
        assertTrue(err.isBlank(), "the rendering did not source cleanly: " + err);

        String[] lines = out.split("\n", -1);
        Map<String, String> read = new java.util.LinkedHashMap<>();
        for (int i = 0; i < expressions.size(); i++) {
            read.put(expressions.get(i), i < lines.length ? lines[i] : "");
        }
        return read;
    }
}
