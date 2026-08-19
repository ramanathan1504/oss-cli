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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That no page teaches a flag the program does not have.
 *
 * <p>Removing four options in 2.0.0 left {@code SETUP.md}, {@code COMMANDS.md}, {@code README.md}
 * and {@code OFFLINE.md} still printing {@code oss prompt 1666 --send-gemini} as the way to do it.
 * Every one of those is a line somebody copies, and what they get back is a usage block. Nothing
 * failed, because nothing was checking: the surface test compares the binary against its own
 * recorded surface, and the documentation tests count commands rather than reading the examples.
 *
 * <p>So the examples are read. Every line that looks like a command someone would type is parsed,
 * and each long option on it must exist on the command it is written against, according to
 * {@code release-surface.json} — which is generated from picocli's own model rather than
 * maintained by hand.
 *
 * <p>Dispatchers are skipped by design. Everything after {@code oss run} or {@code oss memory}
 * belongs to an attached extension, whose flags this repository does not know and must not
 * pretend to.
 */
class DocumentedFlagsTest {

    /** Pages a reader copies from. Anything else in the tree is source, not instruction. */
    private static final List<String> PAGES = List.of(
            "README.md", "COMMANDS.md", "SETUP.md", "OFFLINE.md", "INSTALL.md", "DEVELOPING.md", "site/index.html");

    /**
     * {@code oss <word> …} and the arguments belonging to it.
     *
     * <p>The tail stops at a backtick or at the next {@code oss}, and both bounds were put there by
     * false positives: one line of {@code INSTALL.md} names {@code oss ext add}, {@code oss ext
     * list} and {@code oss serve --uninstall}, and a tail that ran to the end of the line charged
     * {@code --uninstall} to {@code ext}. A test that reports a correct page as broken is a test
     * somebody deletes.
     */
    private static final Pattern INVOCATION =
            Pattern.compile("\\boss\\s+([a-z][a-z-]*)((?:[^`\\n]*?)(?=\\boss\\b|`|$))");

    private static final Pattern LONG_OPTION = Pattern.compile("--[a-z][a-z0-9-]+");

    /**
     * Everything after these belongs to somebody else's program.
     *
     * <p>{@code run} and {@code memory} hand the rest to an extension; the engine prefixes hand it
     * to the command that follows, which the parser below picks up instead.
     */
    private static final Set<String> DISPATCHERS = Set.of("run", "bench", "memory", "kb", "backlog");

    private static final Set<String> PREFIXES = Set.of("llm", "claude", "gemini", "codex");

    /** Accepted on every command, and not worth recording per command. */
    private static final Set<String> GLOBAL = Set.of("--help", "--version");

    @Test
    @DisplayName("every flag in every example exists on the command it is written against")
    void examplesUseFlagsThatExist() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(Path.of("release-surface.json")));
        List<String> wrong = new ArrayList<>();

        for (String page : PAGES) {
            Path path = Path.of(page);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            // Tags first, so an example split across <span>s reads as the line a visitor copies.
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (page.endsWith(".html")) {
                text = text.replaceAll("<[^>]*>", "");
            }
            int lineNumber = 0;
            for (String line : text.split("\n")) {
                lineNumber++;
                Matcher m = INVOCATION.matcher(line);
                while (m.find()) {
                    String command = m.group(1);
                    String rest = m.group(2) == null ? "" : m.group(2);

                    // `oss claude review --refresh` is a flag of review, so step over the prefix.
                    if (PREFIXES.contains(command)) {
                        Matcher inner = Pattern.compile("^\\s+([a-z][a-z-]*)").matcher(rest);
                        if (!inner.find()) {
                            continue;
                        }
                        command = inner.group(1);
                        rest = rest.substring(inner.end());
                    }
                    if (DISPATCHERS.contains(command)) {
                        continue;
                    }
                    Set<String> known = surface.commands().get(command);
                    if (known == null) {
                        continue;
                    }
                    Matcher flag = LONG_OPTION.matcher(rest);
                    while (flag.find()) {
                        String option = flag.group();
                        if (!GLOBAL.contains(option) && !known.contains(option)) {
                            wrong.add(page + ":" + lineNumber + "  oss " + command + " " + option);
                        }
                    }
                }
            }
        }

        assertTrue(
                wrong.isEmpty(),
                "these pages teach flags the program does not have:\n  " + String.join("\n  ", new TreeSet<>(wrong)));
    }

    @Test
    @DisplayName("no page names a command that was removed")
    void examplesNameCommandsThatExist() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(Path.of("release-surface.json")));
        Set<String> known = new TreeSet<>(surface.commands().keySet());
        known.addAll(PREFIXES);
        // Prose says "oss can", "oss reads", "oss is" -- English, not invocations. Only a word that
        // is also a plausible command name is worth checking, and the surface is the judge of that.
        List<String> unknown = new ArrayList<>();

        for (String page : PAGES) {
            Path path = Path.of(page);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            int lineNumber = 0;
            for (String line : Files.readString(path, StandardCharsets.UTF_8).split("\n")) {
                lineNumber++;
                // Only fenced or inline-code invocations, so ordinary sentences are left alone.
                if (!line.contains("`oss ") && !line.trim().startsWith("oss ")) {
                    continue;
                }
                Matcher m = INVOCATION.matcher(line);
                while (m.find()) {
                    String command = m.group(1);
                    if (!known.contains(command) && looksLikeACommand(command)) {
                        unknown.add(page + ":" + lineNumber + "  oss " + command);
                    }
                }
            }
        }

        assertTrue(
                unknown.isEmpty(),
                "these pages name commands that do not exist:\n  " + String.join("\n  ", new TreeSet<>(unknown)));
    }

    /** Filters the English out: "oss can", "oss will", "oss now" are sentences, not commands. */
    private static boolean looksLikeACommand(String word) {
        return Stream.of("can", "will", "now", "also", "does", "is", "was", "reads", "knows", "and", "the", "to", "it")
                .noneMatch(word::equals);
    }
}
