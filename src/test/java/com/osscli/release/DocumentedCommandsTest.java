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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the offline story told in the documentation is the one the binary actually has.
 *
 * <p>Both the landing page and {@code OFFLINE.md} answer the same question -- which commands reach
 * the network -- by listing them out and then stating a total. Both were wrong: they said "six
 * reach the network, twenty-five never do" over a program with <b>thirty-six</b> commands, having
 * been written when there were thirty-one and never recounted. Five commands were simply absent
 * from the page, and a reader counting the chips got a different answer from the reader who read
 * the sentence above them.
 *
 * <p>This is the repository's own rule applied to prose: derive a count, never type one. The
 * partition is the assertion -- every command is on exactly one side, and the totals follow from
 * the lists rather than sitting beside them as a claim.
 */
class DocumentedCommandsTest {

    private static final Path ROOT = Path.of(".");
    private static final Path SURFACE = ROOT.resolve("release-surface.json");
    private static final Path OFFLINE = ROOT.resolve("OFFLINE.md");
    private static final Path SITE = ROOT.resolve("site/index.html");

    /**
     * The commands a reader can type, which is what the documentation is counting.
     *
     * <p>Nested verbs ({@code model --fetch}, {@code run list}) are excluded: the page lists the
     * command, and a page that enumerated every verb would be a different document.
     */
    private static Set<String> everyCommand() throws IOException {
        Surface surface = Surface.fromJson(Files.readString(SURFACE));
        Set<String> top = new TreeSet<>();
        for (String name : surface.commands().keySet()) {
            if (!name.contains(" ")) {
                top.add(name);
            }
        }
        // help and the built-in flags are not commands anybody reads about.
        top.remove("help");
        return top;
    }

    // ==========================================
    // OFFLINE.md
    // ==========================================

    @Test
    @DisplayName("OFFLINE.md accounts for every command, on exactly one side")
    void offlineDocPartitionsEveryCommand() throws IOException {
        String doc = Files.readString(OFFLINE);
        Set<String> all = everyCommand();

        Set<String> networked = backtickedTableKeys(doc);
        Set<String> offline = fencedWords(doc, "The other twenty-nine need no network");

        assertOverlapIsEmpty(networked, offline, "OFFLINE.md");

        Set<String> covered = new TreeSet<>(networked);
        covered.addAll(offline);

        Set<String> missing = new TreeSet<>(all);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(), "OFFLINE.md never mentions: " + missing);

        Set<String> invented = new TreeSet<>(covered);
        invented.removeAll(all);
        assertTrue(invented.isEmpty(), "OFFLINE.md lists commands that do not exist: " + invented);
    }

    @Test
    @DisplayName("OFFLINE.md's stated totals match the lists underneath them")
    void offlineDocTotalsAreTrue() throws IOException {
        String doc = Files.readString(OFFLINE);
        int all = everyCommand().size();
        int networked = backtickedTableKeys(doc).size();

        assertTrue(
                doc.contains("Of " + all + " commands"),
                "the headline should say 'Of " + all + " commands'; it says: " + firstLineWith(doc, "commands"));
        assertEquals(
                all - networked,
                numberWord(doc, "The other ", " need no network"),
                "the offline total must be what is left after the networked ones");
    }

    // ==========================================
    // The landing page
    // ==========================================

    @Test
    @DisplayName("the landing page's two lists partition every command too")
    void sitePartitionsEveryCommand() throws IOException {
        String html = Files.readString(SITE);
        Set<String> all = everyCommand();

        Set<String> networked = boardChips(html, true);
        Set<String> offline = boardChips(html, false);

        assertOverlapIsEmpty(networked, offline, "the landing page");

        Set<String> covered = new TreeSet<>(networked);
        covered.addAll(offline);
        assertEquals(all, covered, "the landing page's chips must be exactly the command set");
    }

    @Test
    @DisplayName("the landing page's counts are the length of its own lists")
    void siteCountsMatchItsLists() throws IOException {
        String html = Files.readString(SITE);
        int all = everyCommand().size();
        int networked = boardChips(html, true).size();

        // The board counts itself in the browser, so the only figure typed into the
        // page is the one it starts at -- and that must be the whole command set.
        assertTrue(
                html.contains("id=\"cable-num\">" + all + "<"),
                "the board should start reading '" + all + "', the whole command set");
        assertTrue(
                html.contains("<span class=\"cable-of\">of " + all + " commands still work</span>"),
                "the board's caption should read 'of " + all + " commands still work'");
        assertTrue(
                html.contains((all - networked) + " of the " + all + " commands never need it"),
                "the hero's claim should read '" + (all - networked) + " of the " + all + " commands never need it'");
    }

    // ==========================================
    // Extraction
    // ==========================================

    /** The first backticked word of every markdown table row, which is how the network table keys it. */
    private static Set<String> backtickedTableKeys(String doc) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\|\\s*`([a-z-]+)[^`]*`").matcher(doc);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The words inside the fenced block that follows a heading line. */
    private static Set<String> fencedWords(String doc, String after) {
        int at = doc.indexOf(after);
        assertTrue(at >= 0, "cannot find the sentence '" + after + "' in OFFLINE.md");
        int open = doc.indexOf("```", at);
        int close = doc.indexOf("```", open + 3);
        assertTrue(open >= 0 && close > open, "no fenced list follows '" + after + "'");

        Set<String> out = new TreeSet<>();
        for (String word : doc.substring(open + 3, close).trim().split("\\s+")) {
            if (!word.isBlank()) {
                out.add(word);
            }
        }
        return out;
    }

    /** The {@code <li>} contents of the first list whose container carries {@code marker}. */
    /**
     * The chips on the cable board, split by whether they reach the network.
     *
     * <p>One list, two classes -- {@code <li class="cmd net">} against
     * {@code <li class="cmd">} -- because the board is a single switchable set rather than two
     * columns. Reading the class is what lets the page animate one side without the count and the
     * markup being able to drift apart.
     */
    private static Set<String> boardChips(String html, boolean networked) {
        int open = html.indexOf("<ul class=\"cmd-board\"");
        assertTrue(open >= 0, "cannot find the cable board on the page");
        int close = html.indexOf("</ul>", open);
        assertTrue(close > open, "the cable board has no closing </ul>");

        Set<String> out = new LinkedHashSet<>();
        Matcher m =
                Pattern.compile("<li class=\"cmd( net)?\"[^>]*>([^<]+)</li>").matcher(html.substring(open, close));
        while (m.find()) {
            boolean isNet = m.group(1) != null;
            if (isNet == networked) {
                // `model --fetch` is the verb that reaches the network; the command is `model`.
                out.add(m.group(2).trim().split("\\s+")[0]);
            }
        }
        return new TreeSet<>(out);
    }

    private static Set<String> listItems(String html, String marker) {
        int at = html.indexOf(marker);
        assertTrue(at >= 0, "cannot find a container matching '" + marker + "' on the page");
        int open = html.indexOf("<ul>", at);
        int close = html.indexOf("</ul>", open);
        assertTrue(open >= 0 && close > open, "no <ul> follows '" + marker + "'");

        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<li>([^<]+)</li>").matcher(html.substring(open, close));
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return new TreeSet<>(out);
    }

    /** Reads "twenty-nine" and friends back into a number, for the totals written as words. */
    private static int numberWord(String doc, String before, String after) {
        Matcher m = Pattern.compile(Pattern.quote(before) + "([a-z-]+)" + Pattern.quote(after))
                .matcher(doc);
        assertTrue(m.find(), "cannot find a total between '" + before + "' and '" + after + "'");
        String word = m.group(1);
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty"};
        String[] units = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};

        String[] parts = word.split("-");
        int value = 0;
        for (String part : parts) {
            int unit = java.util.Arrays.asList(units).indexOf(part);
            int ten = java.util.Arrays.asList(tens).indexOf(part);
            if (unit >= 0) {
                value += unit;
            } else if (ten >= 0) {
                value += ten * 10;
            } else {
                throw new AssertionError("'" + word + "' is not a number this test can read");
            }
        }
        return value;
    }

    private static void assertOverlapIsEmpty(Set<String> a, Set<String> b, String where) {
        Set<String> both = new TreeSet<>(a);
        both.retainAll(b);
        assertTrue(both.isEmpty(), where + " puts these on both sides: " + both);
    }

    private static String firstLineWith(String doc, String needle) {
        for (String line : doc.lines().toList()) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "(no line contains '" + needle + "')";
    }
}
