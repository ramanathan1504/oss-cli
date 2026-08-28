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

import com.osscli.memory.Coverage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a subject documents, what you have touched, and what you have actually learned.
 *
 * <p>{@code coverage} answers the first two together and calls the result a grade. That conflates
 * two very different states. An area you have hit forty times across three pull requests is not an
 * area you know -- it is one you have <em>met</em>, usually while fixing something else, and the
 * understanding is spread across a transcript and a diff rather than written down anywhere. An area
 * with nothing at all is a different problem with a different fix.
 *
 * <p>So three states, and the middle one is the point:
 *
 * <dl>
 *   <dt>gap<dd>The manual documents it and the archive says nothing. Read the manual.
 *   <dt>backlog<dd>You have touched it -- in a note, a session or a change that merged -- and never
 *       sat down with it. Read what you already wrote, then decide.
 *   <dt>covered<dd>You read it and said so. Nothing here ever puts a file in this state.
 * </dl>
 *
 * <h2>The rule that makes it usable</h2>
 *
 * <p><b>Regenerating never overwrites a decision.</b> A file you moved to {@code covered/} stays
 * there, keeps its content, and is not re-created in {@code gap/} the next time this runs. The
 * archive already worked this way by hand -- "the move is the record" -- and a generator that
 * silently un-does that would be worse than no generator: you would stop trusting the folders after
 * the first time it happened, which is once.
 */
public final class Curriculum {

    /** Where the three folders live under the archive. */
    public static final String ROOT = "Reference/coverage";

    /** The three states, in the order somebody moves through them. */
    public static final List<String> STATES = List.of("gap", "backlog", "covered");

    /**
     * Notes mentioning an area before it counts as touched rather than absent.
     *
     * <p>One passing mention is a word in a paragraph about something else. Two separate notes is
     * the smallest thing that can honestly be called an encounter.
     */
    private static final int TOUCHED_NOTES = 2;

    private Curriculum() {}

    /** One area of a subject, and where it stands. */
    public record Item(String subject, String area, String state, int notes, int mentions, String strongest) {

        /** The file this area is written to, whatever state it is in. */
        public String fileName() {
            return SessionNotes.slug(area) + ".md";
        }
    }

    /**
     * Place every area of every subject.
     *
     * <p>{@code covered} is never assigned here. It is a claim about a person having read something,
     * and no count of mentions can establish that -- which is the whole reason the move is done by
     * hand.
     */
    public static List<Item> place(Path archive, Map<String, List<String>> yardsticks) throws IOException {
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> subject : yardsticks.entrySet()) {
            for (Coverage.Area area : Coverage.score(archive, subject.getValue())) {
                String state = area.notes() >= TOUCHED_NOTES ? "backlog" : "gap";
                out.add(new Item(
                        subject.getKey(), area.name(), state, area.notes(), area.mentions(), area.strongest()));
            }
        }
        return out;
    }

    /** Where an item's note goes for a given state. */
    public static Path pathFor(Path archive, String subject, String state, String area) {
        return archive.resolve(ROOT)
                .resolve(SessionNotes.slug(subject))
                .resolve(state)
                .resolve(SessionNotes.slug(area) + ".md");
    }

    /**
     * Where this area already sits, or null when it is new.
     *
     * <p>Checked before anything is written, and it is what stops a regeneration from moving a
     * finished area back to the pile. Searched in the order somebody progresses, so the furthest
     * state wins if a file was ever copied rather than moved.
     */
    public static String existingState(Path archive, String subject, String area) {
        for (int i = STATES.size() - 1; i >= 0; i--) {
            if (Files.isRegularFile(pathFor(archive, subject, STATES.get(i), area))) {
                return STATES.get(i);
            }
        }
        return null;
    }

    /**
     * The note for one area.
     *
     * <p>Says what the state is, why it is that, and what closing it looks like. The evidence
     * matters most in {@code backlog}: "you already wrote about this in these places" is the whole
     * difference between a reading list and a to-do list somebody else made for you.
     */
    public static String noteFor(Item item, List<String> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(item.area()).append('\n');
        sb.append("subject: ").append(item.subject()).append('\n');
        sb.append("status: ").append(item.state()).append('\n');
        sb.append("notes: ").append(item.notes()).append('\n');
        sb.append("mentions: ").append(item.mentions()).append('\n');
        sb.append("source: curriculum\n");
        sb.append("---\n\n");

        sb.append("# ")
                .append(item.area())
                .append("  ·  ")
                .append(item.subject())
                .append("\n\n");

        if ("gap".equals(item.state())) {
            sb.append("**Nothing in the archive touches this.** ")
                    .append("It is in the manual and not in your notes.\n\n");
            sb.append("## To close it\n\n");
            sb.append("Read the section of the manual that covers it, write one note in your own words ")
                    .append("about what it is for and when it is the wrong choice, then move this file to ")
                    .append("`covered/`.\n\n");
        } else {
            sb.append("**You have met this ")
                    .append(item.mentions())
                    .append(" time(s) across ")
                    .append(item.notes())
                    .append(" note(s), and never sat down with it.**\n\n");
            sb.append("That is why it is here rather than in `gap/`: the understanding exists, ")
                    .append("spread across a transcript and a diff, and it is not written down anywhere ")
                    .append("you could find it a year from now.\n\n");
            sb.append("## To close it\n\n");
            sb.append("Read what you already wrote, below. Consolidate it into one note. ")
                    .append("Then move this file to `covered/`.\n\n");
        }

        if (!evidence.isEmpty()) {
            sb.append("## Where you have already touched it\n\n");
            for (String where : evidence) {
                sb.append("- `").append(where).append("`\n");
            }
            sb.append('\n');
        } else if (!"gap".equals(item.state())
                && item.strongest() != null
                && !item.strongest().isBlank()) {
            sb.append("## Where you have already touched it\n\n");
            sb.append("- `").append(item.strongest()).append("`  (the note that says most about it)\n\n");
        }

        sb.append("---\n\n");
        sb.append("_Placed by `oss memory curriculum`. Moving this file is the record; ")
                .append("re-running never moves it back._\n");
        return sb.toString();
    }

    /** A subject's tally, for the caller to report. */
    public record Tally(String subject, int gap, int backlog, int covered) {

        public int total() {
            return gap + backlog + covered;
        }

        /** How much of the subject is finished, as a percentage of what its manual documents. */
        public int percent() {
            return total() == 0 ? 0 : (covered * 100) / total();
        }
    }

    /** Count what is in each state on disk, which is the only place the answer lives. */
    public static List<Tally> tallies(Path archive, Map<String, List<String>> yardsticks) {
        List<Tally> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> subject : yardsticks.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String state : STATES) {
                Path folder = archive.resolve(ROOT)
                        .resolve(SessionNotes.slug(subject.getKey()))
                        .resolve(state);
                int n = 0;
                if (Files.isDirectory(folder)) {
                    try (java.util.stream.Stream<Path> files = Files.list(folder)) {
                        n = (int)
                                files.filter(f -> f.toString().endsWith(".md")).count();
                    } catch (IOException e) {
                        // An unreadable folder counts zero and says so by the total not adding up,
                        // which is better than a number that looks authoritative and is not.
                        n = 0;
                    }
                }
                counts.put(state, n);
            }
            out.add(new Tally(subject.getKey(), counts.get("gap"), counts.get("backlog"), counts.get("covered")));
        }
        return out;
    }

    /**
     * Which notes actually mention an area, so the backlog entry can point at them.
     *
     * <p>Capped, because an area like {@code Recursion} appears in half the archive and a list of
     * four hundred paths is not evidence, it is noise.
     */
    public static List<String> evidenceFor(Path archive, String area, int limit) throws IOException {
        String needle = area.toLowerCase(Locale.ROOT);
        List<String> applied = new ArrayList<>();
        List<String> discussed = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(archive)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (applied.size() >= limit) {
                    break;
                }
                String path = file.toString();
                if (!path.endsWith(".md") || path.contains("/.git/") || path.contains("/coverage/")) {
                    continue;
                }
                try {
                    if (!Files.readString(file).toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                } catch (IOException e) {
                    // One unreadable note costs that note.
                    continue;
                }
                (isApplied(path) ? applied : discussed)
                        .add(archive.relativize(file).toString());
            }
        }
        // Where you used it beats where you mentioned it.
        //
        // The first version listed whatever the walk reached first, so "Array" -- a word in every
        // Java note ever written -- cited a digest and a page about job applications. True, and
        // useless. A change of yours that merged, or a review you wrote, is evidence that the
        // technique was actually applied to real code, which is the only kind worth reading back.
        List<String> out = new ArrayList<>(applied);
        for (String more : discussed) {
            if (out.size() >= limit) {
                break;
            }
            out.add(more);
        }
        return out;
    }

    /** A note about code that shipped, rather than about a conversation. */
    static boolean isApplied(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("/contributions/") || p.contains("/pr-reviews/") || p.contains("/issues/");
    }

    /** Write one area's note, unless a decision has already been made about it. */
    public static boolean write(Path archive, Item item, List<String> evidence) throws IOException {
        String already = existingState(archive, item.subject(), item.area());
        if ("covered".equals(already)) {
            // The move is the record. Re-creating this in gap/ or backlog/ would undo somebody's
            // afternoon and they would stop trusting the folders, which happens exactly once.
            return false;
        }
        if (already != null && !already.equals(item.state())) {
            // It moved between the two generated states -- backlog to gap or back. Take the old
            // file out so the same area is not listed twice under two different answers.
            Files.deleteIfExists(pathFor(archive, item.subject(), already, item.area()));
        }
        Path note = pathFor(archive, item.subject(), item.state(), item.area());
        Files.createDirectories(note.getParent());
        Files.writeString(note, noteFor(item, evidence), StandardCharsets.UTF_8);
        return true;
    }
}
