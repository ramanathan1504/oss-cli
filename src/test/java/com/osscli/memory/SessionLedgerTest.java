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
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** That the ledger records what was filed, and forgets what is gone. */
class SessionLedgerTest {

    @Test
    @DisplayName("an unchanged transcript is not reopened")
    void unchangedIsSkipped(@TempDir Path dir) throws IOException {
        Path transcript = dir.resolve("projects/a/one.jsonl");
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "{\"turn\":1}\n");
        Path file = dir.resolve("sessions.ledger");

        SessionLedger first = SessionLedger.load(file);
        assertTrue(first.changed(transcript));
        first.mark(transcript);
        first.save(file);

        assertFalse(SessionLedger.load(file).changed(transcript));
    }

    @Test
    @DisplayName("text appended while a transcript is being filed is filed next run")
    void appendDuringFilingIsNotLost(@TempDir Path dir) throws IOException {
        Path transcript = dir.resolve("projects/a/one.jsonl");
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "{\"turn\":1}\n");
        Path file = dir.resolve("sessions.ledger");

        SessionLedger run = SessionLedger.load(file);
        assertTrue(run.changed(transcript));
        Files.writeString(transcript, "{\"turn\":2}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        run.mark(transcript);
        run.save(file);

        assertTrue(SessionLedger.load(file).changed(transcript), "turn 2 was recorded as filed and never filed");
    }

    @Test
    @DisplayName("a deleted transcript is forgotten, an unreachable folder is not")
    void deletedTranscriptsArePruned(@TempDir Path dir) throws IOException {
        Path kept = dir.resolve("projects/a/kept.jsonl");
        Path deleted = dir.resolve("projects/a/deleted.jsonl");
        Files.createDirectories(kept.getParent());
        Files.writeString(kept, "{}\n");
        Files.writeString(deleted, "{}\n");
        Path unmounted = dir.resolve("gone-volume/projects/b/elsewhere.jsonl");
        Path file = dir.resolve("sessions.ledger");
        Files.writeString(file, unmounted + "\t10:1\n");

        SessionLedger run = SessionLedger.load(file);
        for (Path t : new Path[] {kept, deleted}) {
            run.changed(t);
            run.mark(t);
        }
        Files.delete(deleted);
        run.save(file);

        String saved = Files.readString(file);
        assertTrue(saved.contains(kept.toString()));
        assertFalse(saved.contains(deleted.toString()), "43 of 802 entries pointed at nothing");
        assertTrue(saved.contains(unmounted.toString()), "an absent folder is a mount problem, not a deletion");
        assertEquals(2, SessionLedger.load(file).size());
    }
}
