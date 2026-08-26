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
package com.osscli.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the decisions about output stay made.
 *
 * <p>They were not made once and then kept; they were made twenty-three times, which is how this
 * repository ended up with equals-sign rules at five different widths, a box-drawing header, and a
 * row of dashes, all in the same program. Every one of them was reasonable on its own — somebody
 * wanted their heading to stand out from the last one — and the sum was a tool that looked like it
 * had been assembled from other tools.
 *
 * <p>So the rules are asserted rather than written down. A new command that reaches for its own
 * separator, or reads the whole corpus in silence, fails here rather than in a screenshot months
 * later.
 */
class OutputIsDesignedTest {

    private static final Path SOURCE = Path.of("src/main/java/com/osscli");

    /** A rule printed on its own line, in any of the widths somebody chose. */
    private static final Pattern SEPARATOR = Pattern.compile("(println|info)\\(\\s*\"[=\\-\\u2500]{5,}\"");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCE)) {
            return walk.filter(f -> f.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    @Test
    @DisplayName("no command draws its own rule")
    void separatorsAreNotReinvented() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : javaFiles()) {
            // Out is where the one rule lives, and Picker draws a full-screen frame of its own.
            String name = file.getFileName().toString();
            if (name.equals("Out.java") || name.equals("Picker.java")) {
                continue;
            }
            String text = Files.readString(file);
            if (SEPARATOR.matcher(text).find()) {
                offences.add(name);
            }
        }
        assertTrue(
                offences.isEmpty(),
                "these print a rule of their own instead of using Out.title/Out.section — that is how "
                        + "one program came to have twenty-three of them: " + offences);
    }

    @Test
    @DisplayName("a command that reads the whole corpus says so while it does")
    void slowWorkIsNotSilent() throws IOException {
        List<String> silent = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (!name.endsWith("Command.java")) {
                continue;
            }
            String text = Files.readString(file);
            // Loading every issue or every vector for a repository is seconds on a real store.
            boolean readsEverything = text.contains("SqliteStorage.loadIssues(") || text.contains("loadEmbeddings(");
            if (readsEverything && !text.contains("Live.start")) {
                silent.add(name);
            }
        }
        assertTrue(
                silent.isEmpty(),
                "these read the whole corpus with nothing on screen, which is indistinguishable from "
                        + "being hung — wrap it in Live.start: " + silent);
    }

    @Test
    @DisplayName("no model is asked without saying so on screen")
    void waitingOnAModelIsNeverSilent() throws IOException {
        // The worst silence in the tool, because it is the longest. `oss claude review` printed
        // every fact it had, said it was handing the diff over, and then showed nothing for as
        // long as the model took -- measured at four and a half minutes on a twenty-two file
        // change, with the child process alive and working the whole time. A terminal that has
        // printed a promise and gone quiet cannot be told apart from one that has hung, and the
        // reader has no way to know whether to wait or press ctrl-c.
        List<String> silent = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            // Only the model clients. GitHubClient also blocks on the network, and is deliberately
            // exempt: it makes hundreds of short requests where one call is milliseconds, so a
            // status line per request would flicker rather than inform -- and every command that
            // uses it already opens its own Live around the whole fetch. The rule here is about a
            // SINGLE call that blocks for minutes, which is only ever a model.
            if (!name.endsWith("Client.java") || !file.toString().contains("/llm/")) {
                continue;
            }
            String text = Files.readString(file);
            boolean waits = text.contains("httpClient.send(") || text.contains("waitFor(");
            if (waits && !text.contains("Live.start")) {
                silent.add(name);
            }
        }
        assertTrue(
                silent.isEmpty(),
                "these wait on a model with nothing on screen, which is indistinguishable from a "
                        + "hang -- wrap the call in Live.start: " + silent);
    }

    @Test
    @DisplayName("the status line still carries what it is doing, and a quip")
    void progressSaysSomething() throws IOException {
        String live = Files.readString(SOURCE.resolve("ui/Live.java"));
        assertTrue(live.contains("QUIPS"), "the wait should be bearable");
        assertTrue(live.contains("OSS_NO_QUIPS"), "and refusable, for somebody reading a build log at 3am");
        assertTrue(live.contains("elapsed()"), "a wait with no elapsed time cannot be judged");
    }
}
