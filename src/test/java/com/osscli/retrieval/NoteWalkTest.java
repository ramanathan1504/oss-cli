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
package com.osscli.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the note walk indexes notes and nothing else.
 *
 * <p>Putting an archive under git turned its own history into the corpus: 603 "notes" and 40,910
 * "passages", every one of them a zlib blob under {@code .git/objects} read as prose.
 *
 * <p>This used to reimplement the rule and check the source for a matching character comparison.
 * Both passed while the indexer applied the rule to the absolute path and found 0 of 1,472 notes under
 * {@code ~/.oss-cli/memory} for seventeen days. It calls the walk the indexer calls now.
 */
class NoteWalkTest {

    private static List<Path> walked(Path root) throws IOException {
        return NoteIndexer.notesUnder(root);
    }

    @Test
    @DisplayName("a git repository's own objects are not notes")
    void gitInternalsAreSkipped(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git/objects/fe"));
        Path object = root.resolve(".git/objects/fe/c39918407ffb804f8d6506944659ddf6f32551");
        Files.write(object, new byte[] {0x78, 0x01, 0x4b, 0x2c});
        Path note = root.resolve("rollover.md");
        Files.writeString(note, "# rollover");

        List<Path> found = walked(root);
        assertFalse(found.contains(object), "603 of these became notes the first time this ran");
        assertTrue(found.contains(note));
    }

    @Test
    @DisplayName("every editor's state folder goes the same way")
    void otherDotFoldersToo(@TempDir Path root) throws IOException {
        for (String hidden : List.of(".obsidian", ".vscode", ".idea", ".Trash")) {
            Files.createDirectories(root.resolve(hidden));
            Files.writeString(root.resolve(hidden).resolve("workspace.json"), "{}");
        }
        assertEquals(List.of(), walked(root));
    }

    @Test
    @DisplayName("a folder with a dot inside its name is not a dot-folder")
    void dottedNamesAreNotHidden(@TempDir Path root) throws IOException {
        Path note = root.resolve("notes.d").resolve("a.md");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "# a");

        assertEquals(List.of(note), walked(root));
    }

    @Test
    @DisplayName("a note folder that is itself hidden still has notes in it")
    void aHiddenRootIsStillARoot(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve(".oss-cli").resolve("memory");
        Path filed = root.resolve("2026-09-14-review.md");
        Path harvested = root.resolve("harvest").resolve("gh-owner-name-1.md");
        Files.createDirectories(harvested.getParent());
        Files.writeString(filed, "# review");
        Files.writeString(harvested, "# issue");
        Path git = root.resolve(".git").resolve("HEAD");
        Files.createDirectories(git.getParent());
        Files.writeString(git, "ref: refs/heads/main");

        List<Path> found = walked(root);
        assertTrue(found.contains(filed), "0 of 1,472 notes were found under ~/.oss-cli/memory");
        assertTrue(found.contains(harvested));
        assertFalse(found.contains(git), "and what the tool keeps inside that root is still not a note");
    }
}
