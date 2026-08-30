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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That walking an archive under version control measures the notes and not the history.
 *
 * <p>Found by sweeping for the class rather than the instance. The indexer had already been fixed
 * after {@code .git/objects} became 40,910 passages of zlib in a real corpus; this walk had the
 * same shape and had not been looked at, and here the cost is worse than waste. The walk has a
 * fifteen-second budget, git objects are reached before the notes are, and the shortfall is
 * reported as "the rest are still in the cloud" -- a true sentence about the wrong cause.
 */
class ArchiveWalkTest {

    @Test
    @DisplayName("a git object is never mistaken for a note")
    void gitInternalsAreNotNotes(@TempDir Path archive) throws IOException {
        Files.createDirectories(archive.resolve(".git/objects/fe"));
        Path object = archive.resolve(".git/objects/fe/c39918407ffb804f8d6506944659ddf6");
        Files.write(object, new byte[] {0x78, 0x01, 0x4b, 0x2c});

        assertFalse(ArchiveNotes.notInsideADotDirectory(object), "603 of these became notes once");
    }

    @Test
    @DisplayName("every editor's state folder goes the same way")
    void otherDotFoldersToo(@TempDir Path archive) throws IOException {
        for (String hidden : java.util.List.of(".obsidian", ".vscode", ".idea", ".Trash")) {
            Path inside = archive.resolve(hidden).resolve("state.json");
            Files.createDirectories(inside.getParent());
            Files.writeString(inside, "{}");
            assertFalse(ArchiveNotes.notInsideADotDirectory(inside), hidden + " is state, not writing");
        }
    }

    @Test
    @DisplayName("a real note is still a note")
    void notesSurvive(@TempDir Path archive) throws IOException {
        Path note = archive.resolve("Projects/log4j/rollover.md");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "# rollover", StandardCharsets.UTF_8);

        assertTrue(ArchiveNotes.notInsideADotDirectory(note));
        // And a folder whose name merely contains a dot is not a dot-folder.
        assertTrue(ArchiveNotes.notInsideADotDirectory(archive.resolve("notes.d/a.md")));
    }

    @Test
    @DisplayName("the budgeted walk applies the rule, not just the helper")
    void theWalkIsWired() throws IOException {
        // The lesson from ranInATempDirectory, which had four passing tests and no call site: a
        // rule nothing calls is not a rule. Asserted at the source because the walk needs a real
        // archive and a clock to exercise.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/memory/ArchiveNotes.java"), StandardCharsets.UTF_8);
        int walk = source.indexOf("Files.walk(archive)");
        assertTrue(walk > 0, "the walk moved; this guard needs rewriting");

        int filter = source.indexOf("notInsideADotDirectory", walk);
        int md = source.indexOf("endsWith(\".md\")", walk);
        assertTrue(filter > 0 && filter < md, "the dot-directory filter must run before the walk collects");
    }

    @Test
    @DisplayName("a recursive delete refuses a path outside the temp directory")
    void destructivePathsAreAsserted() throws IOException {
        // This walks a tree and deletes every entry in it. The only thing between that and
        // somebody's checkout was the caller passing the right path -- which was also true of the
        // test in this repository that believed its configuration and destroyed a real 496 MB
        // database. Assert where a destructive path points and refuse.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/review/Verifier.java"), StandardCharsets.UTF_8);
        int method = source.indexOf("private static void deleteTree(");
        assertTrue(method > 0, "deleteTree moved; this guard needs rewriting");

        String body = source.substring(method, source.indexOf("Files.walk(root)", method));
        assertTrue(body.contains("java.io.tmpdir"), "it must know where temp is");
        assertTrue(body.contains("startsWith(temp)"), "and refuse anything outside it");
        assertEquals(1, body.split("refusing to delete", -1).length - 1, "and say so rather than fail silently");
    }
}
