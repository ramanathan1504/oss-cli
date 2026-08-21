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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a backup cannot be written where the next sync will eat it.
 *
 * <p>`sync --me` walks every drive.paths folder and embeds what it finds, so an archive left in one
 * is ingested as a note and then backed up again, without bound. The command refuses rather than
 * warns, because by the time the symptom shows — a corpus that mysteriously exploded — the cause is
 * long gone.
 */
class BackupLocationTest {

    @Test
    @DisplayName("a directory inside an indexed folder is refused")
    void directlyInsideIsRefused(@TempDir Path dir) throws IOException {
        Path indexed = Files.createDirectories(dir.resolve("notes"));
        Path backups = Files.createDirectories(indexed.resolve("backups"));

        assertTrue(BackupCommand.insideIndexedFolder(backups, indexed.toString()));
    }

    @Test
    @DisplayName("a symlink into an indexed folder is refused too")
    void symlinkIsRefused(@TempDir Path dir) throws IOException {
        Path indexed = Files.createDirectories(dir.resolve("notes"));
        Path link = dir.resolve("looks-innocent");
        Files.createSymbolicLink(link, indexed);

        // The check normalised rather than resolved, and normalize() is lexical: it strips "." and
        // ".." and never follows a link. So this path did not "start with" the indexed folder,
        // the guard passed, and the backup landed inside it anyway.
        assertTrue(BackupCommand.insideIndexedFolder(link, indexed.toString()));
    }

    @Test
    @DisplayName("a sibling whose name merely starts the same is allowed")
    void siblingPrefixIsNotInside(@TempDir Path dir) throws IOException {
        Path indexed = Files.createDirectories(dir.resolve("notes"));
        Path sibling = Files.createDirectories(dir.resolve("notes-backup"));

        // Path.startsWith compares segments, not characters. Comparing strings would refuse this
        // perfectly good directory for sharing five letters with an indexed one.
        assertFalse(BackupCommand.insideIndexedFolder(sibling, indexed.toString()));
    }

    @Test
    @DisplayName("an ordinary directory elsewhere is allowed")
    void unrelatedIsAllowed(@TempDir Path dir) throws IOException {
        Path indexed = Files.createDirectories(dir.resolve("notes"));
        Path elsewhere = Files.createDirectories(dir.resolve("archive"));

        assertFalse(BackupCommand.insideIndexedFolder(elsewhere, indexed.toString()));
    }
}
