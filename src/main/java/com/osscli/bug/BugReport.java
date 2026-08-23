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
package com.osscli.bug;

import java.util.Set;

/**
 * What would be posted, as a value, before anything is posted.
 *
 * <p>Built rather than sent, so the whole of it can be printed and confirmed. That ordering is the
 * feature: the thing that makes filing from a terminal acceptable at all is that the person sees
 * the exact bytes, redacted, before they say yes -- not a summary of them, and not afterwards.
 *
 * <p><b>A model writes the title and the first paragraph; it does not write the report.</b> The
 * stack trace, the version and the command are copied verbatim, because those are the parts a
 * maintainer acts on and a model that paraphrases them has destroyed the report. When no model is
 * reachable the report is still complete -- it just leads with the exception instead of a sentence,
 * and it says so. A capability may degrade; it may not be gated on one provider.
 */
public record BugReport(String title, String body, String signature, boolean drafted) {

    /** How long a title may be before GitHub truncates it in a list. */
    private static final int TITLE_LIMIT = 100;

    /**
     * The report with no model involved: everything a maintainer needs, none of it rephrased.
     *
     * <p>This is the floor, in the sense the term index is the floor for search. It is not a
     * degraded mode that apologises -- a stack trace, a version and the command that produced it is
     * a better bug report than most, and the model only ever adds a sentence at the top.
     */
    public static BugReport of(Crash crash, String said, String home, Set<String> repositories) {
        // A report somebody typed is titled by what they said. Prefixing it with the exception
        // machinery produced "oss: reported by hand — the board page is blank", where every word
        // before the dash is noise the reader has to step over.
        String title = crash.isByHand()
                ? firstSentence(said == null || said.isBlank() ? crash.message() : said)
                : crash.command().split("\\s+")[0] + ": " + shortType(crash.type())
                        + (crash.message().isBlank() ? "" : " — " + firstLine(crash.message()));
        return build(title, null, crash, said, home, repositories);
    }

    /** The same report, led by a sentence a model wrote from it. */
    public static BugReport drafted(
            String title, String summary, Crash crash, String said, String home, Set<String> repositories) {
        return build(title, summary, crash, said, home, repositories);
    }

    private static BugReport build(
            String title, String summary, Crash crash, String said, String home, Set<String> known) {
        // The store's list, plus every name the report names itself. Neither alone is enough: the
        // store does not know a repository somebody passed with --repo, and the text does not name
        // the ones a command walked without being told to.
        Set<String> repositories = new java.util.LinkedHashSet<>(known == null ? Set.of() : known);
        repositories.addAll(Publishable.namesIn(crash.command(), crash.message(), crash.stack(), said, title, summary));

        StringBuilder b = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            b.append(Publishable.text(summary.strip(), home, repositories)).append("\n\n");
        }
        // Not when the title already is the sentence. A one-line report is the common case, and it
        // read as the same words twice under a heading that added nothing.
        String clean = cap(Publishable.text(title, home, repositories));
        String saidClean = said == null ? "" : Publishable.text(said.strip(), home, repositories);
        if (!saidClean.isBlank() && !saidClean.equals(clean)) {
            b.append("**What I was doing**\n\n").append(saidClean).append("\n\n");
        }
        // Only where there is something to say. A hand-written report has no command behind it, and
        // "Ran: oss" is a heading that answers nothing while looking like it answered something.
        if (!crash.isByHand()) {
            b.append("**Ran**\n\n```\n")
                    .append(Publishable.text(crash.command(), home, repositories))
                    .append("\n```\n\n");
        }
        b.append("**Build**\n\n")
                .append(Publishable.text(crash.version(), home, repositories))
                .append(" · ")
                .append(crash.platform())
                .append("\n\n");
        if (!crash.stack().isBlank()) {
            b.append("**Stack**\n\n```\n")
                    .append(Publishable.text(trimmed(crash.stack()), home, repositories))
                    .append("\n```\n\n");
        }
        // The signature is what makes the next person's identical crash find this issue instead of
        // opening a second one. In a comment: it is machinery, and a reader should not have to
        // scroll past it to reach the fault. Absent for a hand-written report, which has no fault
        // to match on -- see Crash.signature().
        if (!crash.signature().isBlank()) {
            b.append("<!-- oss-signature: ")
                    .append(Publishable.text(crash.signature(), home, repositories))
                    .append(" -->\n");
        }
        b.append("\n_Filed from the terminal with `oss bug`. Paths, keys, addresses and repository "
                + "names were taken out before this was shown for confirmation._\n");

        return new BugReport(
                clean,
                b.toString(),
                Publishable.text(crash.signature(), home, repositories),
                summary != null && !summary.isBlank());
    }

    /**
     * The trace, shortened from the middle.
     *
     * <p>Two hundred frames of reflection is not evidence, and an issue nobody scrolls to the end of
     * is one nobody reads. The top is where the fault is and the bottom is how it was reached.
     */
    static String trimmed(String stack) {
        String[] lines = stack.split("\\R");
        if (lines.length <= 40) {
            return stack.strip();
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            b.append(lines[i]).append('\n');
        }
        b.append("\t... ").append(lines.length - 40).append(" frames elided ...\n");
        for (int i = lines.length - 15; i < lines.length; i++) {
            b.append(lines[i]).append('\n');
        }
        return b.toString().strip();
    }

    private static String shortType(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    /** A typed sentence, as a title: the first one, or the first line if there is no full stop. */
    private static String firstSentence(String said) {
        String one = said == null ? "" : said.strip().split("\\R")[0].strip();
        int stop = one.indexOf(". ");
        return stop > 20 ? one.substring(0, stop) : one;
    }

    private static String firstLine(String s) {
        String one = s.split("\\R")[0].strip();
        return one.length() > 60 ? one.substring(0, 57) + "…" : one;
    }

    private static String cap(String title) {
        String one = title.replaceAll("\\R+", " ").strip();
        return one.length() <= TITLE_LIMIT ? one : one.substring(0, TITLE_LIMIT - 1) + "…";
    }

    /** What a model is asked for, and the shape it has to answer in. */
    public static String prompt(Crash crash, String said, String home, Set<String> known) {
        // Same union as the report itself. A prompt is an outward send too -- to whichever provider
        // is answering -- and it was carrying the name the report would have taken out.
        Set<String> repositories = new java.util.LinkedHashSet<>(known == null ? Set.of() : known);
        repositories.addAll(Publishable.namesIn(crash.command(), crash.message(), crash.stack(), said));
        return """
                You are writing a GitHub issue for a command-line tool called oss, reporting a fault \
                in oss itself. Answer with exactly two parts and nothing else:

                TITLE: one line, under 90 characters, naming the command and what went wrong.
                SUMMARY: two or three sentences saying what the user was doing, what happened, and \
                what the stack suggests the cause is. Do not invent a cause you cannot see. Do not \
                repeat the stack trace; it is already in the issue.

                Do not add any path, username, email, API key or repository name that is not already \
                in the text below.

                What the user said, if anything:
                %s

                Command: %s
                Exception: %s: %s
                Stack:
                %s
                """.formatted(
                        said == null || said.isBlank() ? "(nothing)" : Publishable.text(said, home, repositories),
                        Publishable.text(crash.command(), home, repositories),
                        crash.type(),
                        Publishable.text(crash.message(), home, repositories),
                        Publishable.text(trimmed(crash.stack()), home, repositories));
    }

    /** Pull the two parts back out of whatever the model said around them. */
    public static BugReport fromModel(String answer, Crash crash, String said, String home, Set<String> repositories) {
        if (answer == null || answer.isBlank()) {
            return of(crash, said, home, repositories);
        }
        String title = null;
        StringBuilder summary = new StringBuilder();
        boolean inSummary = false;
        for (String line : answer.split("\\R")) {
            String t = line.strip();
            if (t.regionMatches(true, 0, "TITLE:", 0, 6)) {
                title = t.substring(6).strip();
                inSummary = false;
            } else if (t.regionMatches(true, 0, "SUMMARY:", 0, 8)) {
                summary.append(t.substring(8).strip()).append(' ');
                inSummary = true;
            } else if (inSummary && !t.isBlank()) {
                summary.append(t).append(' ');
            }
        }
        // A model that answered in some other shape has still said something useful, but it has not
        // answered the question asked -- so the report falls back rather than posting a title that
        // is really the first line of an essay.
        if (title == null || title.isBlank()) {
            return of(crash, said, home, repositories);
        }
        return drafted(title, summary.toString(), crash, said, home, repositories);
    }
}
