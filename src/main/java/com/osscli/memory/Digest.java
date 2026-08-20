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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What you actually worked out, as opposed to where you discussed it.
 *
 * <p>{@code map} answers "which notes mention log4j" — a count, sorted by hits. That is an index:
 * it tells you nothing, and you still have to open every note to find out what was learned. This
 * reads them instead of counting them.
 *
 * <p>It works because the notes have a shape. Of 623 notes in a real archive, 443 carry
 * {@code ## The Problem (What & Where)} and 444 carry {@code ## The Solution (How)} — written that
 * way by every harvester, which makes the structure worth mining rather than guessing at.
 *
 * <p><b>Evidence is ranked and labelled, never merged.</b> A note harvested from GitHub is what was
 * said in public and how it was resolved; a note from a model conversation is the reasoning that
 * got there. Both matter and which is which matters, so the public one sorts first and each says
 * where it came from. Collapsing them would produce a page that reads like one account when it is
 * two, and the reader could no longer tell what was agreed from what was thought.
 */
public final class Digest {

    private Digest() {}

    /** The three headings every harvester writes, in the order a reader wants them. */
    public static final List<String> SECTIONS =
            List.of("The Problem (What & Where)", "The Solution (How)", "The \"Why\" (Review Discussions)");

    /** One note's contribution to a digest: where it came from, and what it said. */
    public record Entry(String note, String origin, Map<String, String> sections) {

        /** Whether this is a record of what happened, rather than of what was considered. */
        public boolean isPublicRecord() {
            return "github".equals(origin);
        }
    }

    /**
     * Where a note came from, from its own file name.
     *
     * <p>Named rather than inferred from content: a harvester states its origin in the name it
     * writes, and reading that is a fact where classifying the prose would be a guess.
     */
    public static String originOf(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("gh-") || n.contains("oss-github") || n.startsWith("issue-")) {
            return "github";
        }
        if (n.contains("claude") || n.contains("ai-studio") || n.contains("aistudio") || n.contains("chatgpt")) {
            return "conversation";
        }
        return "note";
    }

    /**
     * The named sections of one note.
     *
     * <p>A heading with nothing under it is left out rather than recorded empty: a digest of empty
     * sections reads as though the work was done and produced nothing.
     */
    public static Map<String, String> sectionsOf(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String heading : SECTIONS) {
            int at = content.indexOf("## " + heading);
            if (at < 0) {
                continue;
            }
            int from = at + heading.length() + 3;
            int next = content.indexOf("\n## ", from);
            String body = (next < 0 ? content.substring(from) : content.substring(from, next)).strip();
            if (!body.isEmpty()) {
                out.put(heading, body);
            }
        }
        return out;
    }

    /**
     * A digest for one topic, public record first.
     *
     * <p>Sorting is the whole editorial decision here. Within each kind the newest note is not
     * automatically the best evidence, so order is left as given and only the kind decides.
     */
    public static List<Entry> rank(List<Entry> entries) {
        List<Entry> out = new ArrayList<>(entries);
        out.sort((a, b) -> Boolean.compare(b.isPublicRecord(), a.isPublicRecord()));
        return List.copyOf(out);
    }

    /** The page a reader can go through top to bottom. */
    public static String render(String topic, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(topic).append("\n\n");
        if (entries.isEmpty()) {
            sb.append("No note under this topic carries a problem or a solution yet.\n");
            sb.append("`oss memory map` shows which notes mention it.\n");
            return sb.toString();
        }
        sb.append(entries.size()).append(" note(s) with something worked out in them.\n");

        for (Entry e : rank(entries)) {
            sb.append("\n## ")
                    .append(e.note())
                    .append("  _(")
                    .append(e.origin())
                    .append(")_\n");
            for (Map.Entry<String, String> s : e.sections().entrySet()) {
                sb.append("\n**").append(s.getKey()).append("**\n\n");
                sb.append(s.getValue()).append('\n');
            }
        }
        return sb.toString();
    }
}
