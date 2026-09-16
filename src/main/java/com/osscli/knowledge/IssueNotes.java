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

import com.osscli.memory.ArchiveNotes;
import com.osscli.model.Issue;
import com.osscli.model.Label;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One harvested GitHub thread, as a note in the archive somebody actually reads.
 *
 * <p>{@code harvest} wrote every item it fetched into a flat folder inside the tool's own store,
 * where nothing browses it, nothing backs it up and {@code coverage}, {@code gaps}, {@code map} and
 * {@code digest} — all of which measure the archive {@code kb.json} names — could not see any of
 * it. The archive had 173 such notes, every one of them written by a Python script that no longer
 * exists on this machine: the newest was four weeks old on the day this was written and the index
 * beside them pointed at a folder layout that had been renamed, so all 173 links were dead. Nothing
 * in this program had ever written that folder, so nothing could refresh it.
 *
 * <p><b>Filed by the item, never by the name.</b> The note for {@code owner/name#42} is found by
 * reading {@code github:} out of the notes already on disk, so a title that changes on GitHub
 * rewrites the note that exists rather than filing a second copy beside it under a new slug. That
 * is the whole reason the previous folder could be refreshed at all rather than doubled.
 */
public final class IssueNotes {

    /** The folder, under each topic, that these notes live in. */
    public static final String FOLDER = "issues";

    /** The generated list of all of them, in {@code Projects/issues/}. */
    public static final String INDEX = "00-INDEX.md";

    /**
     * How much of a title becomes the file name.
     *
     * <p>Sixty is what the notes already on disk used, and matching it is not cosmetic: a different
     * limit renames every note whose title is longer than the shorter of the two, and a renamed
     * note is a second copy of the first.
     */
    private static final int SLUG_LIMIT = 60;

    /**
     * One note, and the item it is about.
     *
     * <p>Carries the text it was read with. The walk that finds these notes has already paid for
     * reading them — on an archive that streams, that read was a download — and the next step needs
     * the same bytes to decide whether anything changed. Handing them on costs memory once; reading
     * them again costs the download twice.
     */
    public record Filed(String topic, String repo, String kind, long number, String title, Path path, String text) {}

    private IssueNotes() {}

    /** {@code owner/name#42} — how a note says which item it is about, and how one is found again. */
    public static String id(String repo, long number) {
        return repo + "#" + number;
    }

    /**
     * The file name part of a title.
     *
     * <p>Three rules, every one of them read off the files rather than chosen. Punctuation is
     * <b>dropped</b>, not turned into a separator: {@code feat(events): add ...} is
     * {@code featevents-add-...} on disk and {@code 2.25.1} is {@code 2251}. Whitespace and
     * underscores <b>are</b> separators and a run of them is one dash, so {@code EVP_PKEY_sign} is
     * {@code evp-pkey-sign}. A hyphen somebody typed is kept exactly as typed, which is why
     * {@code effect - the} keeps all three of its dashes.
     *
     * <p>Worth measuring rather than assuming. Mapping punctuation to a dash as well reproduced 74
     * of the 166 names an archive already held; these rules reproduce 166 of 166. The other 92
     * would each have been filed a second time, under a name nobody asked for, beside the note they
     * were meant to replace.
     */
    public static String slug(String title) {
        String text = title == null ? "" : title.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(text.length());
        boolean separating = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '_') {
                separating = out.length() > 0;
                continue;
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                if (separating) {
                    out.append('-');
                    separating = false;
                }
                out.append(c);
            }
        }
        // Trimmed before the cut, not after. A title that opens with a typed hyphen otherwise
        // spends one of its sixty characters on a dash that is then stripped, and the name is a
        // character shorter than the one the archive already has -- which is a second copy.
        String name = out.toString().replaceAll("^-+", "");
        if (name.length() > SLUG_LIMIT) {
            name = name.substring(0, SLUG_LIMIT);
        }
        // A name ending in a separator, or in the dot or space Windows silently drops, refers to a
        // different file than the one asked for.
        name = name.replaceAll("[-. ]+$", "");
        return name.isEmpty() ? "untitled" : name;
    }

    /**
     * The name a note is filed under when it does not have one yet.
     *
     * <p>Zero-padded so the folder sorts by number rather than by digit count.
     */
    public static String nameFor(boolean pullRequest, long number, String title) {
        return (pullRequest ? "pr" : "issue") + "-" + String.format("%05d", number) + "-" + slug(title) + ".md";
    }

    /**
     * What the archive already holds, and whether that answer is the whole of it.
     *
     * <p>The flag is not a detail. A note that was not read is a note that will be written again
     * under a fresh name, beside the one it was meant to replace — so a caller that cannot see all
     * of them must not file any.
     */
    public record Known(
            Map<String, Filed> byItem, Map<String, List<Path>> duplicates, boolean partial, String warning) {}

    /**
     * Every note already filed, by the item it is about.
     *
     * <p>Read through the archive walk rather than by {@code readString} in a loop, because an
     * archive that streams from the cloud answers a read with a download, and that walk is where
     * the deadline for it already lives.
     */
    public static Known filed(Path projects) throws IOException {
        Map<String, Filed> out = new LinkedHashMap<>();
        Map<String, List<Path>> duplicates = new LinkedHashMap<>();
        ArchiveNotes.Walk walk = ArchiveNotes.walk(projects, IssueNotes::inAnIssuesFolder);
        List<ArchiveNotes.Note> inOrder = new ArrayList<>(walk.notes());
        // Sorted, so which copy of a doubled note is the one kept does not depend on the order a
        // filesystem happened to list a folder in.
        inOrder.sort(java.util.Comparator.comparing(n -> n.path().toString()));
        for (ArchiveNotes.Note note : inOrder) {
            Filed filed = read(note.path(), note.text());
            if (filed == null) {
                continue;
            }
            String id = id(filed.repo(), filed.number());
            if (out.putIfAbsent(id, filed) != null) {
                // One thread, two notes. The topic map changed under an old harvester and the same
                // jreleaser threads were filed a second time under a second subject; 14 of them
                // were doubled this way. Both copies answer searches, and the one this run does not
                // rewrite goes on answering with whatever it said in July.
                duplicates.computeIfAbsent(id, k -> new ArrayList<>()).add(filed.path());
            }
        }
        return new Known(out, duplicates, walk.partial(), walk.warning());
    }

    /** Whether a file sits directly inside some topic's {@code issues/} folder. */
    static boolean inAnIssuesFolder(Path file) {
        Path parent = file.getParent();
        return parent != null && FOLDER.equals(parent.getFileName().toString());
    }

    /** What a note on disk says it is about, or null when it says nothing — the index itself, say. */
    static Filed read(Path note, String text) {
        Map<String, String> front = frontmatter(text);
        String github = front.get("github");
        if (github == null || !github.contains("#")) {
            return null;
        }
        try {
            String repo = github.substring(0, github.indexOf('#')).trim();
            long number =
                    Long.parseLong(github.substring(github.indexOf('#') + 1).trim());
            Path topic = note.getParent() == null ? null : note.getParent().getParent();
            return new Filed(
                    topic == null ? "general" : topic.getFileName().toString(),
                    repo,
                    front.getOrDefault("kind", "issue"),
                    number,
                    titleOf(text, repo, number),
                    note,
                    text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The note this item should have, with everything the note on disk already knew.
     *
     * <p>Returns null when the note on disk already says this, so that a harvest which found
     * nothing new writes nothing at all. Without that the timestamp alone would rewrite every note
     * every morning, and an archive under git would record a thousand-file commit a day saying
     * nothing happened.
     *
     * <p>Three fields survive a rewrite, because this command cannot see what wrote them and
     * overwriting them would be a downgrade dressed as an update. Tags and roles are merged rather
     * than replaced — a note filed when the user had reviewed a pull request says
     * {@code reviewer, inline-reviewer}, which costs two more API calls per item to establish and
     * is still true. A state of {@code MERGED} outranks the {@code CLOSED} the search API returns,
     * which cannot tell the two apart.
     */
    public static String rewrite(
            String existing, Issue issue, String repo, List<String> discussion, Set<String> roles, String stamp) {
        Map<String, String> prior = frontmatter(existing);
        Set<String> tags = merge(prior.get("tags"), tagsFor(issue, repo));
        Set<String> allRoles = merge(prior.get("my_role"), roles);
        String state = state(issue, prior.get("state"));
        String body = body(issue, repo, discussion, tags, allRoles, state);
        if (existing != null && body.equals(bodyOf(existing))) {
            return null;
        }
        return frontmatterFor(issue, repo, tags, allRoles, state, stamp) + body;
    }

    /** What the archive's copy of one item looks like below its frontmatter. */
    static String body(
            Issue issue, String repo, List<String> discussion, Set<String> tags, Set<String> roles, String state) {
        String title = issue.title() == null || issue.title().isBlank()
                ? "(no title)"
                : issue.title().strip();
        String kind = issue.isPullRequest() ? "PR" : "Issue";
        StringBuilder sb = new StringBuilder();
        sb.append("# ")
                .append(repo)
                .append(' ')
                .append(kind)
                .append(" #")
                .append(issue.number())
                .append(" — ")
                .append(title)
                .append("\n\n");
        sb.append("**Search Tags/Keywords:** ")
                .append(tags.stream()
                        .map(t -> "#" + t)
                        .reduce((a, b) -> a + " " + b)
                        .orElse(""))
                .append("\n\n");
        sb.append("**GitHub Context:** [")
                .append(repo)
                .append('#')
                .append(issue.number())
                .append("](")
                .append(issue.html_url() == null ? "" : issue.html_url())
                .append(')');
        if (issue.user() != null && issue.user().login() != null) {
            sb.append(" · opened by @").append(issue.user().login());
        }
        sb.append(" · state `").append(state).append('`');
        sb.append(" · my role: **").append(String.join(", ", roles)).append("**\n\n");
        if (issue.labels() != null && !issue.labels().isEmpty()) {
            sb.append("**Labels:** ")
                    .append(issue.labels().stream()
                            .map(Label::name)
                            .map(n -> "`" + n + "`")
                            .reduce((a, b) -> a + " " + b)
                            .orElse(""))
                    .append("\n\n");
        }
        sb.append("---\n\n");
        sb.append("## The Problem (What & Where)\n\n");
        sb.append(
                        issue.body() == null || issue.body().isBlank()
                                ? "(filed with no description)"
                                : issue.body().strip())
                .append("\n\n");
        if (discussion != null && !discussion.isEmpty()) {
            // The reasoning is in the exchange, not in any one message, so the thread is kept in
            // order or not at all.
            sb.append("## The \"Why\" (Review Discussions)\n\n");
            for (String line : discussion) {
                sb.append(line).append("\n\n");
            }
        }
        sb.append("## The Solution (How)\n\n");
        sb.append(
                "closed".equalsIgnoreCase(issue.state())
                        ? "Closed. The thread above carries how it was resolved."
                        : "Still open at the time this was harvested.");
        sb.append('\n');
        return sb.toString();
    }

    private static String frontmatterFor(
            Issue issue, String repo, Set<String> tags, Set<String> roles, String state, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("tags: [").append(String.join(", ", tags)).append("]\n");
        sb.append("github: ").append(id(repo, issue.number())).append('\n');
        if (issue.html_url() != null) {
            sb.append("url: ").append(issue.html_url()).append('\n');
        }
        sb.append("kind: ").append(issue.isPullRequest() ? "pr" : "issue").append('\n');
        sb.append("my_role: ").append(String.join(", ", roles)).append('\n');
        sb.append("source: involved\n");
        sb.append("state: ").append(state).append('\n');
        if (issue.created_at() != null) {
            sb.append("created: ").append(issue.created_at()).append('\n');
        }
        if (issue.updated_at() != null) {
            sb.append("updated: ").append(issue.updated_at()).append('\n');
        }
        sb.append("harvested: ").append(stamp).append('\n');
        sb.append("---\n\n");
        return sb.toString();
    }

    /** What the item is about, as terms: the repository, what kind of thing it is, and its labels. */
    static Set<String> tagsFor(Issue issue, String repo) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("oss");
        tags.add("github");
        for (String part : repo.split("/")) {
            if (!part.isBlank() && !"unknown".equals(part)) {
                tags.add(part);
            }
        }
        tags.add((issue.isPullRequest() ? "pr-" : "issue-") + issue.number());
        if (issue.labels() != null) {
            for (Label label : issue.labels()) {
                if (label.name() != null && !label.name().isBlank()) {
                    tags.add(label.name().replace(' ', '-'));
                }
            }
        }
        return tags;
    }

    /**
     * A state the search API cannot walk back.
     *
     * <p>{@code /search/issues} reports a merged pull request as {@code closed}: the distinction
     * lives on the pull request endpoint, which this does not fetch. So a note that already knows
     * it was merged keeps knowing it.
     */
    private static String state(Issue issue, String prior) {
        String fresh = issue.state() == null ? "OPEN" : issue.state().toUpperCase(Locale.ROOT);
        if (prior != null && prior.startsWith("MERGED") && !"OPEN".equals(fresh)) {
            return prior;
        }
        return fresh;
    }

    /** The generated list of every note on disk, grouped the way somebody browses: topic, then repository. */
    public static String index(Collection<Filed> notes, String stamp) {
        Map<String, Map<String, List<Filed>>> byTopic = new TreeMap<>();
        for (Filed f : notes) {
            byTopic.computeIfAbsent(f.topic(), t -> new TreeMap<>())
                    .computeIfAbsent(f.repo(), r -> new ArrayList<>())
                    .add(f);
        }
        long repositories = notes.stream().map(Filed::repo).distinct().count();

        StringBuilder sb = new StringBuilder();
        sb.append("---\ntags: [oss, github, index]\nharvested: ").append(stamp).append("\n---\n\n");
        sb.append("# OSS GitHub activity — index\n\n");
        sb.append("**Search Tags/Keywords:** #oss #github #index #issue #pullrequest\n");
        sb.append("**")
                .append(notes.size())
                .append(" thread(s) across ")
                .append(repositories)
                .append(" repositories, ")
                .append(byTopic.size())
                .append(" topics.**\n\n");
        sb.append("Written by `oss memory harvest` and rebuilt from the folders on every run — "
                + "do not hand-edit; put your own notes in `Projects/<topic>/`.\n\n");
        sb.append("---\n");
        byTopic.forEach((topic, byRepo) -> {
            sb.append("\n# ").append(topic).append("\n");
            byRepo.forEach((repo, items) -> {
                items.sort(java.util.Comparator.comparingLong(Filed::number));
                sb.append("\n## ")
                        .append(repo)
                        .append("  (")
                        .append(items.size())
                        .append(")\n\n");
                for (Filed f : items) {
                    sb.append("- [")
                            .append(repo)
                            .append(' ')
                            .append("pr".equals(f.kind()) ? "PR" : "Issue")
                            .append(" #")
                            .append(f.number())
                            .append(" — ")
                            .append(f.title())
                            .append("](../")
                            .append(topic)
                            .append('/')
                            .append(FOLDER)
                            .append('/')
                            .append(f.path().getFileName())
                            .append(")\n");
                }
            });
        });
        return sb.toString();
    }

    // ------------------------------------------------------------------ reading ---

    /** The {@code key: value} lines of a note's frontmatter, or empty when it has none. */
    public static Map<String, String> frontmatter(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || !text.startsWith("---")) {
            return out;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        for (String line : text.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                out.put(
                        line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim());
            }
        }
        return out;
    }

    /** Everything below the frontmatter, which is the part worth comparing. */
    public static String bodyOf(String text) {
        if (text == null) {
            return "";
        }
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return text;
        }
        int newline = text.indexOf('\n', end + 1);
        if (newline < 0) {
            return "";
        }
        return text.substring(newline + 1).replaceFirst("^\n", "");
    }

    /** The title a note carries in its heading, so the index can name it without being told. */
    static String titleOf(String text, String repo, long number) {
        for (String line : bodyOf(text).split("\n")) {
            if (line.startsWith("# ")) {
                int dash = line.indexOf(" — ");
                return dash > 0
                        ? line.substring(dash + 3).strip()
                        : line.substring(2).strip();
            }
        }
        return id(repo, number);
    }

    /** Everything the note already listed, plus everything this harvest can see. */
    private static Set<String> merge(String prior, Set<String> fresh) {
        Set<String> out = new LinkedHashSet<>();
        if (prior != null) {
            String cleaned = prior.replace("[", "").replace("]", "");
            for (String part : cleaned.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        out.addAll(fresh);
        out.remove("");
        return out;
    }
}
