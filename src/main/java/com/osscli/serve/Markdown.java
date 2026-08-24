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

    /**
     * Render one document. Never throws: an unreadable line is shown, not swallowed.
     *
     * <p><b>A paragraph is a block, not a line.</b> This used to emit one {@code <p>} per source
     * line, so a paragraph hard-wrapped at 80 characters — which every file in this repository is —
     * arrived as three or four separate paragraphs with a margin between each. COMMANDS.md came out
     * as three hundred and sixty-three of them: the manual read as a ragged column of one-line
     * blocks, and no amount of styling fixes it, because the shredding happens here. Consecutive
     * lines now accumulate and are joined with a space, which is what a blank-line-separated
     * paragraph means.
     *
     * <p>The same applies inside a list. A wrapped bullet used to close the item and open a
     * paragraph after it, leaving the tail of a sentence sitting outside the list it belonged to.
     */
    public static String toHtml(String markdown) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown == null ? new String[0] : markdown.split("\n", -1);

        boolean inCode = false;
        boolean inList = false;
        boolean inTable = false;
        // The block being accumulated. Exactly one of these is ever non-empty.
        List<String> para = new ArrayList<>();
        List<String> item = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.strip().startsWith("```")) {
                if (inCode) {
                    out.add("</code></pre>");
                    inCode = false;
                } else {
                    flushPara(out, para);
                    inList = closeList(out, item, inList);
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
                flushPara(out, para);
                inList = closeList(out, item, inList);
                inTable = closeTable(out, inTable);
                continue;
            }

            // A table is a row starting and ending with a pipe, whose next line is the separator.
            if (trimmed.startsWith("|") && !inTable && i + 1 < lines.length && isSeparator(lines[i + 1])) {
                flushPara(out, para);
                inList = closeList(out, item, inList);
                // Wrapped, so a table too wide for the column scrolls inside its own surface
                // instead of pushing the whole page sideways.
                out.add("<div class=\"tw\"><table><thead><tr>");
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
                flushPara(out, para);
                inList = closeList(out, item, inList);
                String text = trimmed.substring(hashes + 1).strip();
                // An id per heading, so the contents list on the page can link into the document.
                out.add("<h" + hashes + " id=\"" + slug(text) + "\">" + inline(text) + "</h" + hashes + ">");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushPara(out, para);
                if (!inList) {
                    out.add("<ul>");
                    inList = true;
                } else {
                    flushItem(out, item);
                }
                item.add(trimmed.substring(2).strip());
                continue;
            }

            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                flushPara(out, para);
                inList = closeList(out, item, inList);
                out.add("<hr>");
                continue;
            }

            // A plain line. Inside a list it continues the bullet above it -- markdown's lazy
            // continuation -- and everywhere else it continues the paragraph.
            (inList ? item : para).add(trimmed);
        }

        if (inCode) {
            out.add("</code></pre>");
        }
        flushPara(out, para);
        closeList(out, item, inList);
        closeTable(out, inTable);
        return String.join("\n", out);
    }

    /**
     * The headings a reader can jump to, for the contents rail beside the document.
     *
     * <p>Read from the source rather than from the rendered HTML: the slug is computed by
     * {@link #slug} in both places, so the anchor the rail links to and the id the heading carries
     * come from one function and cannot disagree.
     */
    public static List<String[]> headings(String markdown) {
        List<String[]> out = new ArrayList<>();
        boolean inCode = false;
        for (String raw : (markdown == null ? "" : markdown).split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                continue;
            }
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') {
                hashes++;
            }
            // h2 and h3 only. h1 is the document's own title, already at the top of the page, and
            // h4 down is detail that turns a rail into a second copy of the document.
            if ((hashes == 2 || hashes == 3) && hashes < line.length() && line.charAt(hashes) == ' ') {
                String text = line.substring(hashes + 1).strip();
                out.add(new String[] {String.valueOf(hashes), slug(text), stripInline(text)});
            }
        }
        return out;
    }

    /** A heading as plain text: the rail is a list of links, not a place for nested markup. */
    private static String stripInline(String text) {
        return escape(text.replace("`", "").replaceAll("\\*\\*?", ""));
    }

    private static void flushPara(List<String> out, List<String> para) {
        if (!para.isEmpty()) {
            out.add("<p>" + inline(String.join(" ", para)) + "</p>");
            para.clear();
        }
    }

    private static void flushItem(List<String> out, List<String> item) {
        if (!item.isEmpty()) {
            out.add("<li>" + inline(String.join(" ", item)) + "</li>");
            item.clear();
        }
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

    private static boolean closeList(List<String> out, List<String> item, boolean inList) {
        flushItem(out, item);
        if (inList) {
            out.add("</ul>");
        }
        return false;
    }

    private static boolean closeTable(List<String> out, boolean inTable) {
        if (inTable) {
            out.add("</tbody></table></div>");
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
