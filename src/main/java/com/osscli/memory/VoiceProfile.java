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
 * How you write, measured from what you actually wrote.
 *
 * <p>The corpus is full of prose and almost none of it is the user's. Of 1,874 notes on the machine
 * this was written for, 1,024 are harvested GitHub threads — mostly other people's words — and 840
 * are generated drafts, one of which says in its own body <em>"Add the narrative voice before
 * publishing"</em>. A style learned from that is the tool's own voice handed back to its owner as
 * theirs, which is worse than no style at all: it is confidently wrong about the one thing the user
 * would notice.
 *
 * <p>So the source here is deliberately narrow and boring: text with the user's name on it.
 * Issue and pull request bodies they authored, and their own turns in {@code chat_turn}. On that
 * machine that is eleven pieces of writing, which is not enough — and saying so is the point.
 * {@link #confident()} is false below {@link #ENOUGH}, and the profile says which it is everywhere
 * it is shown.
 *
 * <p><b>Measured, never inferred.</b> Every trait here is arithmetic over the text: sentence length,
 * paragraph length, how often a list or a code fence appears, whether headings are used, British or
 * American spelling. Nothing asks a model what somebody "sounds like" — that produces flattery, and
 * flattery cannot be checked against anything.
 */
public final class VoiceProfile {

    /**
     * Below this many samples, the numbers are arithmetic on noise.
     *
     * <p>Twenty is not a statistical threshold, it is an honesty threshold: enough that a single
     * long comment cannot set the average, few enough that somebody who has written a few dozen
     * review comments has a profile at all.
     */
    public static final int ENOUGH = 20;

    private final int samples;
    private final int words;
    private final double wordsPerSentence;
    private final double sentencesPerParagraph;
    private final double bulletRatio;
    private final double codeFenceRatio;
    private final double headingRatio;
    private final double questionRatio;
    private final int britishSpellings;
    private final int americanSpellings;

    private VoiceProfile(
            int samples,
            int words,
            double wordsPerSentence,
            double sentencesPerParagraph,
            double bulletRatio,
            double codeFenceRatio,
            double headingRatio,
            double questionRatio,
            int britishSpellings,
            int americanSpellings) {
        this.samples = samples;
        this.words = words;
        this.wordsPerSentence = wordsPerSentence;
        this.sentencesPerParagraph = sentencesPerParagraph;
        this.bulletRatio = bulletRatio;
        this.codeFenceRatio = codeFenceRatio;
        this.headingRatio = headingRatio;
        this.questionRatio = questionRatio;
        this.britishSpellings = britishSpellings;
        this.americanSpellings = americanSpellings;
    }

    /** True when there is enough of the user's writing for the numbers to mean anything. */
    public boolean confident() {
        return samples >= ENOUGH;
    }

    public int samples() {
        return samples;
    }

    public int words() {
        return words;
    }

    /**
     * Measure a set of texts the user wrote.
     *
     * <p>Takes the texts rather than fetching them, so this is testable without a database — the
     * same seam as {@code Attachments.tree(registered, followed)} and {@code Ai.route(...)}, and for
     * the same reason.
     */
    public static VoiceProfile of(List<String> written) {
        int samples = 0;
        int words = 0;
        int sentences = 0;
        int paragraphs = 0;
        int bulleted = 0;
        int fenced = 0;
        int headed = 0;
        int questions = 0;
        int british = 0;
        int american = 0;

        for (String raw : written) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String text = raw.strip();
            samples++;

            words += text.split("\\s+").length;
            int blocks = Math.max(1, text.split("\\n\\s*\\n").length);
            // A sentence ends at . ! or ?, and "e.g." is not two sentences: require whitespace or
            // the end of the text after the mark.
            //
            // Floored at the number of blocks, because a heading and a bullet carry no full stop
            // and are still one unit of writing each. Without the floor a bullet-heavy comment
            // measured 0.9 sentences per paragraph -- a number that cannot exist, printed with the
            // same confidence as the ones that can.
            int marks = text.split("[.!?](\\s|$)").length;
            sentences += Math.max(blocks, marks);
            paragraphs += blocks;
            questions += count(text, "?");

            if (text.matches("(?s).*(^|\\n)\\s*[-*+] .*")) {
                bulleted++;
            }
            if (text.contains("```")) {
                fenced++;
            }
            if (text.matches("(?s).*(^|\\n)#{1,6} .*")) {
                headed++;
            }

            String lower = text.toLowerCase(Locale.ROOT);
            british += count(lower, "behaviour")
                    + count(lower, "colour")
                    + count(lower, "organise")
                    + count(lower, "recognise")
                    + count(lower, "licence");
            american += count(lower, "behavior")
                    + count(lower, "color")
                    + count(lower, "organize")
                    + count(lower, "recognize")
                    + count(lower, "license");
        }

        if (samples == 0) {
            return new VoiceProfile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new VoiceProfile(
                samples,
                words,
                (double) words / Math.max(1, sentences),
                (double) sentences / Math.max(1, paragraphs),
                (double) bulleted / samples,
                (double) fenced / samples,
                (double) headed / samples,
                (double) questions / samples,
                british,
                american);
    }

    /**
     * Everything on this machine that the user actually wrote.
     *
     * <p>Two sources, both filtered by authorship rather than by location: issue and pull request
     * bodies whose {@code author} is the configured GitHub username, and the {@code user} turns of
     * recorded chats. Notes are excluded wholesale — the harvest directory is other people's
     * threads and the drive notes are generated drafts, and neither becomes the user's voice by
     * sitting in the user's folder.
     *
     * <p>Never throws: a profile is a decoration on other work, and a database that cannot be read
     * will be reported by whatever actually needed it.
     */
    public static List<String> written() {
        List<String> out = new ArrayList<>();
        String username;
        try {
            username = com.osscli.storage.SqliteStorage.loadConfig("github.username");
        } catch (Exception e) {
            username = null;
        }
        if (username != null && !username.isBlank()) {
            try (java.sql.Connection conn = com.osscli.storage.DatabaseManager.getConnection();
                    java.sql.PreparedStatement ps = conn.prepareStatement(
                            "SELECT body FROM issues WHERE author = ? AND body IS NOT NULL AND length(body) > 80")) {
                ps.setString(1, username);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            } catch (Exception ignored) {
                // Reported by the command that needed the corpus, not by the profile decorating it.
            }
        }
        try (java.sql.Connection conn = com.osscli.storage.DatabaseManager.getConnection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT content FROM chat_turn WHERE role = 'user' AND length(content) > 80")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The profile of whatever this machine holds of the user's writing. */
    public static VoiceProfile ofThisMachine() {
        return of(written());
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

    /** Which spelling the user actually uses, or null when they have not shown a preference. */
    public String spelling() {
        if (britishSpellings == americanSpellings) {
            return null;
        }
        return britishSpellings > americanSpellings ? "British" : "American";
    }

    /**
     * The file kept under the memory directory — markdown, so it can be read, edited and indexed
     * like everything else the tool knows.
     */
    public String markdown() {
        StringBuilder b = new StringBuilder("# How you write\n\n");
        if (samples == 0) {
            b.append("Nothing of yours was found to measure.\n\n");
            b.append("This is built only from text with your name on it — issues and pull requests\n");
            b.append("you authored, and your own turns in `oss chat`. Harvested threads and\n");
            b.append("generated notes are excluded on purpose: a voice learned from those is the\n");
            b.append("tool's own, handed back to you as yours.\n");
            return b.toString();
        }
        b.append(
                confident()
                        ? "Measured from " + samples + " pieces of your writing (" + words + " words).\n\n"
                        : "**Provisional — " + samples + " sample(s), " + words + " words.** Below " + ENOUGH
                                + " this is arithmetic on noise, and is shown\nrather than hidden so you know which it is.\n\n");
        b.append("| trait | measured |\n|---|---|\n");
        b.append(String.format("| words per sentence | %.1f |%n", wordsPerSentence));
        b.append(String.format("| sentences per paragraph | %.1f |%n", sentencesPerParagraph));
        b.append(String.format("| uses bullet lists | %.0f%% of the time |%n", bulletRatio * 100));
        b.append(String.format("| uses code blocks | %.0f%% |%n", codeFenceRatio * 100));
        b.append(String.format("| uses headings | %.0f%% |%n", headingRatio * 100));
        if (spelling() != null) {
            b.append(String.format("| spelling | %s |%n", spelling()));
        }
        return b.toString();
    }

    /**
     * What generation is told, or an empty string when there is not enough to say.
     *
     * <p>Silent below {@link #ENOUGH} rather than hedged. "They may write short sentences, from two
     * samples" is worse than nothing: it spends prompt budget to make the model imitate a number
     * that came from almost no evidence, and the user cannot tell that is what happened.
     */
    public String forPrompt() {
        if (!confident()) {
            return "";
        }
        List<String> traits = new ArrayList<>();
        traits.add(String.format("sentences of about %.0f words", wordsPerSentence));
        traits.add(String.format("paragraphs of about %.0f sentences", sentencesPerParagraph));
        if (bulletRatio > 0.5) {
            traits.add("bullet lists, often");
        }
        if (codeFenceRatio > 0.5) {
            traits.add("a code block where one helps");
        }
        if (headingRatio > 0.5) {
            traits.add("headings");
        }
        if (spelling() != null) {
            traits.add(spelling() + " spelling");
        }
        return "--- how the reader writes, measured from " + samples + " of their own pieces ---\n"
                + String.join("; ", traits) + ".\nMatch it where it does not cost clarity.\n";
    }
}
