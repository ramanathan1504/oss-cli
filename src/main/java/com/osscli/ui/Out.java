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

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * One way for this program to say something.
 *
 * <p>Before this there were 1,019 places that printed and no agreement between any of them: 449
 * {@code System.out.println}, 570 {@code LOGGER.info} used as a display layer, and twenty-three
 * distinct separator strings -- {@code ====} at five different widths, a box-drawing header, a row
 * of dashes. Colour existed in three files out of two hundred. Every command looked like a
 * different program because every command was written by someone deciding again.
 *
 * <p>The palette is the site's, deliberately: brass for what you typed or must type, teal for a
 * good outcome, rust for a bad one. Somebody moving between {@code oss review} in a terminal and
 * the same review on {@code localhost:1504} should not have to learn two colour schemes for one
 * tool.
 *
 * <h2>When it stays plain</h2>
 *
 * Colour is an offer, not a decision. It is withheld when stdout is not a terminal -- so a redirect
 * collects text and not escape codes, which is the difference between a report you can read and one
 * full of escape sequences -- and when {@code NO_COLOR} is set, and when {@code TERM} says
 * {@code dumb}. {@code CLICOLOR_FORCE} overrides all of that, for the case where someone genuinely
 * is piping into something that renders colour.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * No spinner, no progress bar, no box drawn around content. A box has to be closed, which means
 * knowing how wide the content is before printing any of it, which means buffering -- and buffering
 * is why a long command shows nothing for a minute and then everything at once.
 */
public final class Out {

    private Out() {}

    // 256-colour rather than truecolor: every terminal worth supporting has had 256 for twenty
    // years, and the three shades below are the site's to within a rounding error.
    private static final String BRASS = "\u001b[38;5;179m"; // what you typed, or must type
    private static final String TEAL = "\u001b[38;5;79m"; // it worked
    private static final String RUST = "\u001b[38;5;167m"; // it did not
    private static final String DIM = "\u001b[2m";
    private static final String BOLD = "\u001b[1m";
    private static final String OFF = "\u001b[0m";

    /**
     * Where content starts, for everything inside a block.
     *
     * <p>Six, and a status glyph sits in the two columns to its left rather than shifting its own
     * line right. Otherwise a bullet and the plain line under it begin in different columns, which
     * is the one thing a column of facts exists not to do -- and it is visible immediately, because
     * the eye is already reading down the left edge.
     */
    private static final String GUTTER = "      ";

    /** The accent down the left of a heading. */
    private static final String BAR = "\u258c";

    private static Boolean colourOverride;

    /**
     * Whether to colour at all.
     *
     * <p>{@code System.console()} is null when stdout is redirected, which is exactly the case that
     * must stay plain. It is also null under some IDE runners that are otherwise perfectly capable
     * terminals, and losing colour there is the harmless half of the trade.
     */
    public static boolean colour() {
        if (colourOverride != null) {
            return colourOverride;
        }
        if (System.getenv("CLICOLOR_FORCE") != null) {
            return true;
        }
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        String term = System.getenv("TERM");
        if (term == null || term.equalsIgnoreCase("dumb")) {
            return false;
        }
        return System.console() != null;
    }

    /** For tests, which have no terminal and still need to see both forms. */
    public static void forceColour(Boolean on) {
        colourOverride = on;
    }

    private static String paint(String code, String text) {
        return colour() ? code + text + OFF : text;
    }

    private static PrintStream out() {
        return System.out;
    }

    /** Pad to a width the escape codes must not be counted in. */
    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /** What you typed, or what to type next. */
    public static String cmd(String text) {
        return paint(BRASS, text);
    }

    /** A good outcome. */
    public static String good(String text) {
        return paint(TEAL, text);
    }

    /** A bad one. */
    public static String bad(String text) {
        return paint(RUST, text);
    }

    /** Present, and not the point. */
    public static String faint(String text) {
        return paint(DIM, text);
    }

    /**
     * The one heading at the top of a command's output.
     *
     * <p>An accent bar rather than a rule of equals signs. A bar is one character wide whatever the
     * title says, so it cannot be the wrong length -- which is how twenty-three separators happened:
     * each one was somebody counting to fifty for their own heading.
     */
    public static void title(String text) {
        out().println();
        out().println("  " + paint(BRASS, BAR) + " " + paint(BOLD, text));
    }

    /**
     * A heading inside it.
     *
     * <p>Lower case, unpunctuated, and with nothing drawn around it. A section is a label for the
     * indented block under it, and the indentation is already doing the work that a box of dashes
     * was being asked to do twice.
     */
    public static void section(String text) {
        out().println();
        out().println("  " + faint(text.toLowerCase(Locale.ROOT)));
    }

    /** A thin rule, for the rare place two blocks genuinely need separating. */
    public static void rule() {
        out().println("  " + faint("\u2500".repeat(56)));
    }

    /** A fact, aligned so that a column of them reads down. */
    public static void kv(String key, String value) {
        out().printf("%s%s %s%n", GUTTER, faint(pad(key, 10)), value);
    }

    /** One of several things. */
    public static void item(String text) {
        out().println(GUTTER + text);
    }

    /** Nothing to report, said in a way that does not look like a failure. */
    public static void none(String text) {
        out().println(GUTTER + faint(text));
    }

    /** It worked. */
    public static void ok(String text) {
        out().println("    " + good("●") + " " + text);
    }

    /** It did not, and it is worth stopping for. */
    public static void warn(String text) {
        out().println("    " + bad("●") + " " + text);
    }

    /**
     * What to run next.
     *
     * <p>Always a command that can be pasted. A hint that says "you could sync" rather than
     * {@code oss sync} leaves the reader with a second question.
     */
    public static void hint(String command, String why) {
        out().printf("%s%s   %s%n", GUTTER, cmd(command), faint(why));
    }

    /** Several of those, aligned to the longest, so the reasons line up. */
    public static void hints(List<String[]> pairs) {
        int width = 0;
        for (String[] p : pairs) {
            width = Math.max(width, p[0].length());
        }
        for (String[] p : pairs) {
            out().printf("%s%s%s   %s%n", GUTTER, cmd(p[0]), " ".repeat(width - p[0].length()), faint(p[1]));
        }
    }

    /** A blank line, named so the reason for it is a word rather than a bare println. */
    public static void gap() {
        out().println();
    }
}
