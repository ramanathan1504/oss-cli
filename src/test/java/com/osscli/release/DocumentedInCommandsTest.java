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
package com.osscli.release;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the command reference names every command.
 *
 * <p>{@code DocumentedCommandsTest} partitions every command across {@code OFFLINE.md} and the
 * landing page, and neither of those is the page a person opens to find out how a command works.
 * COMMANDS.md was the one file nothing checked, and nine commands had no entry in it at all —
 * {@code backlog}, {@code hub}, {@code pick}, {@code prs}, {@code issue}, {@code pr},
 * {@code followup}, {@code ext} and {@code alias} — while {@code bench} and {@code kb} were not
 * named anywhere on the page under any spelling.
 *
 * <p>A heading is not required. An engine prefix belongs in the prose that explains prefixes, and a
 * dispatcher's verbs belong to the extension that supplies them; what is required is that the page
 * names the command somewhere, so a reader can find out it exists.
 */
class DocumentedInCommandsTest {

    private static final Path SURFACE = Path.of("release-surface.json");
    private static final Path REFERENCE = Path.of("COMMANDS.md");

    @Test
    @DisplayName("every command a reader can type appears in COMMANDS.md")
    void everyCommandIsNamed() throws IOException {
        String doc = Files.readString(REFERENCE);
        // A second view with the code font removed: the page writes `oss kb`, and a reader sees the
        // command whether or not it is in backticks. Headings are matched against the raw text,
        // where the backticks are part of the heading itself.
        String prose = doc.replace("`", " ");
        List<String> missing = new ArrayList<>();

        for (String name : everyTopLevelCommand()) {
            boolean heading = Pattern.compile("^#+\\s*`" + Pattern.quote(name) + "`", Pattern.MULTILINE)
                    .matcher(doc)
                    .find();
            boolean named = prose.contains("oss " + name + " ") || prose.contains("oss " + name + "\n");
            if (!heading && !named) {
                missing.add(name);
            }
        }

        assertTrue(missing.isEmpty(), "COMMANDS.md never names: " + missing);
    }

    private static TreeSet<String> everyTopLevelCommand() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(SURFACE));
        TreeSet<String> top = new TreeSet<>();
        for (String name : surface.commands().keySet()) {
            if (!name.contains(" ")) {
                top.add(name);
            }
        }
        top.remove("help");
        return top;
    }
}
