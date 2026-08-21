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

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of the record that has no local files.
 *
 * <p>Claude Code, codex and gemini keep their sessions on disk, so {@code harvest} can fetch them.
 * ChatGPT, Claude.ai and AI Studio keep nothing here — the only route in is the export their owner
 * downloads, which is mostly screenshots by volume and carries secrets in the conversations worth
 * keeping.
 */
class ImportExportTest {

    @Test
    @DisplayName("text is imported and everything else is counted, not silently dropped")
    void onlyTextIsRead() {
        assertTrue(BuiltinMemory.isText(Path.of("conversation.json")));
        assertTrue(BuiltinMemory.isText(Path.of("notes.md")));
        assertTrue(BuiltinMemory.isText(Path.of("paste.txt")));
        assertTrue(BuiltinMemory.isText(Path.of("chat.html")));

        // The file this got wrong. A real export's conversations have NO extension --
        // "Paste July 01, 2026 - 11:21PM" is 45 KB of readable text -- and an allow-list of four
        // extensions imported zero of 179 of them while reporting "not text".
        assertTrue(BuiltinMemory.isText(Path.of("Paste July 01, 2026 - 11:21PM")));
        assertTrue(BuiltinMemory.isText(Path.of("some-conversation-with-no-suffix")));
        assertTrue(BuiltinMemory.isText(Path.of("notes.rtf")));

        // An export is mostly images. Skipping them is right; not saying how many were skipped
        // would read as loss.
        assertFalse(BuiltinMemory.isText(Path.of("screenshot.png")));
        assertFalse(BuiltinMemory.isText(Path.of("recording.mov")));
        assertFalse(BuiltinMemory.isText(Path.of("archive.zip")));
    }

    @Test
    @DisplayName("a nested export flattens into names an archive can hold")
    void namesAreFlattened() {
        Path root = Path.of("/tmp/export");

        String name = BuiltinMemory.importedName(root, Path.of("/tmp/export/chats/2026/june/talk.json"));

        assertEquals("chats-2026-june-talk.json.md", name);
        assertFalse(name.contains("/"), "a note archive is flat; a path is not a file name");
    }

    @Test
    @DisplayName("importing the same export twice rewrites, it does not double the corpus")
    void namesAreStable() {
        Path root = Path.of("/tmp/export");
        Path file = Path.of("/tmp/export/chats/talk.json");

        // The rule the review notes learned after six copies of one review accumulated: a second
        // run must land on the file it wrote the first time.
        assertEquals(BuiltinMemory.importedName(root, file), BuiltinMemory.importedName(root, file));
    }

    @Test
    @DisplayName("a name from somebody else's export cannot become a path")
    void hostileNamesStayNames() {
        Path root = Path.of("/tmp/export");

        for (String nasty : java.util.List.of("../../etc/passwd", "a/../../b.json", "we ird name!.txt", "..")) {
            String name = BuiltinMemory.importedName(root, root.resolve(nasty));

            assertFalse(name.contains("/"), name);
            assertFalse(name.contains("\\"), name);
            Path root2 = Path.of("/archive/imported").toAbsolutePath().normalize();
            assertTrue(root2.resolve(name).normalize().startsWith(root2), nasty + " escaped as " + name);
        }
    }

    @Test
    @DisplayName("import is offered by the built-in, with nothing attached")
    void importIsBuiltIn() {
        assertTrue(BuiltinMemory.VERBS.contains("import"));
        assertTrue(BuiltinMemory.supports("import"));
    }

    @Test
    @DisplayName("a folder that never downloaded is told apart from an empty one")
    void cloudPlaceholdersAreNamed() {
        // Found by running this against a real Google Drive export: 638 files, every read timing
        // out, zero imported. Reported as "binary or unreadable", which reads as "your export has
        // nothing in it" when the truth is "your export is still in the cloud".
        assertTrue(BuiltinMemory.cloudBacked(0, 638));
        assertTrue(BuiltinMemory.cloudBacked(0, 1));

        // A few bad files among good ones is just a few bad files; saying "download your folder"
        // there would be advice for a problem the reader does not have.
        assertFalse(BuiltinMemory.cloudBacked(100, 3));
        assertFalse(BuiltinMemory.cloudBacked(0, 0));
    }
}
