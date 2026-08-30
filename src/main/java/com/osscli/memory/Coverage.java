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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the notes cover, measured against something outside them.
 *
 * <p>A topic map answers "which of my notes touch Log4j" and cannot answer "what about Log4j have I
 * never written down" — both only ever look at what is already there, so a base holding nothing on
 * Lookups reports every one of its Log4j notes as Log4j notes and calls that complete.
 *
 * <p>So the yardstick comes from outside: the list of areas a technology's own manual documents,
 * declared in {@code kb.json}. Every area is scored by how many notes mention it and how often, and
 * the ones that score nothing are the answer to what is missing.
 *
 * <h2>Two grades, and why they are not one</h2>
 *
 * <p><b>Applied</b> requires the mentions to appear in more than one note. A single long note that
 * uses a term forty times is one thing you read once; three notes that each return to it is a
 * subject you have actually worked in. Collapsing the two would let one afternoon's reading read as
 * experience.
 */
public final class Coverage {

    /** Mentions in one note before that note counts as being about the area at all. */
    private static final int MENTION_FLOOR = 3;

    /** Notes that must clear the floor before an area counts as covered rather than touched. */
    private static final int NOTE_FLOOR = 3;

    private Coverage() {}

    /** One documented area, and what the notes have to say about it. */
    public record Area(String name, int notes, int mentions, String strongest) {

        /**
         * Nothing at all, one or two notes, or several.
         *
         * <p><b>"touched", not "covered", and the word matters.</b> This counts what your notes
         * say about an area. It cannot know whether you understand it -- and for a while it said
         * "covered" while {@code curriculum} used the same word for something else entirely: an
         * area you had read and moved to {@code covered/} yourself. So one command reported log4j
         * as "32 of 56 covered" and the other as "0 covered", both correct, about different
         * things.
         *
         * <p>Having written about something forty times is having met it. Only a person can say
         * they have learned it, which is why that verdict is a file they move and this one is a
         * count.
         */
        public String grade() {
            if (notes == 0) {
                return "nothing";
            }
            if (notes < NOTE_FLOOR) {
                return "thin";
            }
            return "touched";
        }

        public String mark() {
            switch (grade()) {
                case "touched":
                    return "●";
                case "thin":
                    return "◐";
                default:
                    return "○";
            }
        }
    }

    /**
     * Score every area of a yardstick against the notes in an archive.
     *
     * <p>Matching is literal and case-insensitive, on purpose. The alternative is a model deciding
     * whether a note is "about" an area, which turns a measurement into an opinion and makes the
     * number move when nothing was written.
     */
    public static List<Area> score(Path archive, List<String> areas) throws IOException {
        Map<String, Integer> noteCount = new LinkedHashMap<>();
        Map<String, Integer> mentionCount = new LinkedHashMap<>();
        Map<String, String> strongest = new LinkedHashMap<>();
        Map<String, Integer> strongestScore = new LinkedHashMap<>();
        for (String area : areas) {
            noteCount.put(area, 0);
            mentionCount.put(area, 0);
            strongest.put(area, "");
            strongestScore.put(area, 0);
        }

        if (!Files.isDirectory(archive)) {
            return areas.stream().map(a -> new Area(a, 0, 0, "")).toList();
        }

        // Through ArchiveNotes, which puts a deadline on every read. This used to call readString
        // directly, and on an archive that lives in iCloud and has been evicted, every one of those
        // is a download: `oss memory map` sat for over two minutes printing nothing.
        lastWalk = ArchiveNotes.walk(archive);
        {
            for (ArchiveNotes.Note read : lastWalk.notes()) {
                Path note = read.path();
                String text = read.lowercaseText();
                for (String area : areas) {
                    int hits = count(text, area.toLowerCase(Locale.ROOT));
                    if (hits < MENTION_FLOOR) {
                        // One passing use of a word is not knowledge of the subject. Without a
                        // floor, a single stray "thread" put most of an archive under concurrency.
                        continue;
                    }
                    noteCount.merge(area, 1, Integer::sum);
                    mentionCount.merge(area, hits, Integer::sum);
                    if (hits > strongestScore.get(area)) {
                        strongestScore.put(area, hits);
                        strongest.put(area, note.getFileName().toString());
                    }
                }
            }
        }

        List<Area> out = new ArrayList<>();
        for (String area : areas) {
            out.add(new Area(area, noteCount.get(area), mentionCount.get(area), strongest.get(area)));
        }
        out.sort((a, b) ->
                b.notes() != a.notes() ? b.notes() - a.notes() : a.name().compareTo(b.name()));
        return out;
    }

    /** Non-overlapping occurrences, which is what "mentions" means to a person counting them. */
    /**
     * What the last walk could not read.
     *
     * <p>Returned out of band because {@code map} and {@code score} each return the thing they
     * measured, and neither shape has room for "and 816 notes were skipped". A count nobody prints
     * is the same as no count, and this is exactly the number a reader needs to know whether the
     * measurement covered their archive or a fraction of it.
     */
    private static ArchiveNotes.Walk lastWalk;

    /** The last walk's warning, or empty. Read straight after calling {@link #map} or {@link #score}. */
    public static String lastWarning() {
        return lastWalk == null ? "" : lastWalk.warning();
    }

    private static int count(String haystack, String needle) {
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

    /** Which notes touch which topic — the question the map answers, over the same archive. */
    public static Map<String, List<String>> map(Path archive, Map<String, List<String>> topics) throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        topics.keySet().forEach(t -> out.put(t, new ArrayList<>()));
        if (!Files.isDirectory(archive) || topics.isEmpty()) {
            return out;
        }
        lastWalk = ArchiveNotes.walk(archive);
        {
            for (ArchiveNotes.Note read : lastWalk.notes()) {
                Path note = read.path();
                String text = read.lowercaseText();
                for (Map.Entry<String, List<String>> topic : topics.entrySet()) {
                    int hits = 0;
                    for (String term : topic.getValue()) {
                        hits += count(text, term.toLowerCase(Locale.ROOT));
                    }
                    if (hits >= MENTION_FLOOR) {
                        out.get(topic.getKey()).add(note.getFileName().toString());
                    }
                }
            }
        }
        return out;
    }
}
