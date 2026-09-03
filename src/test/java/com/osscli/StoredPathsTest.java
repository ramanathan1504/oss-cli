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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a path this tool writes down means the same thing on every machine.
 *
 * <p>It did not, three times, and only the third was caught by a machine rather than by CI. A note
 * filed from a pack recorded {@code repros\issue-4279\README.md} on Windows, which is not the
 * string {@code doctor} looks for afterwards; the search corpus keyed notes the same way, so one
 * archive read from two operating systems indexes twice; and a backup zip wrote entries with
 * backslashes, which the format does not allow and a restore turns into files whose names contain
 * one rather than into directories.
 *
 * <p>These tests run on every platform on purpose. A rule that only fails on the runner nobody has
 * is a rule that gets broken again -- this exact class already cost one CI cycle when
 * {@code /contributions/} would not match {@code Projects\x\contributions\}.
 */
class StoredPathsTest {

    /** Where a path becomes a string that is stored, keyed or compared rather than shown. */
    private static final List<String> MUST_NORMALISE = List.of(
            "src/main/java/com/osscli/memory/PackNotes.java",
            "src/main/java/com/osscli/memory/BuiltinMemory.java",
            "src/main/java/com/osscli/cli/BackupCommand.java",
            "src/main/java/com/osscli/knowledge/Curriculum.java");

    @Test
    @DisplayName("the rule itself")
    void separatorsBecomeOne() {
        assertEquals("repros/issue-4279/README.md", AppPaths.slashes("repros\\issue-4279\\README.md"));
        assertEquals("repros/issue-4279/README.md", AppPaths.slashes("repros/issue-4279/README.md"));
        assertEquals("", AppPaths.slashes(null));
    }

    @Test
    @DisplayName("there is one copy of it, not one per package")
    void oneRuleNotFour() throws IOException {
        // Curriculum kept its own and PackNotes had none, which is precisely how they diverged.
        String curriculum =
                Files.readString(Path.of("src/main/java/com/osscli/knowledge/Curriculum.java"), StandardCharsets.UTF_8);
        assertTrue(
                curriculum.contains("AppPaths.slashes(path)"),
                "Curriculum must delegate rather than keep a second copy of the rule");
    }

    @Test
    @DisplayName("every stored path is normalised where it is built")
    void nothingStoresARawSeparator() throws IOException {
        for (String file : MUST_NORMALISE) {
            String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            // Split on statements rather than lines: the one in BackupCommand is a ternary spread
            // over four of them, and a line-wise rule called it a violation when it was not.
            for (String statement : source.split(";")) {
                if (!statement.contains("relativize(") || !statement.contains(".toString()")) {
                    continue;
                }
                // importedName flattens both separators itself a line later, which is the same rule
                // reached another way -- so this asks about the effect, not the spelling.
                boolean normalised = statement.contains("slashes(") || statement.contains("String rel =");
                assertTrue(
                        normalised,
                        file + " stores a raw separator:\n    "
                                + statement.replaceAll("\\s+", " ").strip());
            }
        }
    }

    @Test
    @DisplayName("a zip entry never carries a backslash")
    void zipEntriesUseForwardSlashes() throws IOException {
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/cli/BackupCommand.java"), StandardCharsets.UTF_8);
        int at = source.indexOf("new ZipEntry(entry)");
        assertTrue(at > 0, "the zip writer moved; this guard needs rewriting");

        String before = source.substring(0, at);
        int built = before.lastIndexOf("String entry =");
        assertTrue(
                before.substring(built).contains("slashes("),
                "the format requires \"/\"; a backslash restores as a filename, not a folder");
    }
}
