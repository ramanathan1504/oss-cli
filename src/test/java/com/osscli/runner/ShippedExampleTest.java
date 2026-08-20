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
package com.osscli.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the worked example a newcomer copies is one the tool can actually read.
 *
 * <p>The documentation says "save it as {@code pack.json}", and for a long time this repository
 * shipped no {@code pack.json} at all — the only example was the older shell form, and the example
 * inside {@link PackFile}'s own documentation named a real third-party project, so the template
 * somebody copies told them the tool was for somebody else's repository.
 */
class ShippedExampleTest {

    private static final Path EXAMPLE = Path.of("runner/packs/example-json/pack.json");

    @Test
    @DisplayName("the shipped pack.json is a pack this tool can read")
    void theExampleLoads() throws IOException {
        assertTrue(Files.exists(EXAMPLE), "the documented format ships no example: " + EXAMPLE);

        PackFile pack = PackFile.find(EXAMPLE.getParent()).orElseThrow();

        assertEquals("example-json", pack.name());
        // The shell the engine sources: what the pack declares, as the engine will read it.
        String shell = pack.toShell();
        assertTrue(shell.contains("1.0.0") && shell.contains("1.1.0"), shell);
        assertTrue(shell.contains("hello"), shell);
    }

    @Test
    @DisplayName("the example names nobody's repository but the reader's")
    void theExampleIsGeneric() throws IOException {
        String text = Files.readString(EXAMPLE);

        // A worked example naming a real project reads as "this tool is for that project", which is
        // the opposite of true. owner/name is the placeholder every other example here uses.
        assertTrue(text.contains("owner/name"), "useWhen should be written against owner/name");
        for (String real : List.of("apache/", "elastic/", "spring-projects/", "quarkusio/")) {
            assertFalse(text.contains(real), "the example names a real repository: " + real);
        }
    }
}
