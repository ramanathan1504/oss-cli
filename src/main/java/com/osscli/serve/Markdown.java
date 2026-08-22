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
package com.osscli.serve;

import java.util.ArrayList;
import java.util.List;

/**
 * Enough markdown to read this project's own documentation, and no more.
 *
 * <p>Deliberately not a library. The documents being rendered are five files in this repository
 * whose syntax is known: headings, fenced code, tables, lists, links, bold and inline code. A
 * general parser would add a dependency and a shading rule to the jar for constructs these files
 * never use, and this tool's own rule is to remove a prerequisite rather than configure one.
 *
 * <p><b>Everything is escaped first.</b> The input is trusted — it ships in the jar — but the page
 * it lands on is served to a browser, and a renderer that escapes only sometimes is one nobody can
 * reason about later. Markup is put back deliberately, after escaping, and only for the constructs
 * below.
 */
public final class Markdown {

    private Markdown() {}

    /** Render one document. Never throws: an unreadable line is shown, not swallowed. */
    public static String toHtml(String markdown) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown == null ? new String[0] : markdown.split("\n", -1);

        boolean inCode = false;
        boolean inList = false;
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.strip().startsWith("```")) {
                if (inCode) {
                    out.add("</code></pre>");
                    inCode = false;
                } else {
                    inList = closeList(out, inList);
                    inTable = closeTable(out, inTable);
                    out.add("<pre><code>");
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                // Escaped and otherwise untouched: a code block that got its asterisks turned into
                // emphasis is a code block that lies about what to type.
                out.add(escape(line));
                continue;
            }

            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                inList = closeList(out, inList);
                inTable = closeTable(out, inTable);
                continue;
            }

            // A table is a row starting and ending with a pipe, whose next line is the separator.
            if (trimmed.startsWith("|") && !inTable && i + 1 < lines.length && isSeparator(lines[i + 1])) {
                inList = closeList(out, inList);
                out.add("<table><thead><tr>");
                for (String cell : cells(trimmed)) {
                    out.add("<th>" + inline(cell) + "</th>");
                }
                out.add("</tr></thead><tbody>");
                inTable = true;
                i++; // the separator itself is not a row
                continue;
            }
            if (inTable && trimmed.startsWith("|")) {
                out.add("<tr>");
                for (String cell : cells(trimmed)) {
                    out.add("<td>" + inline(cell) + "</td>");
                }
                out.add("</tr>");
                continue;
            }
            inTable = closeTable(out, inTable);

            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes > 0 && hashes <= 6 && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ') {
                inList = closeList(out, inList);
                String text = trimmed.substring(hashes + 1).strip();
                // An id per heading, so the contents list on the page can link into the document.
                out.add("<h" + hashes + " id=\"" + slug(text) + "\">" + inline(text) + "</h" + hashes + ">");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) {
                    out.add("<ul>");
                    inList = true;
                }
                out.add("<li>" + inline(trimmed.substring(2)) + "</li>");
                continue;
            }
            inList = closeList(out, inList);

            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                out.add("<hr>");
                continue;
            }
            out.add("<p>" + inline(trimmed) + "</p>");
        }

        if (inCode) {
            out.add("</code></pre>");
        }
        closeList(out, inList);
        closeTable(out, inTable);
        return String.join("\n", out);
    }

    /** A heading's anchor: lowercase, words joined by hyphens, nothing else kept. */
    public static String slug(String heading) {
        String s = heading.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "")
                .strip()
                .replaceAll("\\s+", "-");
        return s.isEmpty() ? "section" : s;
    }

    private static boolean isSeparator(String line) {
        return line.strip().matches("^\\|[\\s:|-]+\\|$");
    }

    private static List<String> cells(String row) {
        String inner = row.strip();
        inner = inner.substring(1, inner.length() - (inner.endsWith("|") ? 1 : 0));
        List<String> out = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            out.add(cell.strip());
        }
        return out;
    }

    private static boolean closeList(List<String> out, boolean inList) {
        if (inList) {
            out.add("</ul>");
        }
        return false;
    }

    private static boolean closeTable(List<String> out, boolean inTable) {
        if (inTable) {
            out.add("</tbody></table>");
        }
        return false;
    }

    /**
     * Inline markup, applied after escaping.
     *
     * <p>Code spans first, and their contents are not searched for emphasis afterwards — otherwise
     * a documented flag like {@code --send-*} becomes italics in the one place the exact characters
     * matter most.
     */
    static String inline(String text) {
        String s = escape(text);

        // Code spans are lifted out before anything else runs, and put back last. Wrapping them in
        // <code> and then continuing was not enough: the emphasis pass matched straight across two
        // adjacent spans, so `--send-*` and `*.md` on one line turned the text between them into
        // italics and destroyed both flags. The comment above claimed this was handled; the test
        // proved it was not.
        List<String> spans = new ArrayList<>();
        java.util.regex.Matcher code =
                java.util.regex.Pattern.compile("`([^`]+)`").matcher(s);
        StringBuilder held = new StringBuilder();
        while (code.find()) {
            spans.add(code.group(1));
            // A placeholder no document contains and no later pattern matches.
            code.appendReplacement(
                    held, java.util.regex.Matcher.quoteReplacement("\u0000" + (spans.size() - 1) + "\u0000"));
        }
        code.appendTail(held);
        s = held.toString();

        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("(?<![*\\w])\\*([^*]+)\\*(?![*\\w])", "<em>$1</em>");

        for (int i = 0; i < spans.size(); i++) {
            s = s.replace("\u0000" + i + "\u0000", "<code>" + spans.get(i) + "</code>");
        }
        return s;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
