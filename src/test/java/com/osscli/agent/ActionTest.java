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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a model asked for, read out of the words it produced.
 *
 * <p>The protocol is text because the rungs are not the same: Anthropic, OpenAI and Google expose
 * tool calling three different ways, Ollama's depends on the model pulled, and the built-in has
 * none. A loop built on any provider's API would exist on {@code oss claude} and silently not exist
 * below it — the gating failure this repository has paid for twice.
 *
 * <p>So these tests are mostly about tolerating what a small local model actually emits.
 */
class ActionTest {

    @Test
    @DisplayName("a fenced block becomes a tool and its arguments")
    void theHappyCase() {
        Action a = Action.firstIn("""
                I need to see the file first.

                ```oss
                tool: read_file
                path: src/main/java/com/osscli/Main.java
                ```
                """).orElseThrow();

        assertEquals("read_file", a.tool());
        assertEquals("src/main/java/com/osscli/Main.java", a.argument("path"));
    }

    @Test
    @DisplayName("prose without a block is an answer, not an action")
    void proseIsNotAnAction() {
        assertEquals(Optional.empty(), Action.firstIn("The bug is in Cloud.generateText, line 46."));
        assertEquals(Optional.empty(), Action.firstIn(""));
        assertEquals(Optional.empty(), Action.firstIn(null));
    }

    @Test
    @DisplayName("a block naming no tool is not an action")
    void aBlockWithoutAToolIsRejected() {
        // Rather than an Action with a null name, which would push the decision into the loop and
        // make it invent an error message for a case the parser already understands.
        assertEquals(Optional.empty(), Action.firstIn("```oss\npath: pom.xml\n```"));
        assertEquals(Optional.empty(), Action.firstIn("```oss\n\n```"));
    }

    @Test
    @DisplayName("only the first action runs, however many were offered")
    void oneActionPerTurn() {
        Action a = Action.firstIn("""
                ```oss
                tool: read_file
                path: first.txt
                ```
                and then

                ```oss
                tool: read_file
                path: second.txt
                ```
                """).orElseThrow();

        // Five speculative steps become four decided against real output instead of performed blind.
        assertEquals("first.txt", a.argument("path"));
    }

    @Test
    @DisplayName("what a small model actually emits is still understood")
    void tolerantOfRealOutput() {
        Action a = Action.firstIn("""
                ```oss
                # thinking about which file
                Tool:   READ_FILE
                Path:   pom.xml
                lines:  1-40
                ```
                """).orElseThrow();

        assertEquals("read_file", a.tool(), "case is not worth a failed step");
        assertEquals("pom.xml", a.argument("path"), "and neither is spacing");
        assertEquals("1-40", a.argument("lines"));
    }

    @Test
    @DisplayName("a value may contain colons, because paths and messages do")
    void valuesKeepTheirColons() {
        Action a = Action.firstIn("""
                ```oss
                tool: run
                command: mvn -B test -Dtest=Foo#bar
                note: fails at 10:42 with C:\\temp
                ```
                """).orElseThrow();

        assertEquals("mvn -B test -Dtest=Foo#bar", a.argument("command"));
        assertEquals("fails at 10:42 with C:\\temp", a.argument("note"));
    }

    @Test
    @DisplayName("a missing argument is empty rather than absent, so tools report it themselves")
    void missingArgumentsAreEmpty() {
        Action a = Action.firstIn("```oss\ntool: read_file\n```").orElseThrow();

        assertEquals("", a.argument("path"));
        assertTrue(a.raw().contains("tool: read_file"), "the raw block is kept for the transcript");
    }
}
