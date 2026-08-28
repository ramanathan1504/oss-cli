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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finding the changes of yours that actually landed, and reading their whole record.
 *
 * <p>Two sources, because either alone is a half-record. A local checkout answers what changed, when
 * it landed and on which branch, for free and offline. GitHub answers why it changed that way -- the
 * description, the review that asked for something different, the constraint somebody mentioned once
 * in a thread and never wrote down anywhere else.
 *
 * <h2>Author is not the whole answer</h2>
 *
 * <p>{@code git log --author} found 22 commits on {@code 2.x}. Counting co-authored trailers as well
 * found 40 across {@code 2.x} and {@code main}. A change somebody else pushed with your name in the
 * trailer is your work, and a record that counts only what you pushed yourself understates the
 * contribution by nearly half. Both are collected, and the note says which it was rather than
 * quietly claiming the stronger one.
 *
 * <h2>Read-only</h2>
 *
 * <p>Every git command here is a read and every GitHub call is a GET. Nothing is cloned, checked
 * out, fetched into a working tree, pushed, commented on or opened. The upstream repository is a
 * source of facts and never a destination.
 */
public final class Contributions {

    /** Branches a change has to reach before it counts as landed. */
    public static final List<String> RELEASE_BRANCHES = List.of("origin/2.x", "origin/main", "origin/master");

    /** How long any single git command may take before it is abandoned. */
    private static final long GIT_TIMEOUT_SECONDS = 30;

    /**
     * Separators git is asked to put between fields and records.
     *
     * <p>Ordinary text rather than the ASCII unit separators the job calls for, and deliberately so:
     * a control character in a source file is invisible in every diff, every review and every
     * terminal that shows this line, so the one time it is typed wrongly nobody can see why the
     * parse broke. These cannot occur in a commit subject or body either, and they can be read.
     */
    private static final String FIELD = "@@F@@";

    private static final String RECORD = "@@R@@";

    private Contributions() {}

    /** One commit found in the local history, before GitHub has been asked anything. */
    public record Landing(
            String sha, String branch, String date, String subject, String message, int pr, boolean coAuthored) {}

    // ==========================================
    // What the checkout knows
    // ==========================================

    /**
     * Every commit on a release branch that is yours, by authorship or by trailer.
     *
     * <p>Deduplicated by sha across branches: a change merged to {@code 2.x} and then forward-merged
     * to {@code main} is one piece of work, and filing it twice would double the record without
     * adding to it. The branches are tried oldest release line first, so the note names where the
     * change actually landed rather than where it later appeared.
     */
    public static List<Landing> landed(Path checkout, String name) throws IOException {
        return landed(checkout, identities(checkout, name));
    }

    /**
     * The same, given every name the person commits under.
     *
     * <p>Separate from {@link #identities} so a caller can say exactly whose work to look for, and
     * so this can be tested against a repository whose history a test made.
     */
    public static List<Landing> landed(Path checkout, List<String> names) throws IOException {
        Map<String, Landing> bySha = new LinkedHashMap<>();
        for (String branch : RELEASE_BRANCHES) {
            if (!hasBranch(checkout, branch)) {
                continue;
            }
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                collect(checkout, branch, List.of("--author=" + name, "--regexp-ignore-case"), false, bySha);
                collect(
                        checkout,
                        branch,
                        List.of("--grep=Co-authored-by:.*" + name, "--regexp-ignore-case"),
                        true,
                        bySha);
            }
        }
        return new ArrayList<>(bySha.values());
    }

    /**
     * Every name this person's commits could carry.
     *
     * <p>A GitHub login and a git author are different identities and nothing keeps them in step.
     * Searching this history for {@code ramanathan1504} -- which is the login, and the only name the
     * tool knew -- matched nothing at all, while the commits were authored as
     * {@code Ramanathan <ramanathanbscmca@gmail.com>}. The command reported "no commits of yours"
     * about forty of them.
     *
     * <p>So all of it is tried: the login, and whatever this checkout is configured to commit as.
     * Matches are deduplicated by sha afterwards, which makes an over-broad name cost a little time
     * and never a wrong count.
     */
    public static List<String> identities(Path checkout, String login) {
        List<String> names = new ArrayList<>();
        if (login != null && !login.isBlank()) {
            names.add(login.strip());
        }
        for (String key : List.of("user.name", "user.email")) {
            try {
                String value =
                        git(checkout, List.of("git", "config", "--get", key)).strip();
                if (!value.isBlank() && !names.contains(value)) {
                    names.add(value);
                }
            } catch (IOException e) {
                // An unset git identity is ordinary on a machine that only reads repositories.
            }
        }
        return names;
    }

    private static void collect(
            Path checkout, String branch, List<String> filters, boolean coAuthored, Map<String, Landing> into)
            throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "log", branch));
        cmd.addAll(filters);
        // A newline-delimited format cannot be parsed back: these subjects contain every
        // punctuation mark there is, and the bodies are multi-line by definition.
        cmd.add("--format=%H" + FIELD + "%ad" + FIELD + "%s" + FIELD + "%b" + RECORD);
        cmd.add("--date=short");
        String out = git(checkout, cmd);
        for (String record : out.split(java.util.regex.Pattern.quote(RECORD))) {
            if (record.isBlank()) {
                continue;
            }
            String[] parts = record.strip().split(java.util.regex.Pattern.quote(FIELD), -1);
            if (parts.length < 3) {
                continue;
            }
            String sha = parts[0].strip();
            if (into.containsKey(sha)) {
                continue;
            }
            String subject = parts[2];
            into.put(
                    sha,
                    new Landing(
                            sha,
                            branch.replace("origin/", ""),
                            parts[1],
                            subject,
                            parts.length > 3 ? parts[3] : "",
                            prNumberIn(subject),
                            coAuthored));
        }
    }

    /**
     * The pull request a commit came from, out of its subject line.
     *
     * <p>The squash-merge convention puts it there -- {@code "Fix the thing (#812)"} -- and the last
     * number on the line is the merge itself. Earlier numbers are references to the issues it fixed,
     * which are worth reading and are not this commit's pull request.
     */
    static int prNumberIn(String subject) {
        if (subject == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\d+)").matcher(subject);
        int last = 0;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    /** What a commit did, in numbers. */
    public record Diffstat(List<String> files, int insertions, int deletions) {}

    /** The files a commit touched, and how much of each. */
    public static Diffstat diffstat(Path checkout, String sha) throws IOException {
        String out = git(checkout, List.of("git", "show", "--numstat", "--format=", sha));
        List<String> files = new ArrayList<>();
        int plus = 0;
        int minus = 0;
        for (String line : out.split("\n")) {
            String[] cols = line.strip().split("\t");
            if (cols.length < 3) {
                continue;
            }
            files.add(cols[2]);
            // A binary file reports "-" for both counts. Counting it as zero is right; parsing it as
            // a number is a crash on the one commit that touched an image.
            plus += safeInt(cols[0]);
            minus += safeInt(cols[1]);
        }
        return new Diffstat(files, plus, minus);
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Which remote repository this checkout is of, as {@code owner/repo}. */
    public static String remoteOf(Path checkout) throws IOException {
        String url =
                git(checkout, List.of("git", "remote", "get-url", "origin")).strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[:/]([\\w.-]+)/([\\w.-]+?)(\\.git)?$")
                .matcher(url);
        return m.find() ? m.group(1) + "/" + m.group(2) : "";
    }

    static boolean hasBranch(Path checkout, String branch) {
        try {
            git(checkout, List.of("git", "rev-parse", "--verify", "--quiet", branch));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Run one git command and read its output.
     *
     * <p>Bounded, and descendants killed with it. This package paid for an unbounded wait on a child
     * process once already this week, and a git command against a large repository is exactly the
     * shape that pays for it again.
     */
    static String git(Path checkout, List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(checkout.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IOException("git did not answer within " + GIT_TIMEOUT_SECONDS + "s: " + cmd);
            }
            if (p.exitValue() != 0) {
                throw new IOException("git exited " + p.exitValue() + ": " + out.strip());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running git", e);
        }
    }

    // ==========================================
    // What the conversation knows
    // ==========================================

    /** What GitHub had to say about one change. */
    public record Conversation(
            String title, String body, String mergedAt, List<Contribution.Remark> remarks, List<String> timeline) {}

    /**
     * Everything said on one pull request: the description, the reviews, the line comments.
     *
     * <p>Three endpoints, because GitHub keeps them apart. {@code /issues/N/comments} holds the
     * discussion; {@code /pulls/N/comments} holds the remarks pinned to lines of the diff, which is
     * where the actual review lives; {@code /pulls/N/reviews} holds the verdicts and the bodies
     * attached to them. Reading only the first -- which is what "fetch the comments" usually means
     * -- misses the review entirely.
     *
     * <p>One unreachable endpoint costs that endpoint. A note with the description and no line
     * comments is worth writing; refusing to write it because one call failed is not.
     */
    public static Conversation conversationOn(com.osscli.github.GitHubClient gh, String repo, int pr) {
        List<Contribution.Remark> remarks = new ArrayList<>();
        List<String> timeline = new ArrayList<>();
        String body = "";
        String mergedAt = "";
        String title = "";

        try {
            // getJson answers null for a 404, which is what a pull request number taken from a
            // commit subject gets when that number was an issue reference rather than the merge.
            // Handed to readTree that is `argument "content" is null` -- a complaint about an
            // argument where the reader needed an answer about a pull request.
            String json = gh.getJson("/repos/" + repo + "/pulls/" + pr);
            JsonNode node = new ObjectMapper().readTree(json == null ? "{}" : json);
            body = node.path("body").asText("");
            title = node.path("title").asText("");
            mergedAt = day(node.path("merged_at").asText(""));
            timeline.add(day(node.path("created_at").asText("")) + " - opened by @"
                    + node.path("user").path("login").asText("?"));
            if (!mergedAt.isBlank()) {
                timeline.add(mergedAt + " - merged");
            }
        } catch (Exception e) {
            // The note is still worth writing from git alone, and says so by carrying no body.
        }

        remarks.addAll(read(gh, "/repos/" + repo + "/issues/" + pr + "/comments", false));
        remarks.addAll(read(gh, "/repos/" + repo + "/pulls/" + pr + "/comments", true));
        remarks.addAll(read(gh, "/repos/" + repo + "/pulls/" + pr + "/reviews", false));
        remarks.sort(java.util.Comparator.comparing(Contribution.Remark::when));
        return new Conversation(title, body, mergedAt, Contribution.trim(remarks), timeline);
    }

    private static List<Contribution.Remark> read(com.osscli.github.GitHubClient gh, String path, boolean onALine) {
        List<Contribution.Remark> out = new ArrayList<>();
        try {
            // Two pages. A thread past two hundred remarks is one nobody reads to the end.
            for (Map<String, Object> c : gh.getPaged(path, 2)) {
                String text = String.valueOf(c.getOrDefault("body", ""));
                if (text.isBlank() || "null".equals(text)) {
                    // A review with no body is an approval click. That belongs in the timeline, not
                    // in a section of things people said.
                    continue;
                }
                Object user = c.get("user");
                String who = user instanceof Map<?, ?> m ? String.valueOf(m.get("login")) : "?";
                String where = "";
                if (onALine && c.get("path") != null) {
                    where = String.valueOf(c.get("path"));
                    if (c.get("line") != null) {
                        where = where + ":" + c.get("line");
                    }
                }
                // Reviews date themselves with submitted_at; comments use created_at. Reading only
                // the second left every review verdict undated, which put them all at the front of
                // a list sorted by date -- so the record showed the approval before the discussion
                // that led to it.
                String when = String.valueOf(c.getOrDefault("created_at", c.getOrDefault("submitted_at", "")));
                if (when.isBlank() || "null".equals(when)) {
                    when = String.valueOf(c.getOrDefault("submitted_at", ""));
                }
                out.add(new Contribution.Remark(who, day(when), where, text));
            }
        } catch (Exception e) {
            // One unreachable endpoint costs that endpoint, not the note.
        }
        return out;
    }

    private static String day(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
    }

    /** A file name that is stable, so a second run rewrites rather than duplicates. */
    public static String nameFor(Contribution.Landed c) {
        return c.mergedOn() + "-pr-" + c.pr() + "-" + SessionNotes.slug(c.title()) + ".md";
    }

    /** Everything about one change as one lower-cased string, for the topic scorer. */
    public static String textOf(Landing l, Conversation c) {
        StringBuilder sb = new StringBuilder(l.subject()).append(' ').append(l.message());
        if (c != null) {
            sb.append(' ').append(c.body());
            for (Contribution.Remark r : c.remarks()) {
                sb.append(' ').append(r.text());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
