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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That harvest does not claim a source it never reads.
 *
 * <p>The class documentation said Claude Code, codex and gemini "keep their sessions on disk and
 * {@code harvest} can go and get them". It does not: {@code harvest} calls the GitHub search API and
 * nothing else. A reader who believed that sentence would think the local transcripts were already
 * in the corpus and never point {@code import} at them — a gap that looks exactly like coverage.
 *
 * <p>This is a claim test, not a behaviour test. It fails when the sentence and the code disagree,
 * whichever of the two moves.
 */
class HarvestClaimTest {

    private static final Path SOURCE = Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java");

    @Test
    @DisplayName("harvest reads GitHub, and says only that")
    void harvestDoesNotClaimLocalSessions() throws IOException {
        String src = Files.readString(SOURCE);

        int at = src.indexOf("int harvest(");
        assertTrue(at > 0, "harvest is gone; this test guards its description");
        String body = src.substring(at);
        int end = body.indexOf("\n    }");
        body = end > 0 ? body.substring(0, end) : body;

        boolean readsLocalSessions = body.contains(".claude/projects") || body.contains("sessionsOnDisk");
        if (!readsLocalSessions) {
            assertFalse(
                    src.contains("sessions on disk and {@code harvest} can go and get them"),
                    "harvest queries GitHub only — do not tell the reader it collects local transcripts");
        }
    }

    @Test
    @DisplayName("the one source harvest does read is named")
    void gitHubIsNamed() throws IOException {
        String src = Files.readString(SOURCE);
        assertTrue(src.contains("involves:"), "harvest's actual query is the thing worth documenting");
    }
}
