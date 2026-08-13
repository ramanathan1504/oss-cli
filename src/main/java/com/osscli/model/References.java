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
package com.osscli.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The references one issue makes to another, read out of what people wrote.
 *
 * <p>Retrieval is otherwise entirely by similarity, which is the right tool when nobody has said
 * what relates to what, and the wrong one when somebody has. "Fixes #4100" is not a resemblance to
 * be scored -- it is a statement, and following it is arithmetic rather than inference. A vector
 * search can miss it outright: a one-line PR that says only "fixes #4100" shares almost no wording
 * with the issue it closes, so the two rank as unrelated exactly when they are most related.
 *
 * <p>So references are extracted once and stored as edges, and retrieval walks them beside the
 * similarity ranking. The two answer different questions and neither replaces the other.
 *
 * <p><b>Precision over recall.</b> A wrong edge is worse than a missing one: it pulls an unrelated
 * issue into the context of every question about this one, spending the token budget on noise and
 * doing it silently. Everything below is therefore deliberately narrow.
 */
public final class References {

    /**
     * Fenced code and indented blocks are removed before matching.
     *
     * <p>Issue bodies are mostly stack traces, logs and configuration. Those carry `#` and long hex
     * strings constantly and mean nothing by them -- a log line reading {@code at Foo#bar} or a
     * config sample containing a colour would each become an edge to a real, unrelated issue.
     */
    private static final Pattern FENCED = Pattern.compile("(?s)```.*?```|~~~.*?~~~");

    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

    /**
     * A closing keyword and its issue. GitHub's own list, which is what maintainers actually type.
     *
     * <p>Matched before the bare form so "fixes #12" is recorded as CLOSES rather than MENTIONS.
     */
    private static final Pattern CLOSES = Pattern.compile(
            "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s*:?\\s+" + "(?:([\\w.-]+)/([\\w.-]+))?#(\\d{1,7})\\b");

    /** {@code owner/name#123}, or a bare {@code #123} in the repository being read. */
    private static final Pattern ISSUE = Pattern.compile("(?<![\\w&/])(?:([\\w.-]+)/([\\w.-]+))?#(\\d{1,7})\\b");

    /**
     * A commit, only where it is unambiguous.
     *
     * <p>A full 40-character hash, or a shorter one that something calls a commit -- a GitHub commit
     * URL, or the literal word. Bare seven-character hex is not enough on its own: it matches
     * fragments of stack traces, hashes and identifiers, and there is no way to tell from the string
     * which it was.
     */
    private static final Pattern COMMIT_URL = Pattern.compile("/commit/([0-9a-f]{7,40})\\b");

    private static final Pattern COMMIT_WORD = Pattern.compile("(?i)\\bcommit\\s+([0-9a-f]{7,40})\\b");

    private static final Pattern COMMIT_FULL = Pattern.compile("(?<![0-9a-z])([0-9a-f]{40})(?![0-9a-z])");

    /** GitHub's own limit, and a bound on what one issue can drag into a prompt. */
    private static final int MAX_REFS = 40;

    private References() {}

    /**
     * Every reference in {@code text}, in the order written, without duplicates.
     *
     * @param text an issue or pull request body, or a comment
     * @param defaultRepository the repository the text was written in, which is what a bare {@code
     *     #123} means. A reference to a repository that is not this one is kept and recorded --
     *     cross-repository references are how ecosystems actually behave -- but retrieval only
     *     follows the ones whose target it has synced.
     */
    public static List<IssueReference> parse(String text, String defaultRepository) {
        if (text == null || text.isBlank() || defaultRepository == null) {
            return List.of();
        }
        String clean = INLINE_CODE.matcher(FENCED.matcher(text).replaceAll(" ")).replaceAll(" ");

        Set<String> seen = new LinkedHashSet<>();
        List<IssueReference> out = new ArrayList<>();

        Matcher m = CLOSES.matcher(clean);
        while (m.find() && out.size() < MAX_REFS) {
            add(out, seen, issueRef(IssueReference.Kind.CLOSES, m.group(1), m.group(2), m.group(3), defaultRepository));
        }

        m = ISSUE.matcher(clean);
        while (m.find() && out.size() < MAX_REFS) {
            add(
                    out,
                    seen,
                    issueRef(IssueReference.Kind.MENTIONS, m.group(1), m.group(2), m.group(3), defaultRepository));
        }

        for (Pattern p : new Pattern[] {COMMIT_URL, COMMIT_WORD, COMMIT_FULL}) {
            m = p.matcher(clean);
            while (m.find() && out.size() < MAX_REFS) {
                add(
                        out,
                        seen,
                        new IssueReference(
                                IssueReference.Kind.COMMIT,
                                defaultRepository,
                                0,
                                m.group(1).toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    /**
     * Add unless this target is already present under any kind.
     *
     * <p>Keyed on the target rather than on kind-and-target, so "fixes #12 ... see also #12" is one
     * edge and keeps the CLOSES it was first matched as. Recording both would double the weight of
     * an issue for having been mentioned twice, which is not what repetition means.
     */
    private static void add(List<IssueReference> out, Set<String> seen, IssueReference ref) {
        if (ref != null && seen.add(ref.key())) {
            out.add(ref);
        }
    }

    private static IssueReference issueRef(
            IssueReference.Kind kind, String owner, String name, String number, String defaultRepository) {
        try {
            long n = Long.parseLong(number);
            if (n <= 0) {
                return null;
            }
            String repo = owner != null && name != null ? owner + "/" + name : defaultRepository;
            return new IssueReference(kind, repo, n, null);
        } catch (NumberFormatException e) {
            // Bounded to seven digits by the pattern, so this is unreachable; returning null rather
            // than throwing keeps one odd body from failing a whole sync.
            return null;
        }
    }
}
