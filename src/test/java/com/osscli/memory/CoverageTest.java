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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measuring notes against something outside them.
 *
 * <p>This is the half of a knowledge base that used to require somebody's checkout: the archive
 * path, the topics and the yardstick lived in a Python file that knew one person's iCloud folder.
 * The capability belongs in the tool, so these pin the properties that make it worth having rather
 * than the folder it happened to be written against.
 */
class CoverageTest {

    private static void note(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body);
    }

    @Test
    @DisplayName("an area is covered by several notes, thin in one, and absent when nobody wrote it")
    void thethreeGrades(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "appenders appenders appenders and layouts layouts layouts");
        note(archive, "b.md", "appenders appenders appenders again");
        note(archive, "c.md", "appenders appenders appenders once more");

        List<Coverage.Area> areas = Coverage.score(archive, List.of("Appenders", "Layouts", "Lookups"));
        Map<String, Coverage.Area> byName = new java.util.HashMap<>();
        areas.forEach(a -> byName.put(a.name(), a));

        assertEquals("covered", byName.get("Appenders").grade());
        assertEquals("thin", byName.get("Layouts").grade());
        // The one that matters. Counting your own notes can only report what you wrote, so the
        // grade for something nobody touched has to come from the yardstick, not the archive.
        assertEquals("nothing", byName.get("Lookups").grade());
        assertEquals(0, byName.get("Lookups").notes());
    }

    @Test
    @DisplayName("one passing mention is not knowledge of the subject")
    void aSingleMentionDoesNotCount(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "This note is about something else and says lookups exactly once.");

        // Without a floor, a single stray word puts a note under a subject it merely brushed --
        // which is how an archive comes to report coverage it does not have.
        assertEquals(
                "nothing", Coverage.score(archive, List.of("Lookups")).get(0).grade());
    }

    @Test
    @DisplayName("the strongest note is named, so a score can be checked rather than believed")
    void theEvidenceIsNamed(@TempDir Path archive) throws IOException {
        note(archive, "weak.md", "filters filters filters");
        note(archive, "strong.md", "filters filters filters filters filters filters filters");

        Coverage.Area area = Coverage.score(archive, List.of("Filters")).get(0);

        assertEquals("strong.md", area.strongest());
        assertEquals(10, area.mentions());
    }

    @Test
    @DisplayName("an archive that is not there scores zero rather than failing")
    void anAbsentArchiveIsNotAnError(@TempDir Path dir) throws IOException {
        List<Coverage.Area> areas = Coverage.score(dir.resolve("nope"), List.of("Appenders"));

        // A new install has no archive. Refusing to measure until one exists would mean the first
        // thing a user sees from this is an error about a folder they have not created yet.
        assertEquals(1, areas.size());
        assertEquals("nothing", areas.get(0).grade());
    }

    @Test
    @DisplayName("the map groups notes by declared topic")
    void topicsGroupNotes(@TempDir Path archive) throws IOException {
        note(archive, "a.md", "appender appender appender");
        note(archive, "b.md", "jvm jvm garbage collect");
        note(archive, "c.md", "nothing relevant here at all");

        Map<String, List<String>> map =
                Coverage.map(archive, Map.of("log4j", List.of("appender"), "java", List.of("jvm", "garbage collect")));

        assertEquals(List.of("a.md"), map.get("log4j"));
        assertEquals(List.of("b.md"), map.get("java"));
    }

    @Test
    @DisplayName("with no configuration the defaults hold, which is where every install starts")
    void defaultsNeedNoFile() {
        KnowledgePack pack = KnowledgePack.load();

        // The store the built-in memory already writes to, with no kb.json anywhere.
        assertEquals(BuiltinMemory.DIR, pack.archive());
        // No topics and no yardstick is not a broken configuration, it is an unconfigured one, and
        // the commands print what to write rather than failing. Said as one property so this
        // asserts something: the first draft here was `isEmpty() || !isEmpty()`, which passes
        // whatever the code does.
        assertTrue(pack.isDefault(), "a home with no kb.json must read as unconfigured");
    }
}
