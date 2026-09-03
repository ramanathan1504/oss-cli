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
package com.osscli.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That {@code search} looks everywhere {@code index} does.
 *
 * <p>It did not. {@code index} embedded every configured folder; {@code search} read one of them.
 * So a note filed into the archive by {@code memory sessions} -- which is where every session note
 * goes -- came back from {@code ask} and was invisible to {@code search}. Two verbs over one
 * archive, disagreeing about whether something had been written down.
 *
 * <p>Found by looking for a note that had been filed perfectly well and could not be found.
 */
class SearchReachesTheArchiveTest {

    @Test
    @DisplayName("a note in the archive is findable by search, not only by ask")
    void theArchiveIsSearched(@TempDir Path home, @TempDir Path work) throws Exception {
        Path archive = work.resolve("archive");
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("kb.json"),
                "{\n  \"archive\": \"" + archive.toAbsolutePath().toString().replace("\\", "\\\\") + "\"\n}\n",
                StandardCharsets.UTF_8);

        Path note = archive.resolve("Projects/freight/spot-rates-worker.md");
        Files.createDirectories(note.getParent());
        Files.writeString(
                note,
                "---\ntitle: Spot Rates Worker on Linux\n---\n\n"
                        + "The carrier scrapers need Xvfb because Hapag template-matches with OpenCV.\n",
                StandardCharsets.UTF_8);

        Journey.Ran ran =
                Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "search", "Xvfb Hapag OpenCV");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(ran.all().contains("spot-rates-worker"), "the archive was not searched:\n" + ran.all());
    }

    @Test
    @DisplayName("version control inside the archive is never searched")
    void gitObjectsAreNotNotes(@TempDir Path home, @TempDir Path work) throws Exception {
        // The archive is a git repository and the store beside it is not, which is why adding it
        // to the search corpus needs the same dot-directory rule the indexer already carries.
        // 40,910 passages of zlib entered the vector index this way once.
        Path archive = work.resolve("archive");
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("kb.json"),
                "{\n  \"archive\": \"" + archive.toAbsolutePath().toString().replace("\\", "\\\\") + "\"\n}\n",
                StandardCharsets.UTF_8);

        Path hidden = archive.resolve(".git/refs/notes/sentineltoken.md");
        Files.createDirectories(hidden.getParent());
        Files.writeString(hidden, "sentineltoken sentineltoken sentineltoken", StandardCharsets.UTF_8);
        Path real = archive.resolve("real.md");
        Files.writeString(real, "# real\n\nsentineltoken lives here too\n", StandardCharsets.UTF_8);

        Journey.Ran ran = Journey.ossAtHome(home, work, work.resolve("fakehome"), "memory", "search", "sentineltoken");

        assertEquals(0, ran.code(), ran.all());
        assertTrue(ran.all().contains("real"), ran.all());
        assertTrue(!ran.all().contains(".git"), "git internals are not writing:\n" + ran.all());
    }
}
