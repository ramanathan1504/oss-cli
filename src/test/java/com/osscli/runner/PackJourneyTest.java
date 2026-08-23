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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journey a stranger takes: init, list, run. All three, in order, on a real project.
 *
 * <p>Every other test in this package checks one step. That is how a pack could be written by
 * {@code oss run init}, load, list its applications, sweep a matrix, and be unable to START any of
 * them -- the declarative format had no field for a main class, so {@code oss run run} handed the
 * JVM an empty argument and died with "Could not find or load main class" and nothing after it.
 *
 * <p>Nothing failed. `init` passed its test, `list` passed its test, the parser passed its tests,
 * and the product did not work. The step nobody tested was the one between them.
 *
 * <p>So this builds an actual Maven project, runs the actual commands in the actual order, and
 * asserts on the application's own stdout. It is slower than every other test here and it is the
 * only one that would have caught this.
 */
class PackJourneyTest {

    private record Ran(int code, String out, String err) {}

    /**
     * Render the pack the way the product does, then run the real engine over it.
     *
     * <p>The first version of this shelled out to the packaged jar, so it could only run AFTER
     * `package` -- during `mvn verify` it found no jar, quietly assumed itself away, and reported
     * as SKIPPED. A test that skips in CI is a test that does not exist, and this one exists
     * precisely because a step nobody tested had been broken for every user. It skipped on its
     * first real run, which is how that was caught.
     *
     * <p>So it uses the two real halves directly: {@link PackFile} renders the declarative pack
     * into shell exactly as {@code Engine} does, and {@code engine.sh} is handed it through
     * {@code OSS_PACK_FILE}, exactly as {@code Engine} hands it over. No jar, same join.
     */
    private static Path render(Path root) throws Exception {
        PackFile pack = PackFile.find(root).orElseThrow(() -> new IllegalStateException("no pack in " + root));
        Path rendered = Files.createTempFile("oss-pack-", ".sh");
        rendered.toFile().deleteOnExit();
        Files.writeString(rendered, pack.toShell());
        return rendered;
    }

    private static Ran run(Path dir, Path home, String... argv) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                "bash", Path.of("runner", "engine.sh").toAbsolutePath().toString()));
        cmd.addAll(java.util.List.of(argv));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.environment().put("OSS_CLI_HOME", home.toString());
        pb.environment().put("OSS_PACK_FILE", render(dir).toAbsolutePath().toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new Ran(p.waitFor(), out, err);
    }

    /** A real, tiny, buildable Maven project — a parent and one application with a main method. */
    private static void aRealProject(Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>orders</artifactId><version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules><module>apps/consumer</module></modules>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);
        Path app = root.resolve("apps/consumer");
        Files.createDirectories(app.resolve("src/main/java/com/example/consumer"));
        Files.writeString(app.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>orders</artifactId><version>1.0.0</version>
                    <relativePath>../../pom.xml</relativePath></parent>
                  <artifactId>consumer</artifactId>
                </project>
                """);
        Files.writeString(app.resolve("src/main/java/com/example/consumer/Main.java"), """
                package com.example.consumer;
                public class Main {
                  public static void main(String[] a) { System.out.println("consumer started"); }
                }
                """);
    }

    /** The pack `oss run init` writes, with the main class it now offers. */
    private static void aPackLikeInitWrites(Path root) throws Exception {
        Files.writeString(root.resolve("pack.md"), """
                # orders — pack

                ```json
                {
                  "name": "orders",
                  "description": "orders across a version x config x app matrix (maven)",
                  "useWhen": { "repository": "owner/name", "files": ["pom.xml"] },
                  "versions": ["1.0.0"],
                  "defaultVersion": "1.0.0",
                  "apps": ["consumer"],
                  "appsDir": "apps",
                  "configsDir": "configs",
                  "modulePath": "apps/{app}",
                  "mainClass": "com.example.{app}.Main"
                }
                ```
                """);
    }

    private static boolean cannotRun() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return true; // the engine is POSIX shell and says so
        }
        if (!Files.isRegularFile(Path.of("runner", "engine.sh"))) {
            return true; // no engine beside this checkout
        }
        try {
            return new ProcessBuilder("mvn", "-v").start().waitFor() != 0;
        } catch (Exception e) {
            return true; // no Maven on this machine; the journey needs a real build
        }
    }

    @Test
    @DisplayName("a pack written the documented way can list its apps AND start one")
    void listAndRun(@TempDir Path root, @TempDir Path home) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(cannotRun(), "needs bash and Maven");
        aRealProject(root);
        aPackLikeInitWrites(root);

        Ran listed = run(root, home, "list");
        assertTrue(listed.out().contains("consumer"), listed.out() + listed.err());

        // The step that was missing. Not "did it build" -- did the application's own code run.
        Ran ran = run(root, home, "run", "consumer");
        assertTrue(
                ran.out().contains("consumer started") || ran.err().contains("consumer started"),
                "the app never ran.\nout: " + ran.out() + "\nerr: " + ran.err());
    }

    @Test
    @DisplayName("a pack with no main class is told what to add, not shown a JVM error")
    void noMainClassIsExplained(@TempDir Path root, @TempDir Path home) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(cannotRun(), "needs bash and Maven");
        aRealProject(root);
        // The same pack with mainClass removed -- which is every pack written before the field
        // existed, and what `oss run init` used to produce.
        Files.writeString(root.resolve("pack.md"), """
                ```json
                {
                  "name": "orders",
                  "versions": ["1.0.0"],
                  "defaultVersion": "1.0.0",
                  "apps": ["consumer"],
                  "modulePath": "apps/{app}"
                }
                ```
                """);

        Ran ran = run(root, home, "run", "consumer");

        assertFalse(
                ran.err().contains("Could not find or load main class"),
                "the JVM was handed an empty class name again: " + ran.err());
        assertTrue(ran.err().contains("does not say how to start"), ran.err());
        assertTrue(ran.err().contains("mainClass"), "the message must name the field to add: " + ran.err());
    }
}
