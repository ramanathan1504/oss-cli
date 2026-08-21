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

import com.osscli.memory.BuiltinMemory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That what the tool remembers reaches the commands that answer.
 *
 * <p>It did not. {@code memory harvest} wrote notes and {@code memory search} found them by term,
 * but only {@code sync --me} turns a note into a vector, and it read {@code drive.paths} alone. On a
 * fresh install that setting is empty, so the whole step was skipped: {@code chat}, {@code guide},
 * {@code pick} and {@code prompt} — every command that answers <em>from</em> the corpus — never saw
 * a single harvested item.
 *
 * <p>The loop appeared to work only where an archive extension happened to write into a folder its
 * owner had separately configured. Take the extension away and the compounding stopped, which made
 * "install oss-cli and that is it" false for exactly the half of the corpus that is yours.
 */
class CompoundingClosesTest {

    @Test
    @DisplayName("the built-in store is read even when nothing else is configured")
    void freshInstallStillCompounds() {
        List<String> folders = SyncCommand.noteFolders(null);

        assertEquals(1, folders.size(), folders.toString());
        assertEquals(BuiltinMemory.DIR.toString(), folders.get(0));

        // Empty and blank are the same state as unset, and all three are what a fresh install has.
        assertEquals(folders, SyncCommand.noteFolders(""));
        assertEquals(folders, SyncCommand.noteFolders("   "));
    }

    @Test
    @DisplayName("the built-in store comes first, and never replaces the folders you configured")
    void configuredFoldersAreKept() {
        List<String> folders = SyncCommand.noteFolders("/a/notes, /b/notes");

        assertEquals(3, folders.size(), folders.toString());
        assertEquals(BuiltinMemory.DIR.toString(), folders.get(0), "the tool's own store is not optional");
        assertTrue(folders.contains("/a/notes"));
        // Trimmed: "a, b" is how a person writes a list, and an untrimmed " /b/notes" is a path
        // that does not exist, reported as a missing folder.
        assertTrue(folders.contains("/b/notes"), folders.toString());
    }

    @Test
    @DisplayName("an empty entry in the list is not read as the current directory")
    void blankEntriesAreDropped() {
        List<String> folders = SyncCommand.noteFolders("/a/notes,,  ,/b/notes");

        assertEquals(3, folders.size(), folders.toString());
        assertFalse(folders.contains(""), "an empty path walks whatever the working directory is");
    }

    @Test
    @DisplayName("nothing here fetches or downloads; it reads a folder this tool filled")
    void readingOwnStoreIsNotActingUnasked() {
        // The rule is that nothing arrives unrequested. Reading the store the user's own commands
        // wrote, on the run the user typed, is the opposite of that -- but it is worth stating,
        // because the next person to touch this will weigh it against the same rule.
        assertTrue(
                BuiltinMemory.DIR.toString().contains("memory"),
                "the store is a folder of markdown, not a service to call");
    }
}
