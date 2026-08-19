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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.AppPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where your notes live, and what you measure them against — as a file, not a repository.
 *
 * <p>The knowledge base used to be somebody's checkout: a folder of Python that knew one archive
 * path, indexed through one macOS application, and harvested one person's accounts. Which meant the
 * honest answer to "can a new user have a knowledge base" was "clone this and edit the paths".
 *
 * <p>The capability belongs in the tool and the wiring belongs to you. Everything general — filing,
 * indexing, searching, the topic map, coverage — is built in and works with nothing configured, over
 * {@code ~/.oss-cli/memory}. This file is the other half: a few lines saying where your archive
 * actually is and what you are trying to learn.
 *
 * <pre>{@code
 * {
 *   "archive": "~/Library/Mobile Documents/…/Devon Capture",
 *   "topics":  { "log4j": ["log4j", "appender", "layout"], "java": ["jvm", "garbage collect"] },
 *   "yardsticks": {
 *     "log4j": ["Appenders", "Layouts", "Filters", "Lookups", "Garbage-free logging"]
 *   }
 * }
 * }</pre>
 *
 * <p>Read from {@code kb.json} beside the archive, or from {@code ~/.oss-cli/kb.json}. Nothing here
 * is required: with no file at all the defaults hold, which is the state every install starts in and
 * most stay in.
 *
 * <p><b>A yardstick is the outside opinion.</b> Counting your own notes can only report what you
 * have written, so a base with nothing on Lookups will happily call itself complete. The yardstick
 * is a list of what a technology documents — its manual's contents — and coverage is measured
 * against that rather than against itself.
 */
public final class KnowledgePack {

    /** Where the archive is when nothing says otherwise: the store the built-in memory already uses. */
    public static final Path DEFAULT_ARCHIVE = BuiltinMemory.DIR;

    private final Path archive;
    private final Map<String, List<String>> topics;
    private final Map<String, List<String>> yardsticks;

    private KnowledgePack(Path archive, Map<String, List<String>> topics, Map<String, List<String>> yardsticks) {
        this.archive = archive;
        this.topics = topics;
        this.yardsticks = yardsticks;
    }

    /**
     * The configuration in force, which may be no configuration at all.
     *
     * <p>Never throws for an absent file. A missing {@code kb.json} is the normal state, and a
     * knowledge base that refuses to start until it is configured is one nobody starts.
     */
    public static KnowledgePack load() {
        for (Path candidate : List.of(AppPaths.BASE_DIR.resolve("kb.json"), DEFAULT_ARCHIVE.resolve("kb.json"))) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return parse(new ObjectMapper().readTree(Files.readString(candidate, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    // Named, then ignored. A malformed file should not take the memory down; it
                    // should cost the configuration it failed to express and say which file to fix.
                    System.err.println("  kb.json could not be read (" + e.getMessage() + ") — using defaults");
                }
            }
        }
        return new KnowledgePack(DEFAULT_ARCHIVE, Map.of(), Map.of());
    }

    private static KnowledgePack parse(JsonNode node) {
        String path = node.path("archive").asText("");
        Path archive = path.isBlank() ? DEFAULT_ARCHIVE : expand(path);
        return new KnowledgePack(archive, listsOf(node.path("topics")), listsOf(node.path("yardsticks")));
    }

    /** {@code ~} is what a person writes, and Java is the only thing that does not know it. */
    private static Path expand(String path) {
        String home = System.getProperty("user.home", "");
        return Paths.get(path.startsWith("~/") ? home + path.substring(1) : path);
    }

    private static Map<String, List<String>> listsOf(JsonNode node) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            List<String> values = new ArrayList<>();
            e.getValue().forEach(v -> values.add(v.asText()));
            out.put(e.getKey(), values);
        });
        return out;
    }

    /** The folder holding the notes. */
    public Path archive() {
        return archive;
    }

    /** Topic name to the terms that identify it, or empty when none are configured. */
    public Map<String, List<String>> topics() {
        return topics;
    }

    /** Technology to the areas its manual documents — the outside opinion coverage is scored against. */
    public Map<String, List<String>> yardsticks() {
        return yardsticks;
    }

    /** True when this is the shipped default rather than something the user wrote. */
    public boolean isDefault() {
        return topics.isEmpty() && yardsticks.isEmpty() && archive.equals(DEFAULT_ARCHIVE);
    }
}
