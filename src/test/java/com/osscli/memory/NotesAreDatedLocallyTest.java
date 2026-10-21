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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a date shown to the operator is the date on their wall, not the date in UTC.
 *
 * <p>{@code oss memory file} named every copy from {@code LocalDate.now(ZoneOffset.UTC)}. East of
 * Greenwich that is yesterday for the whole early morning — in IST, from midnight until 05:30 — so
 * a note filed at 00:13 on 22 October was stored as {@code 2026-10-21-...}. It was found when 46 of
 * 125 filed copies in one archive carried a prefix a day behind the file's own mtime, which makes
 * the directory sort wrong and makes "what did I file today" answer with yesterday's work.
 *
 * <p>A release date is the opposite case and stays in UTC: the website renders GitHub's {@code
 * published_at}, so {@code ChangelogDateTest} compares against UTC on purpose. The rule is not "no
 * UTC" — it is that a date a human reads as <em>today</em> comes from their own zone.
 */
class NotesAreDatedLocallyTest {

    private static final Path FILING = Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java");

    @Test
    @DisplayName("a filed copy is named from the local date")
    void theFiledCopyIsNamedLocally() throws IOException {
        String src = Files.readString(FILING);
        int at = src.indexOf("Path dst = DIR.resolve(");
        assertTrue(at > 0, "the filed copy is named somewhere else now; this test needs moving with it");

        String line = src.substring(at, src.indexOf('\n', at));
        assertEquals(
                "Path dst = DIR.resolve(LocalDate.now() + \"-\" + slug + \".md\");",
                line.strip(),
                "the name must come from the operator's zone, or notes filed before dawn are dated yesterday");
    }

    @Test
    @DisplayName("nothing under memory derives a day from UTC")
    void noDayComesFromUtcInTheArchive() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path dir : List.of(Path.of("src/main/java/com/osscli/memory"), Path.of("src/main/java/com/osscli/cli"))) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file)) {
                        if (line.contains("LocalDate.now(ZoneOffset.UTC)") || line.contains("LocalDate.now(ZoneId")) {
                            offenders.add(file.getFileName() + ": " + line.strip());
                        }
                    }
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "a day the operator reads as today must come from their own zone: " + offenders);
    }
}
