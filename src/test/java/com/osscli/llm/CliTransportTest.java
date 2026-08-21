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
package com.osscli.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That what each provider's tool is told is what it was meant to be told.
 *
 * <p>Only one of these three is installed on any given machine, which is the same problem the
 * autostart definitions had: two thirds of the code unreadable without the machine that runs it. So
 * the command is a value here too, and these read it rather than run it.
 */
class CliTransportTest {

    private static final Path LAST = Path.of("/tmp/last.txt");

    @Test
    @DisplayName("claude is asked for one answer, not a session")
    void claudeIsHeadless() {
        List<String> cmd = new CliClient(CliClient.CLAUDE, 60).commandFor("judge this", LAST);

        // -p is what makes it non-interactive; without it the tool opens a session and the command
        // hangs waiting for somebody who is not there.
        assertTrue(cmd.contains("-p"), cmd.toString());
        assertEquals(List.of("claude", "-p", "judge this", "--output-format", "json"), cmd);
    }

    @Test
    @DisplayName("codex is sandboxed read-only and not asked about the checkout it stands in")
    void codexCannotWrite() {
        List<String> cmd = new CliClient(CliClient.CODEX, 60).commandFor("judge this", LAST);

        // These tools are agent harnesses, not completion endpoints: given the chance they will read
        // files and run commands. Asking for a verdict must not be a way to change anything.
        assertTrue(cmd.contains("--sandbox"), cmd.toString());
        assertEquals("read-only", cmd.get(cmd.indexOf("--sandbox") + 1));
        assertTrue(cmd.contains("--skip-git-repo-check"), cmd.toString());
    }

    @Test
    @DisplayName("a tool reporting failure in a zero exit is not read as an answer")
    void claudeErrorEnvelopeIsNotAnAnswer() throws Exception {
        CliClient client = new CliClient(CliClient.CLAUDE, 60);

        // The process exits 0 and prints an envelope saying it failed. Returning result verbatim
        // would file that sentence as a review verdict.
        assertThrows(
                ApiFailure.Permanent.class,
                () -> client.extract("{\"is_error\":true,\"result\":\"credit balance too low\"}", LAST));
        assertEquals("OK", client.extract("{\"is_error\":false,\"result\":\"OK\"}", LAST));
    }

    @Test
    @DisplayName("the local daemon and the built-in model have no tool to stand in front of")
    void notEveryEngineHasACli() {
        // --cli on these used to be worth refusing rather than ignoring: silently doing nothing
        // leaves somebody believing they changed where their code went.
        assertNull(CliClient.specFor(Ai.Engine.OLLAMA));
        assertNull(CliClient.specFor(Ai.Engine.BUILTIN));
        assertEquals(CliClient.CODEX, CliClient.specFor(Ai.Engine.OPENAI));
    }

    @Test
    @DisplayName("an invocation nobody here could run says so rather than claiming to work")
    void unverifiedIsMarked() {
        assertTrue(CliClient.CLAUDE.verified());
        assertTrue(CliClient.CODEX.verified());
        // Not installed on the machine this was written on. Doctor prints that rather than a tick.
        assertFalse(CliClient.GEMINI.verified());
    }

    @Test
    @DisplayName("the transport is off unless it was asked for")
    void transportIsNeverInferred() {
        Ai.useCli(false);
        assertFalse(Ai.viaCli());
        Ai.useCli(true);
        assertTrue(Ai.viaCli());
        Ai.useCli(false);
    }
}
