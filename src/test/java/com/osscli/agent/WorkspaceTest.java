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
package com.osscli.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model picks the paths, so the paths are where the danger is.
 *
 * <p>It does not take malice: a model that has just read a stack trace naming a home directory will
 * ask for a home directory. The rule has to hold against what a helpful model does, not only
 * against what an attacker would.
 */
class WorkspaceTest {

    @Test
    @DisplayName("a file inside the workspace resolves")
    void insideIsAllowed(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/Foo.java"), "class Foo {}");
        Workspace w = new Workspace(dir);

        assertTrue(w.resolve("src/main/Foo.java").isPresent());
        assertTrue(w.resolve("./src/main/Foo.java").isPresent());
        assertTrue(w.resolve("src/../src/main/Foo.java").isPresent(), "a .. that stays inside is fine");
    }

    @Test
    @DisplayName("climbing out is refused, however it is spelled")
    void escapesAreRefused(@TempDir Path dir) {
        Workspace w = new Workspace(dir);

        assertFalse(w.resolve("../secrets.txt").isPresent());
        assertFalse(w.resolve("../../etc/passwd").isPresent());
        assertFalse(w.resolve("src/../../etc/passwd").isPresent(), "the classic, and why matching on '..' fails");
        assertFalse(w.resolve("/etc/passwd").isPresent(), "an absolute path outside the root");
        assertFalse(w.resolve(System.getProperty("user.home") + "/.ssh/id_rsa").isPresent());
    }

    @Test
    @DisplayName("a filename containing dots is not an escape")
    void dotsInNamesAreFine(@TempDir Path dir) throws IOException {
        // The inverse mistake: refusing anything containing "..". Foo..Bar.java is a legal filename
        // and blocking it would be a rule that annoys everybody and stops nobody.
        Files.writeString(dir.resolve("Foo..Bar.java"), "x");
        Workspace w = new Workspace(dir);

        assertTrue(w.resolve("Foo..Bar.java").isPresent());
    }

    @Test
    @DisplayName("a symlink pointing out is the same escape in different clothes")
    void symlinksAreFollowedBeforeTheComparison(@TempDir Path dir) throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "not yours");
        Workspace w = new Workspace(dir);
        try {
            Files.createSymbolicLink(dir.resolve("link.txt"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // Windows without developer mode cannot make one; the rule is unchanged.
        }

        assertFalse(w.resolve("link.txt").isPresent(), "resolved through the link, it is outside");
    }

    @Test
    @DisplayName("nothing at all is refused rather than treated as the root")
    void emptyIsRefused(@TempDir Path dir) {
        Workspace w = new Workspace(dir);

        assertFalse(w.resolve(null).isPresent());
        assertFalse(w.resolve("").isPresent());
        assertFalse(w.resolve("   ").isPresent());
    }

    @Test
    @DisplayName("paths read back relative, so a reply is portable")
    void displayIsRelative(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.java"), "x");
        Workspace w = new Workspace(dir);

        Path resolved = w.resolve("src/A.java").orElseThrow();
        assertEquals("src" + java.io.File.separator + "A.java", w.display(resolved));
    }
}
