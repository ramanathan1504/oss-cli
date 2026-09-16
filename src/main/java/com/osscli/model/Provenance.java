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
package com.osscli.model;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which conversation a note came out of, read from the note itself.
 *
 * <p>A note filed from a terminal session is a record of a conversation that is still on disk and
 * can still be reopened — {@code claude --resume <id>}, {@code codex resume <id>}. The id was
 * written into the note and nowhere else, so finding a note by searching told you what was decided
 * and not where to go on with it: the id had to be found by opening the file and scrolling to a
 * fence in the body.
 *
 * <p>Read from the content rather than passed in, for the same reason {@link Tier} is: every writer
 * records it and none of them has to remember to.
 */
public record Provenance(String sessions, String tool) {

    private static final Provenance NONE = new Provenance("", "");

    /** Nothing known about where this note came from. */
    public static Provenance none() {
        return NONE;
    }

    public boolean present() {
        return !sessions.isBlank();
    }

    /** The first session id, which is the one a single-session note has. */
    public String firstSession() {
        int comma = sessions.indexOf(',');
        return comma < 0 ? sessions : sessions.substring(0, comma).strip();
    }

    /**
     * What a note's frontmatter says about the conversation behind it.
     *
     * <p>Four spellings, because two note kinds grew separately: a note filed from one session
     * carries {@code session:} and {@code tool:}, while a running log holds many and carries
     * {@code sessions:} and {@code tools:}. Both are read here so that neither has to change to be
     * understood.
     */
    public static Provenance of(String content) {
        if (content == null || !content.startsWith("---")) {
            return NONE;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return NONE;
        }
        Set<String> sessions = new LinkedHashSet<>();
        Set<String> tools = new LinkedHashSet<>();
        for (String line : content.substring(3, end).split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("session:") || lower.startsWith("sessions:")) {
                add(sessions, line.substring(line.indexOf(':') + 1));
            } else if (lower.startsWith("tool:") || lower.startsWith("tools:")) {
                add(tools, line.substring(line.indexOf(':') + 1));
            }
        }
        if (sessions.isEmpty() && tools.isEmpty()) {
            return NONE;
        }
        return new Provenance(String.join(", ", sessions), String.join(", ", tools));
    }

    private static void add(Set<String> into, String value) {
        for (String part : value.replace("[", "").replace("]", "").split(",")) {
            String cleaned = part.strip();
            // A frontmatter value can be quoted, and an id that keeps its quotes is an id that
            // matches nothing when somebody pastes it after --resume.
            if (cleaned.length() > 1 && (cleaned.startsWith("\"") || cleaned.startsWith("'"))) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
            }
            if (!cleaned.isEmpty()) {
                into.add(cleaned);
            }
        }
    }
}
