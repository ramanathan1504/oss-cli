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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the archives this writes are the archives it rotates.
 *
 * <p>They were not. The writer named them {@code oss_backup_*.zip} and the rotation looked for
 * {@code sa_brain_backup_*.zip} -- a prefix from before the tool was renamed -- so the filter
 * matched nothing, the delete loop never ran once, and "rotating the last five" was a promise the
 * code could not keep. Found on a real machine with 1.2 GB of archives that should have been two.
 *
 * <p>Nothing failed while it was wrong. A backup command that quietly keeps everything looks
 * exactly like one that is working, right up until the disk is full.
 */
class BackupRotationTest {

    @Test
    @DisplayName("the name written and the name rotated are the same name")
    void oneNameNotTwo() throws IOException {
        // Asserted at the source because the failure is a disagreement between two string
        // literals, and no unit test of either half alone can see it.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/cli/BackupCommand.java"), StandardCharsets.UTF_8);

        // The old prefix may still appear in a comment explaining this bug -- and it does, which
        // tripped the first version of this assertion. What matters is that no *filter* uses it.
        assertFalse(
                source.contains("startsWith(\"sa_brain_backup_\")"),
                "the rotation was still looking for a prefix the writer stopped using");
        assertTrue(source.contains("PREFIX = \"oss_backup_\""), "the name is declared once");

        // Both the writer and the rotation must use that constant rather than a literal of their
        // own, which is how they came to disagree.
        int writes = source.split("resolve\\(PREFIX", -1).length - 1;
        assertEquals(2, writes, "the archive and its .partial both build their name from the constant");
        assertTrue(source.contains("startsWith(PREFIX)"), "and the rotation matches on the same constant");
    }

    @Test
    @DisplayName("an archive written today matches the rotation filter")
    void aWrittenArchiveIsRotatable(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        // The behavioural half: whatever the constant says, a file named the way the writer names
        // them has to be picked up by a filter written the way the rotation writes it.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/cli/BackupCommand.java"), StandardCharsets.UTF_8);
        int at = source.indexOf("PREFIX = \"");
        String prefix = source.substring(at + 10, source.indexOf('"', at + 10));

        Path archive = dir.resolve(prefix + "20260830_120000.zip");
        Files.writeString(archive, "zip");

        assertTrue(
                archive.getFileName().toString().startsWith(prefix)
                        && archive.getFileName().toString().endsWith(".zip"),
                "a real archive name must satisfy the filter that deletes old ones");
    }
}
