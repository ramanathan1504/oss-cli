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

import com.osscli.memory.Sessions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A CLI transcript, filed under what it was about.
 *
 * <p>The archive on this machine had 541 of 837 notes sitting under a folder named after the
 * program that produced them -- {@code Projects/rollover/claude-code}, {@code Projects/rollover/claude-web},
 * {@code Projects/rollover/ai-studio}. Which is a real fact about each note and a useless one to file
 * by: nobody has ever wanted "everything I discussed in a browser tab". They want everything about
 * rollover, and the browser tab, the terminal and the pull request all have a piece of it, split
 * three ways by an accident of where it was typed.
 *
 * <p>So the tool becomes a field and the subject becomes the folder. Same note, same content, and
 * one place to look.
 *
 * <h2>Titles and topics are read, not generated</h2>
 *
 * <p>Both could be done better by a model and neither may require one. This runs hourly on a laptop
 * that is doing other things; a classifier that needs 470% of a CPU is a classifier that gets turned
 * off, and then the notes stop appearing at all. The topic map in {@code kb.json} already lists the
 * terms that identify each subject -- it is the same map {@code memory map} and {@code coverage}
 * score against -- so this counts them, and the directory the session ran in breaks the tie.
 *
 * <p>What that buys is a filing decision that is explainable in one line and identical on every run.
 * What it costs is nuance, and the note says which terms decided it so a wrong call is visible
 * rather than mysterious.
 */
public final class SessionNotes {

    /** Where filed sessions go under the archive. One folder per subject, none per program. */
    public static final String ROOT = "Sessions";

    /** Below this many matched terms, term-scoring is guessing and the directory wins. */
    private static final int MIN_TOPIC_HITS = 2;

    /** A title long enough to recognise, short enough to survive a file name. */
    private static final int TITLE_MAX = 72;

    /** How many touched paths are worth listing. Past this it is a build log, not a note. */
    private static final int MAX_TOUCHED = 25;

    private SessionNotes() {}

    /** One filed note: where it went, and what decided that. */
    public record Filed(Path path, String topic, String title, String why) {}

    // ==========================================
    // Title
    // ==========================================

    /**
     * What the session was called.
     *
     * <p>The first version of this took the first user turn over twelve characters, and the result
     * was a folder of notes named "all set here right?", "@ramanathan1504", "3. Findings + verdict"
     * and a bare pull-request URL. Every one of those is genuinely what was typed first and none of
     * them is what the session was about -- a conversation opens with an aside and gets to the
     * point three turns later, and a resumed one opens with whatever was on the clipboard.
     *
     * <p>So two rules, in order.
     *
     * <p><b>A pull request or issue number wins.</b> When a session names one, that is the subject:
     * it is a fact, it is unique, it is what you would search for a year later, and it survives the
     * fact that the sentence around it was "check this". {@code some-project PR 812} beats any
     * phrasing of "check this" that could be extracted from the same transcript.
     *
     * <p><b>Otherwise the turn with the most in it wins, not the first.</b> Scored on length and on
     * how much of it is technical rather than conversational, so "all set here right?" loses to the
     * turn that actually describes the problem. Conversational openers are pushed down rather than
     * removed, because a session where every turn is one of those still has to be called something.
     */
    public static String titleOf(List<Sessions.Turn> turns, String fallback) {
        return titleOf(turns, fallback, null);
    }

    /**
     * The same, allowed to borrow from a summary when the transcript has no good name in it.
     *
     * <p>A session that names no pull request is titled from what was typed, which is faithful and
     * often unsearchable: "why chnaged scraping count is greter than 1" is exactly what was asked
     * and not something anybody will find again. The summary was written for that session anyway
     * and is prose about the subject, so its opening clause is a better name and costs nothing --
     * no extra call, no second model.
     *
     * <p>Only when there is nothing better. A reference always wins, and a typed sentence that
     * scores as substantial wins over a generated one: what somebody actually said is the truer
     * title whenever it is usable at all.
     */
    public static String titleOf(List<Sessions.Turn> turns, String fallback, String summary) {
        String reference = referenceIn(turns);
        String phrase = bestPhrase(turns);
        if (reference != null) {
            return phrase == null ? reference : clip(reference + " — " + phrase);
        }
        // A typed phrase wins when it names something, not when it is merely long.
        //
        // Score alone did not separate them: "why chnaged scraping count is greter than 1" reaches
        // the same number as a good sentence purely on length and a question word. What actually
        // distinguishes a findable title is that it contains a symbol somebody could search for.
        if (phrase != null && namesSomething(phrase)) {
            return clip(phrase);
        }
        String fromSummary = openingClause(summary);
        if (fromSummary != null) {
            return clip(fromSummary);
        }
        return phrase == null ? fallback : clip(phrase);
    }

    /**
     * Whether a phrase names a thing rather than describing a feeling about one.
     *
     * <p>{@code RollingFileAppender}, {@code SmtpManager.getHeaders(}, a backticked path -- these
     * are what somebody types into a search box a year later. A sentence without one may be a
     * perfectly accurate account of what was asked and still be unfindable, which is the whole
     * problem with titling from a transcript.
     */
    static boolean namesSomething(String phrase) {
        return phrase.matches(".*\\b[a-z]+[A-Z][A-Za-z0-9]+\\b.*")
                || phrase.matches(".*\\b[A-Z][a-z]+[A-Z][A-Za-z0-9]*\\b.*")
                || phrase.matches(".*\\b\\w+\\.\\w+\\(.*")
                || phrase.contains("`");
    }

    /**
     * The first clause of a summary, as a name.
     *
     * <p>Cut at the first sentence break rather than the first full stop: these summaries open with
     * the conclusion, and the conclusion is usually one clause long before it starts qualifying
     * itself.
     */
    static String openingClause(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        // A summary that declines to summarise must not name the note.
        //
        // The prompt permits "if nothing was concluded, say exactly that" -- which is the right
        // answer for a session that went nowhere and the worst possible title, because every such
        // session gets the same one. Five notes came back called "Nothing was concluded." and
        // "The transcript contains only the opening message", which describe the transcript rather
        // than the work and are indistinguishable from each other.
        String opening = summary.strip().toLowerCase(Locale.ROOT);
        for (String nonAnswer : List.of(
                "nothing was concluded",
                "no conclusion",
                "nothing was settled",
                "nothing was resolved",
                "the transcript contains only",
                "the transcript holds only",
                "this session did not")) {
            if (opening.startsWith(nonAnswer)) {
                return null;
            }
        }
        String flat = summary.strip().replaceAll("\\s+", " ");
        int stop = flat.indexOf(". ");
        String first = stop > 20 ? flat.substring(0, stop) : flat;
        // A clause that is still enormous is a summary that did not open with a conclusion; take
        // the readable front of it rather than a paragraph.
        return first.length() < 12 ? null : first;
    }

    /** The GitHub pull request or issue a session is about, named the way a person would say it. */
    public static String referenceIn(List<Sessions.Turn> turns) {
        java.util.regex.Pattern url =
                java.util.regex.Pattern.compile("github\\.com/([\\w.-]+)/([\\w.-]+)/(pull|issues)/(\\d+)");
        // "review pr 4156" is how somebody who has the repository open actually refers to one, and
        // it is the whole of what many of these sessions say before the work starts. A URL is
        // better evidence when there is one, so it is looked for first across every turn.
        java.util.regex.Pattern bare =
                java.util.regex.Pattern.compile("(?i)\\b(pr|pull request|issue)\\s*#?\\s*(\\d{2,6})\\b");
        for (Sessions.Turn t : turns) {
            if (t.text() == null) {
                continue;
            }
            java.util.regex.Matcher m = url.matcher(t.text());
            if (m.find()) {
                return m.group(2) + ("pull".equals(m.group(3)) ? " PR " : " issue ") + m.group(4);
            }
        }
        for (Sessions.Turn t : turns) {
            if (t.text() == null || !t.user() || isMachinePreamble(t.text().strip())) {
                continue;
            }
            java.util.regex.Matcher m = bare.matcher(t.text());
            if (m.find()) {
                return ("issue".equalsIgnoreCase(m.group(1)) ? "issue " : "PR ") + m.group(2);
            }
        }
        return null;
    }

    /** Phrases that are how a conversation is steered rather than what it is about. */
    private static final List<String> CHATTER = List.of(
            "all set",
            "u know",
            "uknow",
            "ukow",
            "right?",
            "thanks",
            "ok ",
            "okay",
            "yes do",
            "do it",
            "fix all",
            "fix this",
            "continue",
            "go ahead",
            "pls",
            "please",
            "check this",
            "what about",
            "am taking",
            "i think",
            "can u",
            "can you");

    /** The user turn that carries the most subject, rather than the one that came first. */
    static String bestPhrase(List<Sessions.Turn> turns) {
        String best = null;
        int bestScore = 0;
        for (Sessions.Turn t : turns) {
            if (!t.user()) {
                continue;
            }
            String line = firstUsefulLine(t.text());
            if (line == null) {
                continue;
            }
            int score = scoreOf(line);
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }

    /**
     * How much subject a line carries.
     *
     * <p>Length, because a longer request names more; identifiers, because a line holding
     * {@code RollingFileAppender} is about something and a line holding none may not be; and a
     * penalty for the phrases people use to steer rather than to describe. Deliberately arithmetic
     * and inspectable: a title chosen by a model would be better prose and could not be argued with
     * when it was wrong.
     */
    static int scoreOf(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        int score = Math.min(line.length(), 90);
        // A camel-case or dotted identifier is the strongest single sign that a line names a thing.
        if (line.matches(".*\\b[a-z]+[A-Z][A-Za-z]+\\b.*") || line.matches(".*\\b\\w+\\.\\w+\\(.*")) {
            score += 40;
        }
        if (lower.matches(".*\\b(why|how|when|because|instead|fails?|failing|broken|error|bug)\\b.*")) {
            score += 20;
        }
        for (String noise : CHATTER) {
            if (lower.contains(noise)) {
                score -= 25;
            }
        }
        // A line that is mostly a URL says where, not what.
        if (lower.startsWith("http")) {
            score -= 40;
        }
        return Math.max(score, 1);
    }

    /** The first line of a turn that could plausibly name something. */
    private static String firstUsefulLine(String text) {
        if (text == null) {
            return null;
        }
        for (String raw : text.strip().split("\\R")) {
            String line = raw.strip()
                    // A pasted command or heading is a fine title once the punctuation that only
                    // means something to a shell or a markdown renderer is off the front.
                    .replaceAll("^[#>*\\-`\\s]+", "")
                    .replace("`", "")
                    .strip();
            // A line that is only a link says where to look and not what for; the sentence beside
            // it is the useful half, so the link comes out and what is left is judged.
            line = line.replaceAll("https?://\\S+", "").replaceAll("\\s+", " ").strip();
            if (line.length() < 12) {
                continue;
            }
            // A transcript that resumes carries the whole previous conversation as its first turn.
            // Titling from that names every continuation after the same first question.
            if (line.startsWith("<") || line.startsWith("Caveat:") || line.startsWith("This session is being")) {
                continue;
            }
            // A pasted screenshot arrives as "[Image: original 2542x950, displayed at ...]". It is
            // the first line of a great many turns here and names nothing; six notes came out of
            // the first run titled by an image's pixel dimensions.
            if (line.startsWith("[Image:") || line.startsWith("[Screenshot") || line.startsWith("[Pasted")) {
                continue;
            }
            // The harness's own note that somebody pressed ctrl-c. It is the whole of a turn, it
            // is not something anybody typed, and as a title it says only that a session stopped.
            if (line.startsWith("[Request interrupted") || line.startsWith("[Response interrupted")) {
                continue;
            }
            // A skill's own preamble. Typing "/pr-review" expands into several thousand
            // words of instructions, and those words outscore the request that triggered them
            // every time -- seventeen notes came out of the first run called "Base directory for
            // this skill:" when the sessions underneath were "review pr 4156" and its neighbours.
            // The session is real and worth keeping; only the title was the machine's.
            if (isMachinePreamble(line)) {
                continue;
            }
            return line;
        }
        return null;
    }

    /** Text a program put in front of a request, rather than anything a person typed. */
    static boolean isMachinePreamble(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        // This tool's own summarising prompt. Asking a command-line tool to summarise a transcript
        // creates a session of its own, which the next hourly run reads and files -- so the tool's
        // prompts came back as knowledge, 89 notes of it, every one titled "below is a transcript
        // of one working session on...". A machine writing notes about its own notes.
        return lower.startsWith(Enrichment.PREAMBLE.toLowerCase(Locale.ROOT))
                || lower.startsWith("base directory for this skill")
                || lower.startsWith("you are ")
                || lower.startsWith("read-only triage for")
                || lower.startsWith("caveat:")
                || lower.startsWith("<system-reminder")
                || lower.startsWith("the user sent a new message")
                || lower.startsWith("this session is being continued");
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        if (one.length() <= TITLE_MAX) {
            return one;
        }
        // Cut at a word rather than mid-syllable; a title ending "configurat" reads as corruption.
        int cut = one.lastIndexOf(' ', TITLE_MAX);
        return one.substring(0, cut < 30 ? TITLE_MAX : cut).strip();
    }

    /** A file name that is stable across runs, so the second harvest rewrites rather than duplicates. */
    public static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isBlank()) {
            return "session";
        }
        return s.length() > 60 ? s.substring(0, 60).replaceAll("-+$", "") : s;
    }

    /**
     * Where a filed session goes.
     *
     * <p>{@code <archive>/Projects/<subject>/<day>-<slug>.md}, and the thing to notice is what is
     * absent: no level named after the program that produced it. That level is what put three
     * folders -- claude-code, claude-web, ai-studio -- between a reader and everything the archive
     * knew about one subject.
     */
    public static Path fileIn(Path archive, String topic, String day, String title) {
        return archive.resolve("Projects").resolve(topic).resolve(day + "-" + slug(title) + ".md");
    }

    /**
     * The same, but never on top of a different session's note.
     *
     * <p>Two sessions on one day about one pull request produce one name, and the second write
     * destroyed the first silently -- 134 transcripts filed, 90 files on disk, and nothing said.
     * A note that disappears without a message is worse than one that was never written, because
     * the count says it worked.
     *
     * <p>Re-filing the <em>same</em> session still lands on the same name: the note already there
     * is opened and its {@code session:} read, and a match means this is an update rather than a
     * collision. That is what keeps an hourly job from accumulating a copy per run -- the failure
     * that put six copies of one review into a real archive, each embedded, each competing to
     * answer the same question.
     */
    public static Path fileInWithoutClobbering(Path archive, String topic, String day, String title, String sessionId) {
        Path first = fileIn(archive, topic, day, title);
        if (!java.nio.file.Files.isRegularFile(first) || sessionId == null) {
            return first;
        }
        String owner = sessionOf(first);
        if (owner == null || owner.equals(sessionId)) {
            // Ours, or a note from before this field existed. Overwriting is the intended update.
            return first;
        }
        String tag = sessionId.replaceAll("[^A-Za-z0-9]", "");
        tag = tag.length() > 6 ? tag.substring(tag.length() - 6) : tag;
        return archive.resolve("Projects").resolve(topic).resolve(day + "-" + slug(title) + "-" + tag + ".md");
    }

    /** The session id recorded in a note's frontmatter, or null when there is none to read. */
    static String sessionOf(Path note) {
        try {
            for (String line : java.nio.file.Files.readAllLines(note)) {
                if (line.startsWith("session: ")) {
                    return line.substring("session: ".length()).strip();
                }
                if (line.equals("---") && !line.isEmpty()) {
                    continue;
                }
                // The frontmatter is at the top or nowhere; reading a whole note to not find it
                // is a cost paid once per filed session, every hour.
                if (line.startsWith("# ")) {
                    return null;
                }
            }
        } catch (java.io.IOException e) {
            // Unreadable means "cannot prove it is somebody else's", and the safe answer to that
            // is to treat it as somebody else's.
            return "unknown";
        }
        return null;
    }

    /**
     * A note with its frontmatter and title removed, for embedding inside another note.
     *
     * <p>A running log is one document, and a document does not have a second YAML block in the
     * middle of it. Embedded whole, every session section opened with {@code ---} followed by
     * {@code title:} and {@code source:} lines -- which markdown renders as a horizontal rule and
     * then as prose, so the log read as though the file had been concatenated by accident. It had.
     */
    public static String bodyOf(String note) {
        String text = note == null ? "" : note;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end >= 0) {
                text = text.substring(text.indexOf('\n', end + 1) + 1);
            }
        }
        // The heading too: the log already carries one, and a second at the same level reads as a
        // second document rather than a section of this one.
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("# ")) {
            int nl = trimmed.indexOf('\n');
            trimmed = nl < 0 ? "" : trimmed.substring(nl + 1);
        }
        return trimmed.strip();
    }

    /**
     * Whether a transcript is a program talking to itself.
     *
     * <p>Sixteen sessions on this machine opened with "You are answering a question about this
     * project, on the user's own..." and eight more with "Base directory for this skill:". Those
     * are this tool's own prompts to its own subagents, captured because a subagent is a session
     * like any other. They are not knowledge, they all have the same first turn, and being filed
     * they collided on one name and overwrote each other twenty-three times over -- so the archive
     * showed one note where twenty-four transcripts had been read.
     *
     * <p>Matched on the shape rather than on this tool's exact wording: an instruction block
     * addressed to a model in the second person is a machine-authored prompt whoever wrote it.
     */
    public static boolean isAgentPrompt(List<Sessions.Turn> turns) {
        for (Sessions.Turn t : turns) {
            if (!t.user()) {
                continue;
            }
            String head = t.text() == null ? "" : t.text().strip();
            String lower = head.toLowerCase(Locale.ROOT);
            // Only the first thing said counts. A person quoting a prompt mid-conversation is
            // having a conversation about prompts, which is a real note.
            return lower.startsWith("you are ")
                    || lower.startsWith(Enrichment.PREAMBLE.toLowerCase(Locale.ROOT))
                    || lower.startsWith("base directory for this skill:")
                    || lower.startsWith("read-only triage for");
        }
        return false;
    }

    /**
     * Whether a session ran somewhere that is never a project.
     *
     * <p>A transcript from a temporary directory is a tool talking to itself: an {@code oss ask}
     * run, a subagent given a scratchpad, a one-off script. Filed as knowledge they produced notes
     * called "Reply with exactly: OK" and "What is my average sentence length in the GitHub
     * comments I have" -- questions asked <em>of</em> this tool, not work done <em>with</em> it.
     *
     * <p>Deliberately not a check for the home folder. People do work there, and excluding it would
     * drop real sessions to catch two.
     */
    public static boolean ranInATempDirectory(String project) {
        if (project == null || project.isBlank()) {
            return false;
        }
        String p = project.toLowerCase(Locale.ROOT);
        return p.startsWith("private-tmp")
                || p.startsWith("tmp-")
                || p.equals("tmp")
                || p.startsWith("var-folders")
                || p.contains("scratchpad");
    }

    // ==========================================
    // Topic
    // ==========================================

    /**
     * The subject a session belongs to.
     *
     * <p>Two sources, in this order. The terms in {@code kb.json} scored across the whole
     * transcript, which is what the rest of the memory already means by "topic". Then the directory
     * the session ran in, which is a fact rather than an inference and is right whenever somebody
     * was working in a checkout. Neither alone was enough: term-scoring puts a session about a
     * build failure under whatever the build was logging, and the directory says nothing at all
     * about the sessions run from a home folder.
     */
    public static Scored topicOf(String transcriptText, String project, Map<String, List<String>> topics) {
        String haystack = transcriptText == null ? "" : transcriptText.toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = 0;
        List<String> bestTerms = List.of();
        for (Map.Entry<String, List<String>> e : topics.entrySet()) {
            int score = 0;
            List<String> hit = new ArrayList<>();
            for (String term : e.getValue()) {
                int n = count(haystack, term.toLowerCase(Locale.ROOT));
                if (n > 0) {
                    score += n;
                    hit.add(term);
                }
            }
            // Strictly greater, so a tie keeps the first topic in the file rather than the last.
            // kb.json is ordered by how much the owner cares; that order is worth honouring.
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
                bestTerms = hit;
            }
        }
        if (best != null && bestTerms.size() >= MIN_TOPIC_HITS) {
            return new Scored(
                    best,
                    bestScore,
                    "matched " + String.join(", ", bestTerms.subList(0, Math.min(4, bestTerms.size()))));
        }
        String fromDirectory = topicOfProject(project, topics);
        if (fromDirectory != null) {
            return new Scored(fromDirectory, 0, "ran in " + project);
        }
        if (best != null) {
            // One term, and no directory to check it against. Better than the junk drawer -- a
            // note about a build that mentions a topic once is still findable under it and is
            // findable under nothing at all in "general" -- but said out loud, because this is the
            // filing most likely to be wrong and the only way to notice is to read why.
            return new Scored(best, bestScore, "only " + String.join(", ", bestTerms) + " matched, so this is a guess");
        }
        return new Scored("general", 0, "nothing in the topic map matched");
    }

    /** A topic and the evidence for it, so a wrong call can be argued with. */
    public record Scored(String topic, int score, String why) {}

    /** The directory's own name, run through the same topic map. */
    static String topicOfProject(String project, Map<String, List<String>> topics) {
        if (project == null || project.isBlank()) {
            return null;
        }
        String p = project.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> e : topics.entrySet()) {
            for (String term : e.getValue()) {
                if (p.contains(term.toLowerCase(Locale.ROOT))) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** Occurrences, not lines. A count of lines under-reports every term used twice in a paragraph. */
    static int count(String haystack, String needle) {
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

    // ==========================================
    // Project
    // ==========================================

    /**
     * Which checkout a session ran in, recovered from the folder Claude Code named after it.
     *
     * <p>The encoding is lossy on purpose by the tool that wrote it: every {@code /} becomes
     * {@code -}, and so does every {@code -} already in a path, so {@code owner/some-name} and
     * {@code owner-some/name} arrive identical. That is fine for this. The string is a label
     * and a set of terms to match on, not a path to open.
     */
    public static String projectOf(Path transcript) {
        Path parent = transcript.getParent();
        if (parent == null) {
            return "";
        }
        String dir = parent.getFileName().toString();
        // Sessions run against a scratchpad carry the real project inside their own name; the
        // useful half is the checkout, not the temporary directory it was given.
        int embedded = dir.indexOf("--Users-");
        if (embedded >= 0) {
            dir = dir.substring(embedded + 1);
        }
        dir = dir.replaceAll("-scratchpad.*$", "");
        // Strip the home prefix, which is on every one of them and identifies nothing.
        String home = System.getProperty("user.home", "");
        String encodedHome = home.replace("/", "-");
        if (dir.startsWith(encodedHome)) {
            dir = dir.substring(encodedHome.length());
        }
        return dir.replaceAll("^-+|-+$", "");
    }

    // ==========================================
    // The note
    // ==========================================

    /**
     * The note, in the shape the rest of the memory already reads.
     *
     * <p>{@code Digest} mines {@code ## The Problem (What & Where)} and {@code ## The Solution
     * (How)} from every note the other harvesters write, and skips a heading with nothing under it.
     * Writing the same two headings here is what makes a terminal session roll into the same digest
     * as a pull request rather than sit in a second pile with its own shape.
     *
     * <p>The frontmatter is where the tool goes, along with everything else that is true about the
     * note but is not what it is about. That is the whole argument of this class in one block: these
     * are attributes, and attributes are not folders.
     */
    public static String noteFor(
            Sessions.Session session, Scored topic, String project, String title, List<String> touched) {
        return noteFor(session, topic, project, title, touched, new Enrichment.Summary("", Enrichment.By.NONE));
    }

    /** The same note, with the paragraph a model wrote at the top of it. */
    public static String noteFor(
            Sessions.Session session,
            Scored topic,
            String project,
            String title,
            List<String> touched,
            Enrichment.Summary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yaml(title)).append('\n');
        sb.append("topic: ").append(topic.topic()).append('\n');
        if (!project.isBlank()) {
            sb.append("project: ").append(yaml(project)).append('\n');
        }
        sb.append("tool: ").append(session.tool()).append('\n');
        if (!session.when().isBlank()) {
            sb.append("date: ").append(session.when()).append('\n');
        }
        sb.append("session: ").append(session.id()).append('\n');
        sb.append("source: session\n");
        sb.append("filed-because: ").append(yaml(topic.why())).append('\n');
        if (summary.present()) {
            sb.append("summarised-by: ").append(summary.by().label()).append('\n');
        }
        sb.append("---\n\n");

        sb.append("# ").append(title).append("\n\n");

        if (summary.present()) {
            // Above the transcript, because it is the only part most readers will read, and
            // attributed, because a sentence a model wrote and a sentence you wrote are worth
            // different amounts and the note must never blur which is which.
            sb.append("## What this settled\n\n");
            sb.append(summary.text()).append("\n\n");
            sb.append("_Summarised by ").append(summary.by().label()).append(" from the transcript below._\n\n");
        }

        sb.append("## The Problem (What & Where)\n\n");
        if (!project.isBlank()) {
            sb.append("Worked on in `")
                    .append(project)
                    .append("`, through ")
                    .append(session.tool())
                    .append(".\n\n");
        }
        boolean askedAnything = false;
        for (Sessions.Turn t : session.raw()) {
            if (t.user()) {
                sb.append("> ").append(t.text().replace("\n", "\n> ")).append("\n\n");
                askedAnything = true;
            }
        }
        if (!askedAnything) {
            sb.append("_No question survived in this transcript -- it is tool calls only._\n\n");
        }

        sb.append("## The Solution (How)\n\n");
        boolean answered = false;
        for (Sessions.Turn t : session.raw()) {
            if (!t.user()) {
                sb.append(t.text()).append("\n\n");
                answered = true;
            }
        }
        if (!answered) {
            sb.append("_Nothing was written back in prose._\n\n");
        }

        if (!touched.isEmpty()) {
            sb.append("## Files in play\n\n");
            for (String f : touched) {
                sb.append("- `").append(f).append("`\n");
            }
            sb.append('\n');
        }

        sb.append("---\n\n");
        sb.append("_Filed by `oss memory sessions` from a ")
                .append(session.tool())
                .append(" transcript. Full transcript: `")
                .append(session.file())
                .append("`_\n");
        return com.osscli.util.Redactor.redact(sb.toString()).text();
    }

    /** Quoted only where it has to be, so the common case reads as prose rather than as data. */
    public static String yaml(String value) {
        String v = value.replace("\"", "'").replaceAll("\\s+", " ").strip();
        return v.contains(":") || v.contains("#") || v.startsWith("-") ? "\"" + v + "\"" : v;
    }

    /** The paths a session actually opened, in the order it first opened them. */
    public static List<String> touched(List<String> paths) {
        Set<String> seen = new LinkedHashSet<>(paths);
        List<String> out = new ArrayList<>(seen);
        return out.size() > MAX_TOUCHED ? out.subList(0, MAX_TOUCHED) : out;
    }
}
