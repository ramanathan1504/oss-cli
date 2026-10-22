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

package com.osscli.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one front matter every note this program writes opens with.
 *
 * <p>Six keys come first, in this order, on every note: {@code title}, {@code kind}, {@code topic},
 * {@code date}, {@code source}, {@code tags}. Whatever a kind of note adds follows them. Each writer
 * used to lay out its own block with its own quoting rule, so an archive held a dozen shapes and a
 * title with a colon in it was invalid YAML in some of them and not others.
 */
public final class FrontMatter {

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final Map<String, String> fields = new LinkedHashMap<>();

    private FrontMatter() {}

    /** A block with the six keys every note carries. */
    public static FrontMatter of(
            String title, String kind, String topic, String date, String source, Collection<String> tags) {
        FrontMatter fm = new FrontMatter();
        fm.fields.put("title", scalar(title));
        fm.fields.put("kind", scalar(kind));
        fm.fields.put("topic", scalar(topic == null || topic.isBlank() ? "general" : topic));
        fm.fields.put("date", day(date));
        fm.fields.put("source", scalar(source));
        fm.fields.put("tags", list(tags));
        return fm;
    }

    /** Adds a key after the ones already there; a null or blank value is left out. */
    public FrontMatter put(String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            fields.put(key, scalar(value.toString()));
        }
        return this;
    }

    /** Adds a key whose value is a list. */
    public FrontMatter putList(String key, Collection<String> values) {
        if (values != null && !values.isEmpty()) {
            fields.put(key, list(values));
        }
        return this;
    }

    /** The block, delimiters included, followed by the blank line a body starts after. */
    public String render() {
        StringBuilder sb = new StringBuilder("---\n");
        fields.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append("---\n\n").toString();
    }

    /** A value on one line, quoted only when YAML would read it as something else. */
    public static String scalar(String value) {
        String v = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (v.isEmpty()) {
            return "\"\"";
        }
        if (plain(v)) {
            return v;
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Whether YAML reads the value back unchanged without quotes.
     *
     * <p>Only what YAML itself needs: an indicator character at the start, {@code ": "} or {@code " #"}
     * inside, a trailing colon, or a character that ends a flow-list item. A {@code #} or {@code :}
     * inside a word, as in {@code owner/name#12} or a URL, is plain text.
     */
    private static boolean plain(String v) {
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(v.charAt(0)) >= 0) {
            return false;
        }
        if (v.endsWith(":") || v.contains(": ") || v.contains(" #")) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if ("[]{},\"".indexOf(v.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** A flow list, each item quoted on the same rule as a scalar. */
    public static String list(Collection<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(scalar(v));
                }
            }
        }
        return "[" + String.join(", ", out) + "]";
    }

    /** The calendar day of a timestamp, or today's when there is none to read. */
    public static String day(String when) {
        if (when != null) {
            java.util.regex.Matcher m = DAY.matcher(when);
            if (m.find()) {
                return m.group();
            }
        }
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    /** A value as it was meant, with the quoting {@link #scalar} added taken off again. */
    public static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    /** The items of a flow list such as {@code [a, "b: c"]}, unquoted; a comma inside quotes stays. */
    public static List<String> items(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String v = raw.strip();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' && (i == 0 || v.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                add(out, cur);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        add(out, cur);
        return out;
    }

    private static void add(List<String> out, StringBuilder item) {
        String v = unquote(item.toString());
        if (v != null && !v.isBlank()) {
            out.add(v);
        }
    }
}
