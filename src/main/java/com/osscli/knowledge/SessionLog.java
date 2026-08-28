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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One note per thing you worked on, with each session appended to it.
 *
 * <p>Filing a note per session was the obvious thing and it fragmented the record. Four days of
 * work on one issue produced five files -- {@code ...issue-3704-smtp-header-feature...},
 * {@code ...issue-3704-1-2025-07-16-the-values...}, {@code ...issue-3704-in-the-when-we-sart...} --
 * each a fifth of the story, none of them the place to look. The same held for four pull requests
 * with four notes apiece.
 *
 * <p>So when a session names a pull request or an issue, that reference is the file. Every session
 * about it appends a dated section, and the note grows the way the work did.
 *
 * <h2>Appending has to be idempotent</h2>
 *
 * <p>This runs hourly and a session is re-read every time it grows, so "append" cannot mean "add
 * to the end". Each section is fenced by a comment carrying its session id; re-filing replaces that
 * section in place and leaves every other one alone. Without it an afternoon of work would add its
 * own section once an hour until the file was unreadable.
 *
 * <p><b>It never writes over a note you wrote.</b> A hand-written {@code Issue-3704.md} and a
 * generated {@code issue-3704.md} are the same file on macOS, and the generated one would silently
 * replace a page somebody had spent an afternoon on. A file without this class's own marker in it
 * is somebody else's, and the log goes beside it under a different name.
 */
public final class SessionLog {

    /** What marks a file as one of these, so nothing else is ever overwritten. */
    public static final String MARKER = "source: session-log";

    private SessionLog() {}

    /** The fence that opens one session's section. */
    static String open(String sessionId) {
        return "<!-- session:" + sessionId + " -->";
    }

    /** The fence that closes it. */
    static String close(String sessionId) {
        return "<!-- /session:" + sessionId + " -->";
    }

    /**
     * Where the running log for one reference lives.
     *
     * <p>Named after the subject rather than the day, because the subject is what somebody looks
     * for. The fallback name exists for the collision described above and is checked against the
     * file's content, not its name: only a file carrying this class's marker may be appended to.
     */
    public static Path pathFor(Path archive, String topic, String reference) {
        // A pull request is its number, wherever it was mentioned and however it was spelled.
        //
        // Without this one pull request had four files: "PR 841" from somebody typing it and
        // "ff-webapp-backend PR 841" from a pasted URL, each of those again under two topics
        // because two sessions about the same change scored differently. Four fifths of the point
        // of a running log, undone by the name it was filed under.
        Path already = existingLogFor(archive, reference);
        if (already != null) {
            return already;
        }
        Path preferred = archive.resolve("Projects").resolve(topic).resolve(SessionNotes.slug(reference) + ".md");
        if (!Files.exists(preferred) || isOurs(preferred)) {
            return preferred;
        }
        // Somebody's own note is already at that name -- on a case-insensitive filesystem it may
        // not even look like the same name. Go beside it rather than through it.
        return archive.resolve("Projects").resolve(topic).resolve(SessionNotes.slug(reference) + "-sessions.md");
    }

    /**
     * What identifies a reference regardless of how it was written.
     *
     * <p>{@code "PR 841"}, {@code "ff-webapp-backend PR 841"} and a repository-qualified spelling
     * of the same thing all reduce to {@code pr-841}. The repository is not part of the identity
     * on purpose: a session that names a bare number cannot supply one, and refusing to match it
     * would recreate the split this exists to close.
     */
    static String identityOf(String reference) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\b(pr|issue)\\s+(\\d+)\\b")
                .matcher(reference == null ? "" : reference);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) + "-" + m.group(2) : "";
    }

    /**
     * An existing log for the same reference, in whatever topic it ended up under.
     *
     * <p>Searched across topics because the topic is scored per session and two sessions about one
     * change can land in two subjects -- which is a filing detail, not a reason to split the record
     * of one piece of work.
     */
    static Path existingLogFor(Path archive, String reference) {
        String identity = identityOf(reference);
        if (identity.isEmpty()) {
            return null;
        }
        Path projects = archive.resolve("Projects");
        if (!Files.isDirectory(projects)) {
            return null;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(projects, 2)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".md") || !name.toLowerCase(Locale.ROOT).contains(identity)) {
                    continue;
                }
                if (isOurs(file)) {
                    return file;
                }
            }
        } catch (IOException e) {
            // Unreadable means "cannot find one", and a new log is the safe answer to that.
            return null;
        }
        return null;
    }

    /** True when this file is a running log this code wrote, rather than a note somebody kept. */
    static boolean isOurs(Path file) {
        try {
            // Only the head: the marker is in the frontmatter, and reading a 140 KB note to decide
            // is a cost paid per session per hour.
            String head = Files.readString(file, StandardCharsets.UTF_8);
            return head.length() > 400 ? head.substring(0, 400).contains(MARKER) : head.contains(MARKER);
        } catch (IOException e) {
            // Unreadable means "cannot prove it is mine", and the safe reading of that is that it
            // belongs to somebody else.
            return false;
        }
    }

    /** The header a new log starts with. */
    static String headerFor(String reference, String topic, String project) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(SessionNotes.yaml(reference)).append('\n');
        sb.append("topic: ").append(topic).append('\n');
        if (project != null && !project.isBlank()) {
            sb.append("project: ").append(SessionNotes.yaml(project)).append('\n');
        }
        sb.append("kind: running-log\n");
        sb.append(MARKER).append('\n');
        sb.append("---\n\n");
        sb.append("# ").append(reference).append("\n\n");
        sb.append("_Every session about this, oldest first. Each block is one session; re-reading a "
                + "session replaces its own block and leaves the rest alone._\n");
        return sb.toString();
    }

    /**
     * Add or replace one session's section in the log for a reference.
     *
     * @return true when the file was created rather than added to
     */
    public static boolean append(
            Path log, String reference, String topic, String project, String sessionId, String body)
            throws IOException {
        Files.createDirectories(log.getParent());
        boolean created = !Files.exists(log);
        String existing =
                created ? headerFor(reference, topic, project) : Files.readString(log, StandardCharsets.UTF_8);

        String section = open(sessionId) + "\n" + body.strip() + "\n" + close(sessionId) + "\n";

        int from = existing.indexOf(open(sessionId));
        if (from >= 0) {
            int to = existing.indexOf(close(sessionId), from);
            if (to >= 0) {
                // Replace in place. Appending instead would add this session's block once an hour
                // for as long as the session kept growing.
                existing = existing.substring(0, from)
                        + section
                        + existing.substring(to + close(sessionId).length() + 1);
                Files.writeString(log, existing, StandardCharsets.UTF_8);
                return created;
            }
        }
        Files.writeString(log, existing.stripTrailing() + "\n\n" + section, StandardCharsets.UTF_8);
        return created;
    }

    /** How many sessions a log already holds, for a caller that wants to say so. */
    public static int sessionsIn(Path log) {
        try {
            String text = Files.readString(log, StandardCharsets.UTF_8);
            int n = 0;
            int at = text.indexOf("<!-- session:");
            while (at >= 0) {
                n++;
                at = text.indexOf("<!-- session:", at + 1);
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    /** The dated heading one session contributes to the log. */
    public static String sectionFor(String day, String title, Enrichment.Summary summary, String turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(day);
        String trimmed = title == null ? "" : title.strip();
        if (!trimmed.isEmpty() && !trimmed.toLowerCase(Locale.ROOT).startsWith("pr ")) {
            sb.append(" · ").append(trimmed);
        }
        sb.append("\n\n");
        if (summary != null && summary.present()) {
            sb.append(summary.text()).append("\n\n");
            sb.append("_Summarised by ").append(summary.by().label()).append("._\n\n");
        }
        sb.append(turns.strip()).append('\n');
        return sb.toString();
    }
}
