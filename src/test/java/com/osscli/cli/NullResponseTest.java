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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That nothing hands a null to a parser.
 *
 * <p>Two methods here answer null on purpose. {@code GitHubClient.getJson} returns null for a 404,
 * because absent is not an error at that layer. {@code OllamaClient.generateJson} returns whatever
 * the daemon put in {@code "response"}, which is null when it put nothing there.
 *
 * <p>Handed to {@code readTree}, either one throws {@code argument "content" is null} — a Jackson
 * complaint about an argument, where the reader needed an answer about a pull request or a model.
 * The repository had already fixed that once, for the pull request itself, and left it standing
 * thirty-eight lines further down for the file list.
 */
class NullResponseTest {

    /** A parse whose argument is checked, one way or another, on the same line. */
    private static final Pattern GUARDED = Pattern.compile("readTree\\(\\s*(orEmpty\\(|[A-Za-z_]+\\s*(==|!=)|.*\\?)");

    @Test
    @DisplayName("no parse is handed a value that is allowed to be null")
    void everyParseIsGuarded() throws IOException {
        List<String> unguarded = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/osscli"))) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                String[] lines = text.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (!line.contains("readTree(")) {
                        continue;
                    }
                    // Only the two methods that answer null by design matter here; a file read or a
                    // stream cannot be null and guarding it would be noise.
                    boolean risky = line.contains("getJson(") || line.contains("generateJson(");
                    if (!risky) {
                        continue;
                    }
                    Matcher m = GUARDED.matcher(line);
                    if (!m.find()) {
                        unguarded.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }
        assertTrue(unguarded.isEmpty(), "readTree(null) throws rather than explaining: " + unguarded);
    }

    @Test
    @DisplayName("a model that answered with nothing is reported, not parsed")
    void anEmptyModelAnswerIsHandled() throws IOException {
        // ReviewCommand had this right and said so on screen. onboard and prompt went straight to
        // the parser, so a daemon that replied without a "response" field produced a stack trace
        // where the honest answer was "it returned nothing, here is what that means".
        for (String command : List.of("Onboard", "Prompt", "Review")) {
            String src = Files.readString(Path.of("src/main/java/com/osscli/cli/" + command + "Command.java"));
            int call = src.indexOf("generateJson(");
            assertTrue(call > 0, command + " no longer calls the local model; this test needs updating");

            String after = src.substring(call, Math.min(src.length(), call + 700));
            assertTrue(
                    after.contains("== null") || after.contains("isBlank()"),
                    command + " parses the model's answer without checking there was one");
        }
    }
}
