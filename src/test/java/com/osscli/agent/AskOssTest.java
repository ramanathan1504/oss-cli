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
package com.osscli.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loop could not ask the tool it lives inside.
 *
 * <p>Measured, not reviewed: asked which recorded reviews were waiting, the loop searched the issue
 * index three times, matched titles containing the word "review", and concluded — carefully, and
 * wrongly — that no such data exists on the machine. Fifty-five seconds to be wrong about something
 * {@code oss hub} answers correctly in eight, because {@code recall} covers synced issues and the
 * review ledger is not one.
 */
class AskOssTest {

    private final List<List<String>> ran = new ArrayList<>();

    private AskOss tool(String output) {
        return new AskOss((argv, timeout) -> {
            ran.add(argv);
            return output;
        });
    }

    private static Action ask(String body) {
        return Action.firstIn("```oss\ntool: oss\n" + body + "\n```").orElseThrow();
    }

    @Test
    @DisplayName("a question on the table runs the argv the board would have run")
    void aKnownQuestionRuns(@TempDir Path dir) {
        String out =
                tool("  WAITING ON YOU\n    apache/logging-log4j2 #4229").run(ask("question: hub"), new Workspace(dir));

        assertEquals(List.of(List.of("hub")), ran);
        assertTrue(out.contains("#4229"), out);
    }

    @Test
    @DisplayName("an unknown question answers with the vocabulary rather than a failure")
    void anUnknownQuestionTeaches(@TempDir Path dir) {
        String out = tool("").run(ask("question: telepathy"), new Workspace(dir));

        assertTrue(out.startsWith("error:"), out);
        assertTrue(out.contains("hub"), "it lists what can be asked:\n" + out);
        assertTrue(ran.isEmpty(), "and runs nothing");
    }

    @Test
    @DisplayName("a question needing an argument says which one, instead of running half a command")
    void aMissingArgumentIsNamed(@TempDir Path dir) {
        // `search` takes text. Running it without any would either error deep inside or, worse,
        // succeed against an empty query and return something meaningless.
        String out = tool("").run(ask("question: search"), new Workspace(dir));

        assertTrue(out.contains("needs arg:"), out);
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("an argument is passed through to the command")
    void anArgumentIsPassed(@TempDir Path dir) {
        tool("nothing found").run(ask("question: search\narg: zstd rollover"), new Workspace(dir));

        assertEquals(List.of(List.of("search", "zstd rollover")), ran);
    }

    @Test
    @DisplayName("an empty answer becomes the table's own sentence for empty")
    void emptyBecomesTheStatedEmpty(@TempDir Path dir) {
        String out = tool("   ").run(ask("question: hub"), new Workspace(dir));

        assertFalse(out.isBlank(), "a blank answer tells the model nothing at all");
        assertTrue(out.length() > 5, out);
    }

    @Test
    @DisplayName("it never writes, because every entry on that table reads")
    void itIsReadOnly() {
        // Enforced in the serve package by a test that fails the build if a writing command is ever
        // added to the table -- which is why this can be declared rather than audited here.
        assertFalse(tool("").writes());
    }
}
