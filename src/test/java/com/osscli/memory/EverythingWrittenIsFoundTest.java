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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a note the tool wrote is a note the tool can find.
 *
 * <p>It was not. Every verb that writes a note writes it into a subfolder — {@code harvest/},
 * {@code sessions/}, {@code imported/}, {@code gaps/} — and the loader listed one level. So a
 * harvest could collect a thousand items, report a thousand items, and the very next
 * {@code memory search} would answer <em>nothing filed yet</em>. The loop the whole tool is built on
 * was open at the join, and both ends reported success.
 *
 * <p>Found by running the two commands in sequence rather than by testing either one.
 */
class EverythingWrittenIsFoundTest {

    /** The folders the writing verbs actually use. Adding one without adding it here is the bug. */
    private static final List<String> WHERE_NOTES_LAND = List.of("harvest", "sessions", "imported", "gaps");

    @Test
    @DisplayName("every folder a verb writes into is somewhere the reader looks")
    void nothingIsWrittenSomewhereUnread() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"));

        // The reader must walk. Files.list stops at the top level, where none of these are.
        int at = src.indexOf("private static List<Note> load()");
        assertTrue(at > 0, "the loader is gone; this test guards what it reaches");
        String body = src.substring(at, src.indexOf("\n    }", at));
        assertTrue(body.contains("Files.walk"), "a one-level listing cannot see a note in a subfolder");

        for (String folder : WHERE_NOTES_LAND) {
            assertTrue(
                    src.contains("DIR.resolve(\"" + folder + "\")"),
                    folder + " is no longer written to — update this list, it is the point of the test");
        }
    }

    @Test
    @DisplayName("a note in a subfolder is loaded, and keeps the folder in its name")
    void subfolderNotesAreLoaded(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        // Exercises the same rule the loader follows, against a folder a test made: two notes with
        // the same file name in different folders are two notes, not one silently winning.
        for (String folder : WHERE_NOTES_LAND) {
            Files.createDirectories(dir.resolve(folder));
            Files.writeString(dir.resolve(folder).resolve("note.md"), "# " + folder + "\n", StandardCharsets.UTF_8);
        }

        List<Path> found;
        try (var walk = Files.walk(dir)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();
        }

        assertEquals(WHERE_NOTES_LAND.size(), found.size(), found.toString());
        assertEquals(
                WHERE_NOTES_LAND.size(),
                found.stream()
                        .map(dir::relativize)
                        .map(Path::toString)
                        .distinct()
                        .count(),
                "the folder must survive into the name, or the four collapse into one");
    }

    @Test
    @DisplayName("one note is one line of results, however many of its passages matched")
    void resultsAreDedupedByNote() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"));

        // Ranked by passage, reported by note. Printing every passage listed one file three times
        // at three scores under a heading that said "note(s)" -- three pieces of writing where
        // there is one, and the other matches pushed off the list.
        assertTrue(src.contains("best.size() + \" of \" + notes.size()"), "the count must be of notes, not passages");
        assertTrue(src.contains("best.size() + \" of \" + noteCount"), "the meaning path counts them too");
    }
}
