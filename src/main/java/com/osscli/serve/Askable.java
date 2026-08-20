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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question the local page can ask, and the command that answers it.
 *
 * <p>The page never reimplements a command. It runs one and shows what came back, so the two cannot
 * disagree — and if they ever did, the page would be the one lying. That rule is why this is a
 * table of argv rather than a set of handlers.
 *
 * <p>Every entry carries <b>the question it answers</b>, because that sentence is the button's
 * hover text and belongs beside the argv rather than in a manual: written in two places they drift,
 * and the copy that drifts is always the one the reader is looking at.
 *
 * <p><b>Everything here reads and nothing here writes.</b> A browser has no terminal, and an
 * outward write must be confirmed at one — so a command that posts, syncs or files can never be on
 * this table, and {@code AskableTest} fails the build if one appears. That is the whole reason the
 * page may dispatch at all.
 */
public final class Askable {

    private Askable() {}

    /** One question, the argv that answers it, and what to say when the answer is empty. */
    public record Question(String key, List<String> argv, String arg, int timeoutSeconds, String asks, String empty) {

        /** Whether this question needs something typed before it can be asked. */
        public boolean needsArgument() {
            return arg != null;
        }
    }

    /**
     * Commands that change something. None of these may ever appear above.
     *
     * <p>Named rather than inferred: a list of verbs is something a reader can check, where "does
     * this write" inferred from a name is a guess that gets one wrong eventually.
     */
    public static final List<String> WRITES =
            List.of("sync", "setup", "backup", "restore", "alias", "ext", "run", "bench", "serve", "memory", "kb");

    private static final Map<String, Question> TABLE = table();

    private static Map<String, Question> table() {
        Map<String, Question> m = new LinkedHashMap<>();
        // The board itself, first: these two are what the page opens on, and they are commands
        // like every other entry rather than a rendering of their own. A board that reimplemented
        // the ledger would be a second answer to "who is waiting", free to disagree with the first.
        put(
                m,
                new Question(
                        "hub",
                        List.of("hub"),
                        null,
                        120,
                        "Is anyone waiting on me? Every project you follow, ordered by whose turn it"
                                + " is rather than by date.",
                        "nobody is waiting on you"));
        put(
                m,
                new Question(
                        "pick",
                        List.of("pick"),
                        null,
                        180,
                        "What should I work on next? Scored against what you have already worked on,"
                                + " so the suggestion is one you are equipped for.",
                        "nothing to suggest yet — record a review or file a note"));
        put(
                m,
                new Question(
                        "search",
                        List.of("search"),
                        "text",
                        120,
                        "Have I worked this out before? Searches your own notes and synced issues by"
                                + " meaning, using the model that runs inside oss — no network.",
                        "nothing recorded yet"));
        put(
                m,
                new Question(
                        "duplicates",
                        List.of("duplicates"),
                        null,
                        300,
                        "Is this the same as something already open? Compares every open issue against"
                                + " every other by meaning, not by words.",
                        "no duplicates found"));
        put(
                m,
                new Question(
                        "followup",
                        List.of("followup", "--changed"),
                        null,
                        120,
                        "What moved since I reviewed it? Reads the ledger, which knows what you decided"
                                + " — GitHub only knows what you posted.",
                        "nothing has moved"));
        put(
                m,
                new Question(
                        "followup-one",
                        List.of("followup"),
                        "num",
                        120,
                        "This one pull request in full: what you recorded, and what has happened to it" + " since.",
                        "nothing recorded for that one"));
        put(
                m,
                new Question(
                        "hidden-critical",
                        List.of("hidden-critical"),
                        null,
                        300,
                        "What is serious but not labelled so? Reads the bodies rather than trusting the" + " labels.",
                        "nothing hidden found"));
        put(
                m,
                new Question(
                        "doctor",
                        List.of("doctor"),
                        null,
                        120,
                        "Is every prerequisite in place? Exits non-zero when an optional one is missing,"
                                + " so a red result here is a report, not a failure.",
                        "doctor said nothing"));
        // Not Map.copyOf: that returns an unordered map, and this table has an order -- the
        // questions are offered in it. Caught by the page listing doctor first and search fourth.
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, Question> m, Question q) {
        m.put(q.key(), q);
    }

    /** Every question this page can ask, in the order they are offered. */
    public static List<Question> all() {
        return List.copyOf(TABLE.values());
    }

    /** One question by key, or null when the page asks for something that is not on the table. */
    public static Question byKey(String key) {
        return key == null ? null : TABLE.get(key);
    }
}
