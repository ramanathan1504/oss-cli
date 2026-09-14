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
import java.util.List;
import java.util.Locale;

/**
 * How often a term is mentioned in a note, counted the way a reader would count it.
 *
 * <p>Counting letters inside other words measured nothing. On a real archive of 1,403 notes, "Raft"
 * was found in 159 of them through "draft", "SLO" in 171 through "slow", "Trie" in 135 through
 * "retrieval" and "eta" 207 times through "details" — areas reported as well covered that nobody had
 * written a word about.
 *
 * <p>Counting whole words only was wrong the other way: it lost "log4j2" for log4j,
 * "ThreadPoolExecutor" for ThreadPool and "AsyncAppender" for Async Appender.
 *
 * <p>So a mention starts where a word starts — after anything that is not a letter or digit, or at a
 * lower-to-upper case change inside an identifier — and may run on into a plural or a version
 * number, ending where a word ends or a new identifier part begins. The words of a term may be
 * written apart or together. Symbols the term starts or ends with are part of it, so {@code @test}
 * is the annotation and not every test. A term with no letters or digits is counted as written.
 *
 * <p><b>Scanned by hand, not by a regular expression.</b> The same rule as a case-insensitive
 * pattern with look-behinds took 524 seconds over 506 areas and 56 MB, against 11 for the substring
 * scan it replaced. Candidates are found with {@code indexOf} and only those are checked.
 */
public final class Mentions {

    private static final String SEPARATORS = " \t\n\r_-./";

    private final String lead;

    private final List<String> words;

    private final String trail;

    private final String literal;

    private final String needle;

    private Mentions(String lead, List<String> words, String trail, String literal) {
        this.lead = lead;
        this.words = words;
        this.trail = trail;
        this.literal = literal;
        this.needle = words.isEmpty() ? literal : lead + words.get(0);
    }

    /** The matcher for one term, built once and asked of every note. */
    public static Mentions of(String term) {
        String t = term == null ? "" : term.strip().toLowerCase(Locale.ROOT);
        int first = 0;
        while (first < t.length() && !Character.isLetterOrDigit(t.charAt(first))) {
            first++;
        }
        if (first == t.length()) {
            return new Mentions("", List.of(), "", t);
        }
        int last = t.length();
        while (!Character.isLetterOrDigit(t.charAt(last - 1))) {
            last--;
        }
        List<String> words = new ArrayList<>();
        int at = first;
        while (at < last) {
            int end = at;
            while (end < last && Character.isLetterOrDigit(t.charAt(end))) {
                end++;
            }
            words.add(t.substring(at, end));
            at = end;
            while (at < last && !Character.isLetterOrDigit(t.charAt(at))) {
                at++;
            }
        }
        return new Mentions(t.substring(0, first), List.copyOf(words), t.substring(last), "");
    }

    /**
     * Mentions of the term in a note.
     *
     * @param text the note as written, which is where an identifier's case changes can be seen
     * @param lowercase the same note lowercased with {@link Locale#ROOT}
     */
    public int in(String text, String lowercase) {
        if (words.isEmpty()) {
            return literalCount(lowercase, literal);
        }
        boolean aligned = lowercase.length() == text.length();
        String haystack = aligned ? lowercase : text;
        int n = 0;
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int at = aligned ? haystack.indexOf(needle, from) : indexOfIgnoringCase(text, needle, from);
            if (at < 0) {
                break;
            }
            int end = startsAWord(text, at) ? matchFrom(text, at) : -1;
            if (end > 0) {
                n++;
                from = end;
            } else {
                from = at + 1;
            }
        }
        return n;
    }

    /** Mentions in a note that has not been lowercased yet. */
    public int in(String text) {
        return in(text, text.toLowerCase(Locale.ROOT));
    }

    private int matchFrom(String text, int at) {
        int i = at;
        if (!regionIs(text, i, lead)) {
            return -1;
        }
        i += lead.length();
        for (int w = 0; w < words.size(); w++) {
            if (w > 0
                    && i < text.length()
                    && SEPARATORS.indexOf(text.charAt(i)) >= 0
                    && !regionIs(text, i, words.get(w))) {
                i++;
            }
            if (!regionIs(text, i, words.get(w))) {
                return -1;
            }
            i += words.get(w).length();
        }
        if (!trail.isEmpty()) {
            return regionIs(text, i, trail) ? i + trail.length() : -1;
        }
        for (String suffix : new String[] {"es", "s", ""}) {
            if (regionIs(text, i, suffix)) {
                int j = i + suffix.length();
                while (j < text.length() && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                if (endsAWord(text, j)) {
                    return j;
                }
            }
        }
        return -1;
    }

    private static boolean startsAWord(String text, int at) {
        if (at == 0) {
            return true;
        }
        char before = text.charAt(at - 1);
        if (!Character.isLetterOrDigit(before)) {
            return true;
        }
        return (Character.isLowerCase(before) || Character.isDigit(before)) && Character.isUpperCase(text.charAt(at));
    }

    private static boolean endsAWord(String text, int at) {
        if (at >= text.length()) {
            return true;
        }
        char next = text.charAt(at);
        return !Character.isLetterOrDigit(next)
                || Character.isUpperCase(next) && at > 0 && !Character.isUpperCase(text.charAt(at - 1));
    }

    private static boolean regionIs(String text, int at, String lowercaseWord) {
        return at + lowercaseWord.length() <= text.length()
                && text.regionMatches(true, at, lowercaseWord, 0, lowercaseWord.length());
    }

    private static int indexOfIgnoringCase(String text, String lowercaseNeedle, int from) {
        for (int i = from; i <= text.length() - lowercaseNeedle.length(); i++) {
            if (text.regionMatches(true, i, lowercaseNeedle, 0, lowercaseNeedle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int literalCount(String haystack, String needle) {
        if (needle.isBlank()) {
            return 0;
        }
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }
}
