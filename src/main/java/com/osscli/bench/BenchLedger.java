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
package com.osscli.bench;

import com.osscli.AppPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What the runner actually found, and which code it found it on.
 *
 * <p>The tool reads repositories through the GitHub API without a clone, which is what lets it
 * follow any project in any language — and is exactly why it cannot answer <em>does this actually
 * run</em>. A model cannot answer it either; asked, it produces a confident sentence about code it
 * never executed. The runner can answer it, and before this the answer lasted as long as the
 * terminal scrollback.
 *
 * <p>So a run is recorded against the pull request it was about, and every later reading — the
 * review, the terminal, the board — sees it before any model is consulted. That is the ordering
 * this ledger exists to make possible: a question the bench has already answered is not a question
 * worth paying a network round trip for.
 *
 * <p><b>It records what was run, not a proof.</b> The runner executes in whatever directory you are
 * in, and nothing here checks out the pull request. So both shas are kept — the head GitHub reports
 * for the pull request, and the local {@code HEAD} the run actually happened on — and every reading
 * says plainly which case it is. A green result from a tree that is not the change under review is
 * worse than no result, because it reads exactly like a good one.
 */
public final class BenchLedger {

    public static final Path FILE = AppPaths.BASE_DIR.resolve("bench-runs.tsv");

    private static final String HEADER = "# repo\tpr\tverb\texit\tpr_head\tran_at\tran_on\trunner";

    private BenchLedger() {}

    /** How much a recorded run is worth, which is entirely a question of which code it ran. */
    public enum Trust {
        /** The local HEAD was the pull request's head: this ran the change under review. */
        SAME_CODE,
        /** It ran, but on a different commit. The result is about some other tree. */
        OTHER_CODE,
        /** No local git, or no head reported. Neither confirmed nor denied. */
        UNKNOWN
    }

    /** One run of one verb, against one pull request. */
    public static final class Row {
        public String repo = "";
        public int pr;
        public String verb = "";
        public int exit;
        /** The head GitHub reported for the pull request when the run started. */
        public String prHead = "";
        /** When it ran, ISO-8601. */
        public String ranAt = "";
        /** The local git HEAD the run actually happened on. */
        public String ranOn = "";
        /** The extension that answered, or {@code built-in}. */
        public String runner = "built-in";

        public boolean passed() {
            return exit == 0;
        }

        /**
         * Whether this result is about the code under review.
         *
         * <p>Compared on whatever length both shas share, because GitHub reports a full forty
         * characters and a person reading {@code git rev-parse --short} has seven. Two shas that
         * agree on seven characters are the same commit for every practical purpose here, and
         * refusing to compare them would report OTHER_CODE for a run that was in fact correct.
         */
        public Trust trust() {
            if (prHead.isBlank() || ranOn.isBlank()) {
                return Trust.UNKNOWN;
            }
            int n = Math.min(prHead.length(), ranOn.length());
            if (n < 7) {
                return Trust.UNKNOWN;
            }
            return prHead.regionMatches(true, 0, ranOn, 0, n) ? Trust.SAME_CODE : Trust.OTHER_CODE;
        }

        /** One line, in the words every rendering of this uses. */
        public String summary() {
            String head = verb + (passed() ? " passed" : " failed (exit " + exit + ")");
            return switch (trust()) {
                case SAME_CODE -> head;
                case OTHER_CODE -> head + " — but on " + shortSha(ranOn) + ", not this change";
                case UNKNOWN -> head + " — which commit it ran on is not recorded";
            };
        }
    }

    /** The first seven characters, which is how a person refers to a commit. */
    public static String shortSha(String sha) {
        return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }

    public static List<Row> read() {
        return readFrom(FILE);
    }

    /**
     * Parse one ledger file.
     *
     * <p>Takes the path so the format can be tested without writing into the data directory an
     * installed release depends on. A test that had to point {@code OSS_CLI_HOME} at a temporary
     * directory before this class loaded would be a test nobody could run in isolation.
     */
    public static List<Row> readFrom(Path file) {
        List<Row> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length < 6) {
                    continue;
                }
                Row r = new Row();
                r.repo = f[0];
                try {
                    r.pr = Integer.parseInt(f[1].trim());
                    r.exit = Integer.parseInt(f[3].trim());
                } catch (NumberFormatException e) {
                    continue; // a malformed row is skipped, not fatal: the rest is still useful
                }
                r.verb = f[2];
                r.prHead = f[4];
                r.ranAt = f[5];
                r.ranOn = f.length > 6 ? f[6] : "";
                r.runner = f.length > 7 ? f[7] : "built-in";
                rows.add(r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return rows;
    }

    /**
     * Add one run.
     *
     * <p>Appends rather than replaces. {@code build} passing and {@code test} failing on the same
     * commit are two facts, and a ledger that kept only the newest would answer "does it work"
     * with whichever verb was typed last.
     */
    public static void record(Row row) {
        List<Row> rows = read();
        rows.add(row);
        write(rows);
    }

    public static void write(List<Row> rows) {
        writeTo(FILE, rows);
    }

    /** Render and store one ledger file. Paired with {@link #readFrom} so both can be tested. */
    public static void writeTo(Path file, List<Row> rows) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder(HEADER).append('\n');
            for (Row r : rows) {
                sb.append(String.join(
                                "\t",
                                r.repo,
                                String.valueOf(r.pr),
                                r.verb,
                                String.valueOf(r.exit),
                                r.prHead,
                                r.ranAt,
                                r.ranOn,
                                // The only free-text field, and it comes from a manifest somebody
                                // else wrote. A tab in it would shift every column after it by one
                                // and the name would come back truncated at the tab.
                                r.runner.replace('\t', ' ')))
                        .append('\n');
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            // Write-then-move, as the review ledger does: an interrupted write must not leave a
            // file every later run fails to parse.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    /**
     * Every run recorded for one pull request, newest first.
     *
     * <p>Repository match is case-insensitive because {@code owner/Name} and {@code owner/name} are
     * the same project to GitHub, and a ledger that disagreed would silently report "never run".
     */
    public static List<Row> forPr(String repo, int pr) {
        return forPr(read(), repo, pr);
    }

    /** The same selection over rows already in hand. */
    public static List<Row> forPr(List<Row> all, String repo, int pr) {
        List<Row> out = new ArrayList<>();
        for (Row r : all) {
            if (r.pr == pr && r.repo.equalsIgnoreCase(repo == null ? "" : repo.trim())) {
                out.add(r);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * The run worth showing for a pull request, or null.
     *
     * <p>A failure outranks a pass, and a run on the right code outranks both. "test passed" beside
     * "build failed" is not a summary, it is the half of the truth that flatters the change; and a
     * pass from the wrong tree must never displace a real result.
     */
    public static Row headline(String repo, int pr) {
        return headline(read(), repo, pr);
    }

    /** The same choice over rows already in hand. */
    public static Row headline(List<Row> all, String repo, int pr) {
        List<Row> rows = forPr(all, repo, pr);
        Row best = null;
        for (Row r : rows) {
            if (best == null || rank(r) > rank(best)) {
                best = r;
            }
        }
        return best;
    }

    private static int rank(Row r) {
        int code = r.trust() == Trust.SAME_CODE ? 4 : r.trust() == Trust.UNKNOWN ? 2 : 0;
        // A failure is the more useful of two results on equal footing: it is the one that changes
        // what you do next.
        return code + (r.passed() ? 0 : 1);
    }
}
