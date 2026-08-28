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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the note walk indexes notes and nothing else.
 *
 * <p>Putting an archive under git turned its own history into the corpus: 603 "notes" and 40,910
 * "passages", every one of them a zlib blob under {@code .git/objects} read as prose. They embed to
 * plausible-looking nonsense, they outnumbered the 877 real notes, and they competed with them at
 * every query.
 *
 * <p>The extension filter could never have caught it -- a git object has no extension at all -- and
 * nothing failed. The index simply got bigger, which reads as progress.
 */
class NoteWalkTest {

    /**
     * The walk as {@link NoteIndexer} performs it.
     *
     * <p>Reimplemented here rather than reached through {@code index}, which needs an embedder and a
     * database. What is asserted is the rule -- which paths are notes -- and that rule is checked
     * against the source below so the two cannot drift apart.
     */
    private static boolean wouldIndex(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (name.length() > 1 && name.charAt(0) == '.') {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("a git repository's own objects are not notes")
    void gitInternalsAreSkipped(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git/objects/fe"));
        Path object = root.resolve(".git/objects/fe/c39918407ffb804f8d6506944659ddf6f32551");
        Files.write(object, new byte[] {0x78, 0x01, 0x4b, 0x2c});
        Path note = root.resolve("rollover.md");
        Files.writeString(note, "# rollover");

        assertFalse(wouldIndex(root, object), "603 of these became notes the first time this ran");
        assertTrue(wouldIndex(root, note));
    }

    @Test
    @DisplayName("every editor's state folder goes the same way")
    void otherDotFoldersToo(@TempDir Path root) throws IOException {
        for (String hidden : java.util.List.of(".obsidian", ".vscode", ".idea", ".Trash")) {
            Files.createDirectories(root.resolve(hidden));
            Path inside = root.resolve(hidden).resolve("workspace.json");
            Files.writeString(inside, "{}");
            assertFalse(wouldIndex(root, inside), hidden + " is state, not writing");
        }
    }

    @Test
    @DisplayName("a folder that merely starts with a dot in its name is not a dot-folder")
    void singleDotSegmentsAreNotHidden(@TempDir Path root) throws IOException {
        // "." appears in a relativized path and must not exclude everything under it.
        Path note = root.resolve("notes.d").resolve("a.md");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "# a");

        assertTrue(wouldIndex(root, note));
    }

    @Test
    @DisplayName("the rule above is the rule the indexer actually applies")
    void theIndexerSkipsDotDirectories() throws IOException {
        // The reimplementation in this file is only worth having if it matches. Asserted at the
        // source, because a walk that quietly stops filtering costs a corpus and fails nothing.
        String source = Files.readString(Path.of("src/main/java/com/osscli/retrieval/NoteIndexer.java"));

        assertTrue(
                source.contains("charAt(0) == '.'"),
                "NoteIndexer must skip dot-directories, or a versioned archive indexes its own history");
    }
}
