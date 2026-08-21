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
package com.osscli.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That {@code release-surface.json} still describes the program.
 *
 * <p>This runs on every pull request rather than only at release time, and that is the whole design.
 * A guard that only fires during {@code release.sh} tells you what you changed weeks ago, at the
 * moment you least want to think about it. Firing on the pull request that adds a command means the
 * record is updated by the person adding it, in the change where it makes sense.
 *
 * <p>Regenerate with:
 *
 * <pre>mvn test -Dtest=ReleaseSurfaceTest -Dsurface.update=true</pre>
 */
class ReleaseSurfaceTest {

    /** Repo root, resolved from the working directory Surefire runs in. */
    private static final Path SURFACE_FILE = Path.of("release-surface.json");

    @Test
    @DisplayName("the committed surface matches the commands and schema this build actually has")
    void surfaceIsCurrent() throws Exception {
        Surface live = Surface.current();

        if (Boolean.getBoolean("surface.update")) {
            Files.writeString(SURFACE_FILE, live.toJson(), StandardCharsets.UTF_8);
            System.out.println("Rewrote " + SURFACE_FILE.toAbsolutePath());
            return;
        }

        assertTrue(
                Files.exists(SURFACE_FILE),
                SURFACE_FILE + " is missing. Create it with: "
                        + "mvn test -Dtest=ReleaseSurfaceTest -Dsurface.update=true");

        Surface committed = Surface.fromJson(Files.readString(SURFACE_FILE, StandardCharsets.UTF_8));
        List<String> drift = live.differences(committed);

        assertTrue(
                drift.isEmpty(),
                "release-surface.json no longer describes this build:\n  "
                        + String.join("\n  ", drift)
                        + "\n\nThis is not a failure of your change — it is the record catching up with it."
                        + "\nRegenerate and commit the result:"
                        + "\n  mvn test -Dtest=ReleaseSurfaceTest -Dsurface.update=true");
    }

    @Test
    @DisplayName("the surface reads the real command tree, not a list somebody typed")
    void surfaceComesFromPicocli() {
        Surface live = Surface.current();

        // If these ever stop being present, the walk has broken rather than the commands having
        // been deleted -- a silently empty surface would let anything through the guard.
        assertTrue(live.commands().containsKey("chat"), "chat missing from the surface");
        assertTrue(live.commands().containsKey("history"), "history missing from the surface");
        assertTrue(live.commands().containsKey("doctor"), "doctor missing from the surface");
        assertTrue(
                live.commands().size() > 20, "only " + live.commands().size() + " commands found; the walk is wrong");

        assertTrue(live.commands().get("chat").contains("--resume"), "chat --resume missing");
        assertTrue(live.commands().get("chat").contains("-c"), "short aliases must be recorded too");
        assertTrue(live.commands().get("history").contains("--search"), "history --search missing");
    }

    @Test
    @DisplayName("nested subcommands are recorded under their full path")
    void nestedCommandsAreNotFlattened() {
        Surface live = Surface.current();
        boolean anyNested = live.commands().keySet().stream().anyMatch(name -> name.contains(" "));
        assertTrue(anyNested, "no nested command was recorded; `oss ext list` could vanish unnoticed");
    }

    @Test
    @DisplayName("the schema version travels with the surface")
    void schemaVersionIsCarried() {
        assertEquals(
                com.osscli.storage.DatabaseManager.currentSchemaVersion(),
                Surface.current().schemaVersion());
    }

    @Test
    @DisplayName("what is written can be read back unchanged")
    void jsonRoundTrips() {
        Surface live = Surface.current();
        Surface reparsed = Surface.fromJson(live.toJson());

        assertEquals(live.schemaVersion(), reparsed.schemaVersion());
        assertEquals(live.commands().keySet(), reparsed.commands().keySet());
        assertTrue(live.differences(reparsed).isEmpty(), "a round trip must not invent a difference");
    }

    @Test
    @DisplayName("the built-in memory's verbs are part of the recorded surface")
    void builtinVerbsAreRecorded() {
        Surface live = Surface.current();

        // picocli cannot see these: they arrive as passthrough parameters, so `oss memory digest`
        // was invisible to the walk while being exactly as scriptable as any flag. Removing one
        // would have broken somebody's daily job with the guard reporting no change at all.
        java.util.Set<String> verbs = live.commands().get("memory builtin-verbs");
        assertTrue(verbs != null && !verbs.isEmpty(), "the built-in verbs are not in the surface");
        assertEquals(new java.util.TreeSet<>(com.osscli.memory.BuiltinMemory.VERBS), verbs);

        // And they must survive the round trip. Written as "memory <builtin>" they did not: the
        // parser's key charset dropped the row on the way back in, so the guard compared a surface
        // that had the verbs against one that never did, and reported no difference.
        Surface reparsed = Surface.fromJson(live.toJson());
        assertEquals(verbs, reparsed.commands().get("memory builtin-verbs"), "the row did not survive fromJson");
    }

    @Test
    @DisplayName("the built-in runner's verbs are recorded on the same terms")
    void builtinRunnerVerbsAreRecorded() {
        Surface live = Surface.current();

        // Same argument as the memory's verbs, and worth its own test rather than an extra line in
        // that one: these arrive by the same invisible route, and the row that carries them is a
        // separate key that can be dropped on its own.
        java.util.Set<String> verbs = live.commands().get("run builtin-verbs");
        assertTrue(verbs != null && !verbs.isEmpty(), "the built-in runner's verbs are not in the surface");
        assertEquals(new java.util.TreeSet<>(com.osscli.runner.BuiltinRunner.VERBS), verbs);
        assertEquals(
                verbs,
                Surface.fromJson(live.toJson()).commands().get("run builtin-verbs"),
                "the row did not survive fromJson");
    }
}
