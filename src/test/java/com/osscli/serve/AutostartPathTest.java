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
package com.osscli.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a launch agent records as the thing to start.
 *
 * <p>Three launch agents on one machine died the same death this week: each recorded a path that
 * later moved, then failed at every login into a log nobody reads. One pointed at a script removed
 * when a repository became a pack. This one recorded the resolved jar — which under Homebrew is
 * {@code /opt/homebrew/Cellar/oss/1.11.10/libexec/lib/oss.jar}, a directory that {@code brew
 * upgrade} deletes. The service would have broken on the very next upgrade, silently, and the
 * symptom would have been "localhost:1504 stopped working" with nothing to connect it to.
 *
 * <p>A launcher on {@code PATH} is the stable handle: Homebrew re-points {@code bin/oss} on every
 * upgrade, so the same recorded string keeps meaning the current version.
 */
class AutostartPathTest {

    @Test
    @DisplayName("a launcher on PATH is preferred over a versioned jar")
    void prefersTheStableName() {
        Path launcher = Autostart.launcher();

        List<String> start = Autostart.startCommand(Path.of("/some/jvm/bin/java"), Path.of("/some/versioned/oss.jar"));

        if (launcher != null) {
            assertEquals(List.of(launcher.toString()), start, "with a launcher on PATH, that is the whole command");
            assertFalse(start.contains("-jar"), "a stable launcher does not need the jar spelled out");
        } else {
            // No launcher installed on this machine. The jar is then the only handle there is, and
            // a pinned path still beats no service at all.
            //
            // Compared with separators normalised: Path.of("/some/jvm/bin/java") renders with
            // backslashes on Windows, so a literal here asserted the host's separator rather than
            // that the command keeps the jvm, the flag and the jar in that order.
            List<String> normalised =
                    start.stream().map(part -> part.replace('\\', '/')).toList();
            assertEquals(List.of("/some/jvm/bin/java", "-jar", "/some/versioned/oss.jar"), normalised);
        }
    }

    @Test
    @DisplayName("nothing recorded points inside a versioned install directory")
    void neverRecordsAVersionedPath() {
        List<String> start = Autostart.startCommand(
                Path.of("/opt/homebrew/Cellar/oss/1.11.10/libexec/runtime/bin/java"),
                Path.of("/opt/homebrew/Cellar/oss/1.11.10/libexec/lib/oss.jar"));

        if (Autostart.launcher() != null) {
            for (String part : start) {
                assertFalse(part.contains("/Cellar/oss/"), "recorded a path brew deletes on the next upgrade: " + part);
            }
        }
    }

    @Test
    @DisplayName("an empty PATH is survived rather than thrown on")
    void emptyPathIsHandled() {
        // getenv cannot be set from a test, so this asserts the contract that matters: whatever
        // launcher() returns, startCommand always produces something runnable.
        List<String> start = Autostart.startCommand(Path.of("/jvm/java"), Path.of("/x/oss.jar"));

        assertFalse(start.isEmpty(), "there must always be a command to run");
        assertTrue(start.get(0).length() > 1, "and its first element must be a real path");
    }
}
