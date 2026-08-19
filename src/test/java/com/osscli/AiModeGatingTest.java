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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.llm.Ai;
import com.osscli.release.Surface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the engine you typed is the engine that could have answered.
 *
 * <p>A provider used to be a flag on two commands, spelled {@code --send-claude} on one and
 * {@code --claude} on the other, with an automatic local verdict whenever a daemon happened to be
 * installed. You could not tell from what you typed whether a model had read your code. The engine
 * is a prefix now, and these are the properties that makes it worth having.
 */
class AiModeGatingTest {

    @AfterEach
    void clearSelection() {
        // Process-wide by design -- one invocation, one engine list. A test that left its choice
        // behind would hand it to whichever test ran next.
        Ai.reset();
    }

    @Test
    @DisplayName("every command is classified, and nothing is classified that does not exist")
    void theClassificationCoversTheCommandSet() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(Path.of("release-surface.json")));
        var commands = new TreeSet<String>();
        for (String name : surface.commands().keySet()) {
            if (!name.contains(" ")) {
                commands.add(name);
            }
        }
        commands.remove("help");
        // The prefixes are not commands you gate; they are how you say which engine.
        commands.removeAll(Ai.prefixes());

        var classified = new TreeSet<>(Ai.USE.keySet());
        assertEquals(
                commands,
                classified,
                "every command must say whether it generates -- adding one should force the decision, "
                        + "not default into silence");
    }

    @Test
    @DisplayName("naming no engine means nothing external may be reached")
    void plainIsLocal() {
        assertEquals(java.util.List.of(Ai.Engine.BUILTIN), Ai.engines());
        assertFalse(Ai.mayEscalate(), "a plain invocation must not be able to reach anybody's API");
        assertTrue(Ai.escalationPath().isEmpty());
    }

    @Test
    @DisplayName("prefixes stack in the order typed, and repeats collapse")
    void prefixesStack() {
        Ai.add(Ai.Engine.OLLAMA);
        Ai.add(Ai.Engine.CLAUDE);
        Ai.add(Ai.Engine.OLLAMA);

        assertEquals(
                java.util.List.of(Ai.Engine.OLLAMA, Ai.Engine.CLAUDE),
                Ai.engines(),
                "order is the escalation order, so it is kept; typing one twice is not two engines");
        assertTrue(Ai.mayEscalate());
    }

    @Test
    @DisplayName("a local engine is not an external one")
    void ollamaIsNotEscalation() {
        Ai.add(Ai.Engine.OLLAMA);

        assertTrue(Ai.mayEscalate() == false, "Ollama runs on this machine: naming it must not permit a network call");
        assertTrue(Ai.escalationPath().isEmpty());
    }

    @Test
    @DisplayName("a command that never generates refuses the prefix instead of ignoring it")
    void theGateRefuses() {
        Cli.Result r = Cli.run("claude", "doctor");

        assertFalse(r.ok(), "silently running the same deterministic report would leave the wrong impression");
        assertTrue(r.says("never asks a model"), r.all());
        assertTrue(r.says("oss doctor"), "must name the command that does work: " + r.all());
    }

    @Test
    @DisplayName("a prefix with nothing after it says what it would do")
    void barePrefixExplains() {
        Cli.Result r = Cli.run("llm");

        assertTrue(r.ok(), r.all());
        assertTrue(r.says("oss llm"), r.all());
        // Only the commands that can actually use one are offered.
        assertTrue(r.says("review"), r.all());
        assertFalse(r.says("oss llm doctor"), "offered a command it would refuse: " + r.all());
    }

    @Test
    @DisplayName("the prefix is consumed, and everything after it belongs to the command")
    void argumentsPassThrough() {
        // --refresh is review's flag and unknown here. Parsed at this level it would answer with
        // this command's usage, which is the bug stopAtPositional exists to prevent.
        Cli.Result r = Cli.run("claude", "review", "--help");

        assertTrue(r.says("oss review"), "did not reach review: " + r.all());
        assertFalse(r.says("Unknown option"), r.all());
    }
}
