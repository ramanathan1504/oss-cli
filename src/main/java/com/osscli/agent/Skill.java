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
package com.osscli.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How to do one kind of work, written down where a person can read and change it.
 *
 * <p>The alternative is a prompt buried in Java, which is what this replaces. A prompt in source is
 * a prompt only the person who can rebuild the jar may correct — and the people who know how a
 * Log4j review should go are not always the people holding a JDK. A skill is a markdown file: it
 * ships built in, it is listed, and yours replaces one of ours by having the same name, exactly as
 * an attached runner takes over the built-in one.
 *
 * <pre>
 * ---
 * name: reviewing-a-pull-request
 * when: review, pull request, pr, diff
 * summary: What a review has to establish before it is worth posting
 * ---
 * (the instructions themselves)
 * </pre>
 *
 * <p><b>{@code when} is a list of words, not a rule engine.</b> A skill is included when the
 * question mentions one of them, or always when it says {@code always}. Matching is deliberately
 * dumb: something cleverer would be a second thing to debug when an answer came out wrong, and the
 * user cannot see inside it. They can see a list of words.
 */
public record Skill(String name, List<String> when, String summary, String body, boolean builtIn) {

    /** Included in every prompt, whatever the question. */
    public boolean always() {
        return when.contains("always");
    }

    /** Whether this skill has anything to do with what was asked. */
    public boolean matches(String question) {
        if (always()) {
            return true;
        }
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String word : when) {
            if (!word.isBlank() && lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read one file.
     *
     * <p>Front matter is optional and a missing one is not an error: a file dropped into the skills
     * directory with nothing but instructions in it still works, keyed by its filename. Refusing it
     * would make the simplest possible skill the one that does not load.
     */
    public static Skill parse(String filename, String text, boolean builtIn) {
        String name = filename.replaceAll("\\.md$", "");
        List<String> when = new ArrayList<>();
        String summary = "";
        String body = text == null ? "" : text;

        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end > 0) {
                String front = body.substring(3, end);
                body = body.substring(end + 4).strip();
                for (String line : front.split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).strip();
                    switch (key) {
                        case "name" -> name = value.isEmpty() ? name : value;
                        case "summary", "description" -> summary = value;
                        case "when" -> {
                            for (String word : value.split(",")) {
                                String w = word.strip().toLowerCase(Locale.ROOT);
                                if (!w.isEmpty()) {
                                    when.add(w);
                                }
                            }
                        }
                        default -> {
                            // Unknown keys are ignored rather than refused: a skill file is written
                            // by hand, and a typo in a field nothing reads should not stop the file
                            // that carries the instructions from loading.
                        }
                    }
                }
            }
        }
        if (when.isEmpty()) {
            // No `when` means the author did not narrow it. Always is the honest reading of that,
            // and being too eager is visible in the answer where being silent is not.
            when.add("always");
        }
        return new Skill(name, List.copyOf(when), summary, body.strip(), builtIn);
    }
}
