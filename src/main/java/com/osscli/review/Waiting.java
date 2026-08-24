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
package com.osscli.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Whose move it is, on everything you have reviewed.
 *
 * <p>This was inside {@code HubCommand}, which is where it stopped being usable. The answer is a
 * list of pull requests with a reason attached to each, and the command turned it straight into
 * {@code printf} — so the only way anything else could have it was to run the command and read the
 * text back, which is not having the answer, it is having a picture of it. The board page did
 * exactly that and rendered a terminal transcript in a browser.
 *
 * <p>So the computation is here and the two renderings are elsewhere: {@code hub} prints it,
 * {@code /api/waiting} serialises it. One implementation, and the two cannot disagree about who is
 * waiting on you — which they would, immediately, if the page had its own copy of the rule.
 *
 * <p><b>The rule itself,</b> in one place because it is the whole point: it is on you when the pull
 * request is open, not merged, and either the author pushed after you looked or the last word is
 * somebody else's. Both mean the thing you decided was decided against a state that no longer
 * exists.
 */
public final class Waiting {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Waiting() {}

    /** One row's reads, and the verdict they support. */
    public record Item(
            ReviewLedger.Row row,
            String state,
            String title,
            String head,
            String lastBy,
            String lastAt,
            boolean merged,
            boolean pushed,
            boolean onYou,
            com.osscli.bench.BenchLedger.Row bench) {

        /**
         * What the runner found, in the words every rendering uses. Empty when it was never asked.
         *
         * <p>Read from the ledger rather than run here: this method is called for every recorded
         * review at once, and starting a build per row would turn a seven-second list into an
         * afternoon.
         */
        public String benchSaid() {
            return bench == null ? "" : bench.summary();
        }

        /**
         * Why it is where it is, in the words both renderings use.
         *
         * <p>Shared rather than duplicated: a page that said "pushed" where the terminal said
         * "reply:someone" would be two answers to one question, and the reader has no way to tell
         * which of them read the ledger correctly.
         */
        public String why(String me) {
            if (merged) {
                return "merged";
            }
            boolean somebodyElseSpoke = !lastBy.isEmpty() && !lastBy.equals(me);
            if (pushed && somebodyElseSpoke) {
                return "pushed + replied";
            }
            if (pushed) {
                return "pushed since your review";
            }
            return lastBy.isEmpty() ? "-" : "reply:" + lastBy;
        }
    }

    /** Both lists, and what could not be read. */
    public record Result(List<Item> onYou, List<Item> onThem, int checked, int unreachable) {
        public boolean nothingRecorded() {
            return checked == 0 && onYou.isEmpty() && onThem.isEmpty();
        }
    }

    /** Told how far along the reads are, so a caller can show it or ignore it. */
    public interface Progress {
        void step(int done, int total, String what);

        Progress SILENT = (done, total, what) -> {};
    }

    /** The account whose word does not count as somebody else's. */
    public static String me() {
        try {
            String u = SqliteStorage.loadConfig("github.username");
            return u == null ? "" : u.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Read every recorded review and sort them by whose move it is.
     *
     * <p>Three GitHub calls per row, six rows at a time. The progress callback counts rows in
     * ledger order rather than completion order, so "4 of 17" names the fourth row rather than
     * whichever request happened to finish fourth.
     */
    public static Result read(String repoFilter, String me, Progress progress) {
        List<ReviewLedger.Row> rows = ReviewLedger.read();
        List<ReviewLedger.Row> wanted = new ArrayList<>();
        for (ReviewLedger.Row r : rows) {
            if (repoFilter == null || repoFilter.isBlank() || r.repo.equalsIgnoreCase(repoFilter.trim())) {
                wanted.add(r);
            }
        }
        if (wanted.isEmpty()) {
            return new Result(List.of(), List.of(), 0, 0);
        }

        List<Item> fetched = com.osscli.util.Parallel.map(
                wanted,
                r -> read(r, me),
                done -> progress.step(done, wanted.size(), wanted.get(done - 1).repo + "#" + wanted.get(done - 1).pr));

        List<Item> yours = new ArrayList<>();
        List<Item> theirs = new ArrayList<>();
        int unreachable = 0;
        for (Item it : fetched) {
            if (it == null) {
                unreachable++;
                continue;
            }
            (it.onYou() ? yours : theirs).add(it);
        }
        yours.sort(Comparator.comparing((Item i) -> i.lastAt()).reversed());
        theirs.sort(Comparator.comparing((Item i) -> i.lastAt()).reversed());
        return new Result(yours, theirs, wanted.size(), unreachable);
    }

    /**
     * One row's reads. Null when the pull request cannot be read.
     *
     * <p>Called from several threads at once, which is safe because everything it touches is either
     * a parameter or created here — {@link #api} keeps no state of its own, and {@code me} is
     * resolved once before any of this starts.
     */
    private static Item read(ReviewLedger.Row r, String me) {
        JsonNode pull = api("/repos/" + r.repo + "/pulls/" + r.pr);
        if (pull == null) {
            return null;
        }
        String state = pull.path("state").asText("?");
        boolean merged = pull.path("merged_at").asText("").length() > 0;
        String head = pull.path("head").path("sha").asText("");
        String title = pull.path("title").asText("");
        boolean pushed = !head.isEmpty() && !head.equals(r.head);

        Said last = lastWord(r.repo, r.pr);
        String lastBy = last == null ? "" : last.by;
        String lastAt = last == null ? "" : last.at;

        boolean onYou =
                !merged && "open".equalsIgnoreCase(state) && (pushed || (!lastBy.isEmpty() && !lastBy.equals(me)));
        return new Item(
                r,
                state,
                title,
                head,
                lastBy,
                lastAt,
                merged,
                pushed,
                onYou,
                // Cheap: one read of a small file, already in the page cache by the second row.
                com.osscli.bench.BenchLedger.headline(r.repo, r.pr));
    }

    private static JsonNode api(String path) {
        try {
            String json = new GitHubClient().getJson(path);
            return (json == null || json.isBlank()) ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Said lastWord(String repoName, int pr) {
        List<Said> all = new ArrayList<>();
        collect(all, api("/repos/" + repoName + "/issues/" + pr + "/comments?per_page=100"), "created_at");
        collect(all, api("/repos/" + repoName + "/pulls/" + pr + "/reviews?per_page=100"), "submitted_at");
        all.removeIf(s -> s.at.isEmpty());
        all.sort(Comparator.comparing(s -> s.at));
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static void collect(List<Said> into, JsonNode arr, String timeField) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode n : arr) {
            Said s = new Said();
            s.at = n.path(timeField).asText("");
            s.by = n.path("user").path("login").asText("");
            into.add(s);
        }
    }

    private static final class Said {
        String at = "";
        String by = "";
    }
}
