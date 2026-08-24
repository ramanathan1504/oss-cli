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
package com.osscli.retrieval;

import com.osscli.model.Issue;
import com.osscli.model.RepoIssue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What to work on next, scored against what you have already written or reviewed.
 *
 * <p>This was inside {@code PickCommand} for the same reason {@link com.osscli.review.Waiting} was
 * inside {@code HubCommand}, and it had the same consequence: the ranking existed only as printed
 * text, so the board page could show it only by running the command and pasting forty-nine lines of
 * terminal into a browser. A score is a number and a match is a list of titles; neither survives
 * being turned into a column of spaces.
 *
 * <p>So the arithmetic is here and the renderings are elsewhere. {@code pick} prints it,
 * {@code /api/suggestions} serialises it, and there is one implementation of "why this one" for
 * both — which matters because "because you wrote: …" is the whole claim the ranking rests on.
 *
 * <p><b>No model required.</b> Term overlap against your own corpus is arithmetic and works on a
 * machine with nothing installed. A local model makes the matching better; its absence costs
 * quality, not the feature.
 */
public final class Suggestions {

    /** Reviewing something means you read it. Filing a note means you thought it worth keeping. */
    public static final int WEIGHT_REVIEWED = 4;

    public static final int WEIGHT_NOTE = 1;

    private Suggestions() {}

    /** One suggestion, and the things of yours it matched. */
    public record Item(String repo, long number, String title, double score, List<String> because, boolean pull) {}

    /**
     * Why the list is what it is, including why it is empty.
     *
     * <p>Three different nothings, kept apart. "Nothing to score against" means you have not
     * started; "nothing cached" means you have not synced; "nothing overlaps" means both are done
     * and the answer is genuinely no — and that last one is a real answer rather than a failure,
     * which a single blank list would not have told anybody.
     */
    public enum Why {
        OK,
        NO_PROFILE,
        NOTHING_SYNCED,
        NO_OVERLAP
    }

    /** The ranking, and the numbers that say how much to trust it. */
    public record Result(
            Why why, List<Item> items, int profileSize, boolean semantic, int candidates, int embeddedHere) {

        public boolean empty() {
            return items.isEmpty();
        }

        /** How the matching was done, in the words both renderings use. */
        public String how() {
            return semantic ? "by meaning" : "by shared terms";
        }
    }

    /** Told how far along the scoring is, so a caller can show it or ignore it. */
    public interface Progress {
        void step(int done, int total);

        /**
         * Whatever the corpus says while it loads.
         *
         * <p>Loading is the slow half on a cold store, and dropping its messages would put the
         * silence back that the status line was added to remove -- this time before the counter
         * starts, where there is nothing at all to look at.
         */
        default void note(String what) {}

        Progress SILENT = (done, total) -> {};
    }

    /**
     * Score the open backlog against your own corpus.
     *
     * @param repoFilter only this repository, as {@code owner/name}; null or blank for all
     * @param limit how many to return
     * @param issuesOnly skip pull requests
     */
    public static Result read(String repoFilter, int limit, boolean issuesOnly, Progress progress) throws Exception {
        Corpus profile = Corpus.load(progress::note);
        if (profile.size() == 0) {
            return new Result(Why.NO_PROFILE, List.of(), 0, false, 0, 0);
        }

        List<RepoIssue> all = com.osscli.storage.SqliteStorage.loadAllIssues();
        if (all.isEmpty()) {
            return new Result(Why.NOTHING_SYNCED, List.of(), profile.size(), profile.semantic(), 0, 0);
        }

        // Read the issue vectors sync already wrote, rather than making them again. Embedding every
        // open issue here was one ONNX inference per issue -- 15,935 of them on a real store, a
        // hundred seconds of silence, and a SIGSEGV inside onnxruntime on an 8 GB machine. The
        // vector on disk is literally the same vector, so the answer is identical and the
        // inference count is zero for anything synced.
        Map<String, double[]> vectors = new HashMap<>();
        if (profile.bySimilarity()) {
            for (com.osscli.model.IssueEmbedding e : com.osscli.storage.SqliteStorage.loadAllEmbeddings()) {
                vectors.put(e.repository() + "#" + e.issueNumber(), e.vector());
            }
        }

        List<RepoIssue> candidates = new ArrayList<>();
        for (RepoIssue ri : all) {
            Issue i = ri.issue();
            if (!"open".equalsIgnoreCase(i.state())) {
                continue;
            }
            if (issuesOnly && i.isPullRequest()) {
                continue;
            }
            if (repoFilter != null && !repoFilter.isBlank() && !ri.repository().equalsIgnoreCase(repoFilter.trim())) {
                continue;
            }
            candidates.add(ri);
        }

        List<Scored> scored = new ArrayList<>();
        int embedded = 0;
        int done = 0;
        for (RepoIssue ri : candidates) {
            Issue i = ri.issue();
            double[] q = vectors.get(ri.repository() + "#" + i.number());
            List<Corpus.Hit> hits;
            if (q != null) {
                hits = profile.searchByVector(q, 3);
            } else {
                // Not synced with this model. Embedding it here is what used to be done for every
                // issue; doing it for the few that need it is the difference between a handful of
                // inferences and fifteen thousand.
                String text = (i.title() == null ? "" : i.title()) + " " + (i.body() == null ? "" : i.body());
                hits = profile.search(text, 3);
                embedded++;
            }
            if (++done % 500 == 0) {
                progress.step(done, candidates.size());
            }
            if (hits.isEmpty()) {
                continue;
            }
            double s = hits.stream().mapToDouble(Corpus.Hit::score).sum();
            scored.add(new Scored(ri, s, hits));
        }
        progress.step(candidates.size(), candidates.size());

        if (scored.isEmpty()) {
            return new Result(
                    Why.NO_OVERLAP, List.of(), profile.size(), profile.semantic(), candidates.size(), embedded);
        }

        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score));
        List<Item> items = new ArrayList<>();
        for (Scored x : scored) {
            if (items.size() >= limit) {
                break;
            }
            Issue i = x.ri.issue();
            items.add(new Item(
                    x.ri.repository(),
                    i.number(),
                    i.title() == null ? "" : i.title(),
                    x.score,
                    because(x.hits),
                    i.isPullRequest()));
        }
        return new Result(Why.OK, List.copyOf(items), profile.size(), profile.semantic(), candidates.size(), embedded);
    }

    /**
     * What of yours it matched on.
     *
     * <p>Naming this is the difference between a ranking you can act on and one you have to take on
     * faith. It is also how you notice when it matched on nothing useful.
     */
    private static List<String> because(List<Corpus.Hit> hits) {
        return hits.stream()
                .map(Corpus.Hit::title)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .limit(2)
                .toList();
    }

    private record Scored(RepoIssue ri, double score, List<Corpus.Hit> hits) {}
}
