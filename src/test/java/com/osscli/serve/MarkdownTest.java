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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enough markdown to read this project's own documents, rendered without lying about them.
 *
 * <p>The risk in a small renderer is not that it fails — it is that it quietly mangles one
 * construct and the page still looks fine. These check the constructs these five documents
 * actually use, and then render all five to prove the real input survives.
 */
class MarkdownTest {

    @Test
    @DisplayName("a code block is escaped and otherwise left exactly alone")
    void codeIsNotTouched() {
        String html = Markdown.toHtml("```\noss review --send-* <n>\nif (a < b && c > d) {}\n```");

        assertTrue(html.contains("--send-*"), "asterisks in a command must not become emphasis:\n" + html);
        assertTrue(html.contains("&lt;n&gt;"), "and angle brackets must be escaped:\n" + html);
        assertFalse(html.contains("<em>"), html);
    }

    @Test
    @DisplayName("an inline code span keeps its asterisks too")
    void inlineCodeKeepsItsCharacters() {
        String html = Markdown.toHtml("Use `--send-*` and `*.md` for that.");

        assertTrue(html.contains("<code>--send-*</code>"), html);
        assertFalse(html.contains("<em>"), "the flag is not italics:\n" + html);
    }

    @Test
    @DisplayName("headings carry an id, so a contents list can link into the document")
    void headingsAreAnchored() {
        String html = Markdown.toHtml("## Working offline\n\ntext");

        assertTrue(html.contains("<h2 id=\"working-offline\">Working offline</h2>"), html);
    }

    @Test
    @DisplayName("a table becomes a table, and its separator row does not become one")
    void tablesRender() {
        String html = Markdown.toHtml("| Command | Needs |\n|---|---|\n| `sync` | token |\n");

        assertTrue(html.contains("<th>Command</th>"), html);
        assertTrue(html.contains("<td><code>sync</code></td>"), html);
        assertFalse(html.contains("---"), "the separator row must not appear as content:\n" + html);
    }

    @Test
    @DisplayName("html in the source is shown, never executed")
    void htmlIsEscaped() {
        String html = Markdown.toHtml("A <script>alert(1)</script> in prose.");

        assertFalse(html.contains("<script>"), "the page is served to a browser:\n" + html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    @DisplayName("bold, links and lists survive")
    void theRestOfWhatTheseDocsUse() {
        String html = Markdown.toHtml("- **A** point\n- See [the docs](https://ubuos.com)\n");

        assertTrue(html.contains("<ul>"), html);
        assertTrue(html.contains("<strong>A</strong>"), html);
        assertTrue(html.contains("<a href=\"https://ubuos.com\">the docs</a>"), html);
    }

    @Test
    @DisplayName("every document that ships is rendered, and none comes out empty")
    void theRealDocumentsRender() throws IOException {
        // The point of the whole exercise: these five are what the local page shows. A renderer
        // that passes invented examples and mangles the real files would pass every test above.
        for (String name : new String[] {"README.md", "COMMANDS.md", "OFFLINE.md", "SETUP.md", "CONTRIBUTING.md"}) {
            try (InputStream in = Markdown.class.getResourceAsStream("/docs/" + name)) {
                assertTrue(in != null, name + " is not in the jar — the page would be empty where it ships");
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String html = Markdown.toHtml(source);

                assertFalse(html.isBlank(), name + " rendered to nothing");
                assertTrue(html.contains("<h1") || html.contains("<h2"), name + " lost every heading");
                assertFalse(html.contains("<script>"), name + " produced an executable tag");
                assertEquals(
                        count(html, "<pre><code>"),
                        count(html, "</code></pre>"),
                        name + " has an unbalanced code fence, so the rest of the page is inside it");
            }
        }
    }

    @Test
    @DisplayName("a hard-wrapped paragraph is one paragraph, not one per line")
    void wrappedLinesAreOneBlock() {
        // Every file in this repository is wrapped at about eighty characters. Emitting a <p> per
        // line turned COMMANDS.md into 363 of them, each with a margin above and below, so the
        // manual read as a ragged column of one-line blocks. No stylesheet can undo that; the
        // shredding happens in the renderer.
        String html = Markdown.toHtml("Since 3.0, oss --help lists the dozen that carry\n"
                + "the daily work plus the four engine prefixes,\n"
                + "rather than all forty at once.\n\nA second paragraph.");

        assertEquals(2, count(html, "<p>"), "a wrapped paragraph was split:\n" + html);
        assertTrue(html.contains("carry the daily work"), "the join lost the space between lines:\n" + html);
    }

    @Test
    @DisplayName("a wrapped bullet stays inside its bullet")
    void wrappedListItemsStayInTheList() {
        // The tail of a wrapped bullet used to close the item and open a paragraph after it, so
        // half the sentence sat outside the list it belonged to.
        String html = Markdown.toHtml("- the first item, which runs on\n  past the end of its line\n- second");

        assertEquals(2, count(html, "<li>"), html);
        assertEquals(0, count(html, "<p>"), "a continuation escaped the list:\n" + html);
        assertTrue(html.contains("runs on past the end"), html);
    }

    @Test
    @DisplayName("a table can scroll without taking the page with it")
    void tablesGetTheirOwnSurface() {
        String html = Markdown.toHtml("| Command | Does |\n|---|---|\n| `sync` | reads |");

        assertTrue(html.contains("<div class=\"tw\"><table>"), html);
        assertEquals(count(html, "<div class=\"tw\">"), count(html, "</table></div>"), html);
    }

    @Test
    @DisplayName("the contents rail is built from the same slugs the headings carry")
    void headingsMatchTheirAnchors() {
        String src = "# Title\n\n## Working offline\n\ntext\n\n### A `code` heading\n\nmore";
        String html = Markdown.toHtml(src);

        java.util.List<String[]> heads = Markdown.headings(src);
        assertEquals(2, heads.size(), "h1 belongs to the page, h2 and h3 to the rail");
        for (String[] h : heads) {
            // The link and the target come from one function, so they cannot drift.
            assertTrue(html.contains("id=\"" + h[1] + "\""), "no heading carries " + h[1] + ":\n" + html);
        }
        assertEquals("A code heading", heads.get(1)[2], "the rail is a list of links, not markup");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
