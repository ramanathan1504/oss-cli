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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.osscli.release.Surface.Bump;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * That the version being released is at least as large as what changed.
 *
 * <p>{@code release.sh} checked the shape of the version and that the tag was free. It never checked
 * that the number was <em>right</em>: {@code 1.10.2} would have been accepted for the release that
 * added {@code oss history}, {@code oss chat --resume} and a schema migration, telling everyone who
 * read it "a fix, nothing new to learn".
 *
 * <p>The rule, which is bnd's rule with the schema added:
 *
 * <table>
 *   <tr><td>command or flag removed</td><td>major</td></tr>
 *   <tr><td>schema raised, or command or flag added</td><td>minor</td></tr>
 *   <tr><td>neither</td><td>patch</td></tr>
 * </table>
 *
 * <p>Run by {@code release.sh} with three properties. Without them it skips, because most of the
 * time it is running inside an ordinary build that has no release to check.
 */
class ReleaseGuardTest {

    @Test
    @DisplayName("the requested version is not smaller than the change requires")
    void requestedVersionIsBigEnough() throws Exception {
        String requested = System.getProperty("guard.version");
        String previousTag = System.getProperty("guard.prevTag");
        String previousFile = System.getProperty("guard.prev");

        assumeTrue(requested != null && previousFile != null, "not a release run");

        Path previousPath = Path.of(previousFile);
        if (!Files.exists(previousPath) || Files.size(previousPath) == 0) {
            // The first release after this guard lands has no recorded surface to compare against.
            // Passing is right; inventing a comparison against an empty file would call every
            // command "added" and demand a minor bump for a genuine patch.
            System.out.println("No surface recorded at " + previousTag + " — nothing to compare, allowing.");
            return;
        }

        Surface previous = Surface.fromJson(Files.readString(previousPath, StandardCharsets.UTF_8));
        Surface live = Surface.current();

        Bump required = live.requiredBump(previous);
        int[] from = Surface.parseVersion(previousTag.replaceFirst("^v", ""));
        int[] to = Surface.parseVersion(requested);
        Bump actual = Bump.between(from, to);

        assertTrue(
                actual.compareTo(required) >= 0,
                "v" + requested + " is too small a bump for what changed since " + previousTag + "."
                        + "\n\n  required: " + required.name().toLowerCase(java.util.Locale.ROOT)
                        + "\n  requested: " + actual.name().toLowerCase(java.util.Locale.ROOT)
                        + "\n\nWhat changed:\n  "
                        + String.join("\n  ", live.differences(previous))
                        + "\n\nRelease a "
                        + required.name().toLowerCase(java.util.Locale.ROOT)
                        + " version instead.");
    }

    @Nested
    @DisplayName("the rule itself")
    class Rule {

        private static final String BASE = """
                {
                  "schemaVersion": 14,
                  "commands": {
                    "chat": ["--resume", "-c"],
                    "doctor": ["-h"]
                  }
                }
                """;

        @Test
        @DisplayName("nothing changed is a patch")
        void identicalIsPatch() {
            assertEquals(Bump.PATCH, Surface.fromJson(BASE).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a new command is a minor")
        void addedCommandIsMinor() {
            String withHistory = BASE.replace("\"doctor\":", "\"history\": [\"--list\"],\n    \"doctor\":");
            assertEquals(Bump.MINOR, Surface.fromJson(withHistory).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a new flag on an existing command is a minor")
        void addedFlagIsMinor() {
            String withFlag = BASE.replace("[\"--resume\", \"-c\"]", "[\"--resume\", \"-c\", \"--continue\"]");
            assertEquals(Bump.MINOR, Surface.fromJson(withFlag).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a raised schema version is a minor even when no command changed")
        void raisedSchemaIsMinor() {
            String migrated = BASE.replace("\"schemaVersion\": 14", "\"schemaVersion\": 15");
            assertEquals(Bump.MINOR, Surface.fromJson(migrated).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a removed command is a major")
        void removedCommandIsMajor() {
            String without = BASE.replace("\"chat\": [\"--resume\", \"-c\"],\n    ", "");
            assertEquals(Bump.MAJOR, Surface.fromJson(without).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a removed flag is a major, even when the command survives")
        void removedFlagIsMajor() {
            String without = BASE.replace("[\"--resume\", \"-c\"]", "[\"--resume\"]");
            assertEquals(Bump.MAJOR, Surface.fromJson(without).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("dropping a short alias is a major, because scripts use it")
        void removedShortAliasIsMajor() {
            String without = BASE.replace("[\"--resume\", \"-c\"]", "[\"--resume\", \"--continue\"]");
            assertEquals(Bump.MAJOR, Surface.fromJson(without).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("a schema version that went backwards is a major, not a patch")
        void loweredSchemaIsMajor() {
            String older = BASE.replace("\"schemaVersion\": 14", "\"schemaVersion\": 13");
            assertEquals(Bump.MAJOR, Surface.fromJson(older).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("removing one thing and adding another is still a major")
        void removalWinsOverAddition() {
            String churned = BASE.replace("[\"--resume\", \"-c\"]", "[\"--resume\", \"--carry-on\"]");
            assertEquals(Bump.MAJOR, Surface.fromJson(churned).requiredBump(Surface.fromJson(BASE)));
        }

        @Test
        @DisplayName("the differences read as a review comment, not a diff")
        void differencesAreReadable() {
            String changed = BASE.replace("\"doctor\":", "\"history\": [\"--list\"],\n    \"doctor\":")
                    .replace("\"schemaVersion\": 14", "\"schemaVersion\": 15");
            List<String> out = Surface.fromJson(changed).differences(Surface.fromJson(BASE));

            assertTrue(out.contains("added command: history"), out.toString());
            assertTrue(out.stream().anyMatch(s -> s.startsWith("schema version: 14")), out.toString());
        }
    }

    @Nested
    @DisplayName("comparing two version numbers")
    class VersionArithmetic {

        @Test
        @DisplayName("each part maps to its bump")
        void bumpBetween() {
            assertEquals(Bump.PATCH, Bump.between(new int[] {1, 11, 0}, new int[] {1, 11, 1}));
            assertEquals(Bump.MINOR, Bump.between(new int[] {1, 11, 0}, new int[] {1, 12, 0}));
            assertEquals(Bump.MAJOR, Bump.between(new int[] {1, 11, 0}, new int[] {2, 0, 0}));
        }

        @Test
        @DisplayName("a larger bump than required is allowed")
        void largerIsFine() {
            assertTrue(Bump.MAJOR.compareTo(Bump.MINOR) >= 0);
            assertTrue(Bump.MINOR.compareTo(Bump.PATCH) >= 0);
            assertTrue(Bump.MINOR.compareTo(Bump.MAJOR) < 0, "a minor must not satisfy a required major");
        }

        @Test
        @DisplayName("a version that is not three parts is rejected rather than guessed at")
        void malformedVersionsAreRejected() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> Surface.parseVersion("1.11"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    NumberFormatException.class, () -> Surface.parseVersion("1.11.x"));
        }
    }
}
