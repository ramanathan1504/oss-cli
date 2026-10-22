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

import java.util.ArrayList;
import java.util.List;

/**
 * One change of yours that reached a release branch, as a note.
 *
 * <p>The archive was full of what was <em>discussed</em> and held almost nothing about what
 * actually landed. Forty commits across {@code 2.x} and {@code main} -- the work that survived
 * review, the thing an employer or a PMC would look at -- existed only as lines in somebody else's
 * git history.
 *
 * <p>The record is assembled from two places and neither is optional. Git holds what changed: the
 * commit, the files, the diffstat, the branch it landed on, the date. GitHub holds why it changed
 * that way: the description, the review that asked for something different, the comment that
 * explained the constraint nobody had written down. A note with only the first is a changelog
 * entry; with only the second it is a conversation about code that may never have merged.
 *
 * <p><b>Read-only, and it stays that way.</b> This clones nothing, pushes nothing, comments
 * nowhere and opens nothing. Every call is a GET and every git command is a read.
 */
public final class Contribution {

    /** How many review threads are worth keeping. Past this it was a design argument, not a review. */
    private static final int MAX_THREADS = 60;

    private Contribution() {}

    /** One landed change: what git knows, and what the conversation knows. */
    public record Landed(
            String repo,
            int pr,
            String title,
            String sha,
            String branch,
            String mergedOn,
            String body,
            List<String> files,
            int insertions,
            int deletions,
            String commitMessage,
            List<Remark> conversation,
            List<String> timeline,
            boolean coAuthored) {}

    /** One thing somebody said, kept with who said it and where. */
    public record Remark(String author, String when, String where, String text) {}

    /**
     * The note.
     *
     * <p>Uses the three headings every other harvester writes, because {@code Digest} mines those
     * and a fourth shape would sit in its own pile. The review discussion is the section that only
     * this can fill: it is the half of the work that never appears in a diff.
     */
    public static String noteFor(Landed c, String topic) {
        StringBuilder sb = new StringBuilder();
        // Recorded both ways rather than only when co-authored. A co-authored change is real work
        // and it is not sole authorship, and a record that leaves the key out when it is sole
        // authorship cannot be told apart from one that never knew.
        sb.append(FrontMatter.of(
                        c.repo() + " PR " + c.pr() + " — " + c.title(),
                        "contribution",
                        topic,
                        c.mergedOn(),
                        "contribution",
                        List.of(topic))
                .put("project", c.repo())
                .put("pr", c.pr())
                .put("branch", c.branch())
                .put("commit", c.sha())
                .put("merged", c.mergedOn())
                .put("role", c.coAuthored() ? "co-author" : "author")
                .put("files", c.files().size())
                .put("insertions", c.insertions())
                .put("deletions", c.deletions())
                .render());

        sb.append("# ")
                .append(c.repo())
                .append(" PR ")
                .append(c.pr())
                .append(" — ")
                .append(c.title())
                .append("\n\n");
        sb.append("Merged to `")
                .append(c.branch())
                .append("` on ")
                .append(c.mergedOn())
                .append(" as `")
                .append(shortSha(c.sha()))
                .append("`.");
        if (c.coAuthored()) {
            sb.append(" Co-authored.");
        }
        sb.append("\n\n");

        sb.append("## The Problem (What & Where)\n\n");
        if (c.body() == null || c.body().isBlank()) {
            sb.append("_The pull request carried no description._\n\n");
        } else {
            sb.append(c.body().strip()).append("\n\n");
        }

        sb.append("## The Solution (How)\n\n");
        if (c.commitMessage() != null && !c.commitMessage().isBlank()) {
            sb.append(c.commitMessage().strip()).append("\n\n");
        }
        sb.append("`")
                .append(c.files().size())
                .append(" file(s), +")
                .append(c.insertions())
                .append(" −")
                .append(c.deletions())
                .append("`\n\n");
        for (String f : c.files()) {
            sb.append("- `").append(f).append("`\n");
        }
        sb.append('\n');

        sb.append("## The \"Why\" (Review Discussions)\n\n");
        if (c.conversation().isEmpty()) {
            // Said plainly. An empty section reads as though the work was reviewed and nothing was
            // said, which for a change that merged unopposed is exactly true and worth knowing.
            sb.append("_Nothing was said on this one. It merged without discussion._\n\n");
        } else {
            for (Remark r : c.conversation()) {
                sb.append("**").append(r.author()).append("**");
                if (!r.where().isBlank()) {
                    sb.append(" on `").append(r.where()).append('`');
                }
                if (!r.when().isBlank()) {
                    sb.append(" · ").append(r.when());
                }
                sb.append("\n\n");
                sb.append("> ").append(r.text().strip().replace("\n", "\n> ")).append("\n\n");
            }
        }

        if (!c.timeline().isEmpty()) {
            sb.append("## Timeline\n\n");
            for (String t : c.timeline()) {
                sb.append("- ").append(t).append('\n');
            }
            sb.append('\n');
        }

        return com.osscli.util.Redactor.redact(sb.toString()).text();
    }

    /** Long enough to be unambiguous in this repository, short enough to read. */
    static String shortSha(String sha) {
        return sha == null ? "" : sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    /** Threads worth keeping, oldest first, capped. */
    public static List<Remark> trim(List<Remark> all) {
        return all.size() <= MAX_THREADS ? all : new ArrayList<>(all.subList(0, MAX_THREADS));
    }
}
