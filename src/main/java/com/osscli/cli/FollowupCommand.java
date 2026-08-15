package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.review.ReviewLedger;
import com.osscli.storage.SqliteStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * What moved on a pull request since you reviewed it.
 *
 * <p>Reading a pull request is a snapshot: it says what the thing looks like now. It cannot say
 * whether the author pushed after you commented, whether a maintainer replied, or whether what you
 * asked for was done — because it has nothing to compare against. This keeps the comparison.
 *
 * <p>It lives in the core rather than in a runner, and that placement is the point. This asks
 * nothing of a clone, a build or a JVM; it is facts and a record, which is what the core is for. It
 * previously sat inside a Log4j bench, where it worked against any repository but could only be
 * reached by attaching that bench — a general capability held hostage by a specific one.
 *
 * <p>The ledger therefore carries the repository on every row. Without it, "PR 4234" means nothing
 * once you follow two projects, and the file quietly becomes single-project again.
 *
 * <p>Read-only against GitHub throughout. {@code --record} writes the ledger and {@code --write}
 * appends to a review file, both under {@code ~/.oss-cli/reviews/}. Nothing here posts anywhere.
 */
@Command(
        name = "followup",
        mixinStandardHelpOptions = true,
        description = "What moved on a reviewed pull request since you reviewed it")
public class FollowupCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Source files a review is likely to name. Used only to say where to look, never to judge. */
    private static final Pattern NAMED_FILE =
            Pattern.compile("[A-Za-z0-9_]+\\.(?:java|xml|adoc|properties|json|yaml|yml|kt|py|ts)");

    /** A single patch can be a megabyte. Past a point it stops being readable and starts being weight. */
    private static final int PATCH_LIMIT = 6000;

    @Parameters(index = "0", arity = "0..1", description = "One pull request, reported in full")
    Integer only;

    @Option(names = "--repo", description = "owner/name (default: the repo already on that row)")
    String repo;

    @Option(names = "--changed", description = "Only the ones that moved")
    boolean changed;

    @Option(names = "--mine", description = "Only where the last word is not yours")
    boolean mine;

    // --sync is the name this had in the bench it came from. Kept as an alias because it is in
    // muscle memory and in older notes; both spellings do exactly the same thing.
    @Option(
            names = {"--record", "--sync"},
            description = "Record a PR as reviewed at its current head")
    Integer record;

    @Option(names = "--verdict", description = "With --record: take | changes | blocked | routine")
    String verdict = "none";

    @Option(names = "--note", description = "With --record: one line, for you, later")
    String note = "";

    @Option(names = "--since", description = "What the author pushed since you reviewed it")
    Integer since;

    @Option(names = "--write", description = "With --since: append the report to the review file")
    boolean write;

    @Option(names = "--comment", description = "Print just the paste-ready block of a review, to pipe")
    Integer comment;

    @Override
    public Integer call() {
        try {
            if (write && since == null) {
                System.err.println("error  --write only means something with --since <pr>");
                return 1;
            }
            if (comment != null) {
                return pasteReady(comment);
            }
            if (since != null) {
                return sinceReport(since);
            }
            if (record != null) {
                return doRecord(record);
            }
            List<ReviewLedger.Row> rows = ReviewLedger.read();
            if (rows.isEmpty()) {
                System.out.println("Nothing recorded yet.");
                System.out.println();
                System.out.println("  oss followup --record <pr> --repo owner/name --verdict take");
                return 0;
            }
            for (ReviewLedger.Row r : rows) {
                if (only != null && r.pr != only) {
                    continue;
                }
                report(r);
            }
            if (com.osscli.github.Reachability.seen()) {
                // Every row printed the bare word "unreachable", which reads as a fact about those
                // pull requests. Said once here, it is a fact about this machine instead.
                System.out.println();
                System.out.println("  none of these could be read: " + com.osscli.github.Reachability.whyUnreadable());
                System.out.println("  What you already synced still answers: oss search, oss inspect, oss prompt.");
            }
            if (only == null) {
                System.out.println();
                System.out.println("  oss followup <n>          one pull request in full");
                System.out.println("  oss followup --changed    only what moved");
                System.out.println("  oss followup --since <n>  what the author pushed since you reviewed");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + com.osscli.github.Reachability.describe(e));
            return 1;
        }
    }

    // ------------------------------------------------------------------ record ---

    private Integer doRecord(int pr) throws Exception {
        String target = resolveRepo(pr);
        if (target == null) {
            System.err.println("error  --repo owner/name is needed the first time a PR is recorded");
            return 1;
        }
        JsonNode pull = fetch(target, pr);
        if (pull == null) {
            System.err.println("error  " + whyNot(target, pr));
            return 1;
        }
        List<ReviewLedger.Row> rows = ReviewLedger.read();
        ReviewLedger.Row previous = rows.stream()
                .filter(r -> r.pr == pr && r.repo.equalsIgnoreCase(target))
                .findFirst()
                .orElse(null);
        rows.removeIf(r -> r.pr == pr && r.repo.equalsIgnoreCase(target));

        ReviewLedger.Row row = new ReviewLedger.Row();
        row.repo = target;
        row.pr = pr;
        // Re-recording an already-reviewed PR is the common case, and it should not silently discard
        // the verdict and note that were written the first time. Only an explicitly given value wins.
        row.verdict = !"none".equals(verdict) || previous == null ? verdict : previous.verdict;
        row.note = !note.isEmpty() || previous == null ? note : previous.note;
        row.posted = previous == null ? "no" : previous.posted;
        // A full UTC timestamp, not a date. This is compared against comment timestamps, and
        // "2026-08-09T15:18:04Z" > "2026-08-09" lexically -- so a date alone could never clear a
        // badge raised by activity earlier the same day, and the row would look permanently stuck.
        row.reviewed = Instant.now().toString().replaceAll("\\.\\d+", "");
        row.head = pull.path("head").path("sha").asText("");
        row.author = pull.path("user").path("login").asText("?");
        rows.add(row);
        ReviewLedger.write(rows);

        System.out.printf("  recorded %s#%d at %s%n", target, pr, shortSha(row.head));
        System.out.println("  Only do this after actually reading it at that head.");
        return 0;
    }

    // ------------------------------------------------------------------ report ---

    private void report(ReviewLedger.Row r) throws Exception {
        JsonNode pull = fetch(r.repo, r.pr);
        if (pull == null) {
            System.out.printf("  %-28s #%-6d %s%n", r.repo, r.pr, "unreachable");
            return;
        }
        String head = pull.path("head").path("sha").asText("");
        String state = pull.path("state").asText("?");
        boolean merged = !pull.path("merged_at").isNull()
                && pull.path("merged_at").asText("").length() > 0;

        Said last = lastWord(r.repo, r.pr);

        List<String> moved = new ArrayList<>();
        if (!head.isEmpty() && !head.equals(r.head)) {
            moved.add("pushed");
        }
        if (merged) {
            moved.add("merged");
        } else if (!"open".equalsIgnoreCase(state)) {
            moved.add(state.toLowerCase());
        }
        String me = me();
        if (last != null && !last.by.equals(me) && last.at.compareTo(r.reviewed) > 0) {
            moved.add("reply:" + last.by);
        }

        if (changed && moved.isEmpty()) {
            return;
        }
        // --mine asks "where is the ball in my court": the last thing said was not said by me.
        if (mine && (last == null || last.by.equals(me))) {
            return;
        }

        if (only == null) {
            System.out.printf(
                    "  %-28s #%-6d %-14s %-12s %s%n",
                    r.repo, r.pr, r.verdict, r.author, moved.isEmpty() ? "—" : String.join(",", moved));
            return;
        }

        System.out.printf("%n  %s #%d  %s  (%s)%n", r.repo, r.pr, state, r.author);
        System.out.printf("  verdict     %s%s%n", r.verdict, "no".equals(r.posted) ? "   (comment not posted)" : "");
        if (!r.note.isEmpty()) {
            System.out.printf("  note        %s%n", r.note);
        }
        System.out.printf("  reviewed    %s at %s%n", r.reviewed, shortSha(r.head));
        System.out.printf(
                "  head now    %s%s%n", shortSha(head), moved.contains("pushed") ? "   <- author pushed" : "");
        if (last == null) {
            System.out.println("  last word   no comments or reviews yet");
        } else {
            System.out.printf("  last word   %s by %s at %s%n", last.kind, last.by, last.at);
        }
        Path rf = reviewFile(r.pr);
        if (rf != null) {
            System.out.printf("  review      %s%n", rf);
        }
        if (moved.contains("pushed")) {
            System.out.println();
            System.out.println("  Re-read before trusting the verdict above.");
            System.out.printf("    oss followup --since %d            # what landed since%n", r.pr);
            System.out.printf("    oss followup --since %d --write    # ... into the review file%n", r.pr);
            System.out.printf("    oss followup --record %d           # once you have re-read it%n", r.pr);
        }
    }

    // ------------------------------------------------------------------- since ---

    /**
     * What the author pushed after the review.
     *
     * <p>The obvious implementation is wrong, and wrong in a way that looks right. Comparing the
     * reviewed head with the current head through the compare API attributes every commit the author
     * merged in from the base branch to the author. Measured once on a real pull request: comparing
     * two of its own commits reported 20 commits and 89 files, nearly all of them dependency bumps
     * merged in from the base. That is the opposite of the signal wanted.
     *
     * <p>So commits come from the pull request's own commit list, which excludes base commits, and
     * files come from each commit individually with merges skipped — GitHub diffs a merge against its
     * first parent, so a merge of the base branch reports the entire base branch as its file list.
     */
    private Integer sinceReport(int pr) throws Exception {
        ReviewLedger.Row r = ReviewLedger.read().stream()
                .filter(row -> row.pr == pr && (repo == null || row.repo.equalsIgnoreCase(repo.trim())))
                .findFirst()
                .orElse(null);
        if (r == null) {
            System.err.println("error  PR " + pr + " is not in the ledger");
            return 1;
        }
        if (r.head.isEmpty()) {
            System.err.println("error  PR " + pr + " has no head recorded; nothing to diff against");
            return 1;
        }

        JsonNode pull = fetch(r.repo, pr);
        if (pull == null) {
            System.err.println("error  could not read " + r.repo + "#" + pr);
            return 1;
        }
        String head = pull.path("head").path("sha").asText("");
        String state = pull.path("state").asText("?");
        String base = pull.path("base").path("ref").asText("?");
        String title = pull.path("title").asText("");

        if (head.equals(r.head)) {
            System.out.printf("%n  %s #%d  %s  (%s)%n", r.repo, pr, state, r.author);
            System.out.printf(
                    "  head is still %s — nothing pushed since the review on %s%n", shortSha(r.head), r.reviewed);
            return 0;
        }

        List<Commit> all = commits(r.repo, pr);
        // If the reviewed head is still in the list, everything after it is new. If it is not, the
        // branch was rebased or force-pushed and that history is gone -- fall back to the review
        // timestamp and say so, rather than reporting nothing.
        boolean rebased = all.stream().noneMatch(c -> c.sha.equals(r.head));
        List<Commit> fresh = new ArrayList<>();
        if (rebased) {
            for (Commit c : all) {
                if (c.at.compareTo(r.reviewed) > 0) {
                    fresh.add(c);
                }
            }
        } else {
            boolean seen = false;
            for (Commit c : all) {
                if (seen) {
                    fresh.add(c);
                }
                if (c.sha.equals(r.head)) {
                    seen = true;
                }
            }
        }

        // Files, per commit, merges excluded. Ordered by size of change: the biggest hunk is the one
        // most likely to have changed the answer.
        Map<String, int[]> stats = new LinkedHashMap<>();
        Map<String, JsonNode> detail = new LinkedHashMap<>();
        int merges = 0;
        for (Commit c : fresh) {
            if (c.merge) {
                merges++;
                continue;
            }
            JsonNode full = api("/repos/" + r.repo + "/commits/" + c.sha);
            if (full == null) {
                continue;
            }
            detail.put(c.sha, full);
            for (JsonNode f : full.path("files")) {
                int[] ad = stats.computeIfAbsent(f.path("filename").asText(""), k -> new int[2]);
                ad[0] += f.path("additions").asInt();
                ad[1] += f.path("deletions").asInt();
            }
        }
        List<Map.Entry<String, int[]>> ranked = new ArrayList<>(stats.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -(e.getValue()[0] + e.getValue()[1])));

        Path rf = reviewFile(pr);
        Set<String> named = namedFiles(rf);
        List<Said> said = saidSince(r.repo, pr, r.reviewed);

        printSince(r, pr, state, title, base, head, rebased, fresh, ranked, named, rf, said, merges);

        if (!write) {
            System.out.println();
            System.out.printf("  oss followup --since %d --write    append this to the review file%n", pr);
            System.out.printf("  oss followup --record %d           once you have re-read it%n", pr);
            return 0;
        }
        return appendToReview(r, pr, rf, base, head, rebased, fresh, ranked, named, said, detail, merges);
    }

    private void printSince(
            ReviewLedger.Row r,
            int pr,
            String state,
            String title,
            String base,
            String head,
            boolean rebased,
            List<Commit> fresh,
            List<Map.Entry<String, int[]>> ranked,
            Set<String> named,
            Path rf,
            List<Said> said,
            int merges) {
        System.out.printf("%n  %s #%d  %s  (%s)%n", r.repo, pr, state, r.author);
        System.out.printf("  %s%n", title);
        System.out.printf("  verdict     %s  %s%n", r.verdict, r.note);
        System.out.printf("  reviewed    %s at %s  ->  head now %s%n", r.reviewed, shortSha(r.head), shortSha(head));
        if (rebased) {
            System.out.printf(
                    "  rebased/force-pushed — %s is no longer on the branch; listing by date instead%n",
                    shortSha(r.head));
        }

        if (fresh.isEmpty()) {
            System.out.println(
                    "  head moved but no commit is newer than the review — likely a rebase of the same work");
        } else {
            System.out.printf(
                    "%n  %d new commit%s%s%n",
                    fresh.size(),
                    fresh.size() == 1 ? "" : "s",
                    merges > 0 ? String.format(" (%d a merge of %s — files not attributed)", merges, base) : "");
            for (Commit c : fresh) {
                System.out.printf(
                        "    %s  %-16s %s%s%n", shortSha(c.sha), c.by, c.subject, c.merge ? "   [merge]" : "");
            }
        }

        if (!ranked.isEmpty()) {
            System.out.println();
            System.out.println("  files the author touched");
            for (Map.Entry<String, int[]> e : ranked) {
                System.out.printf("    +%-5d -%-5d %s%n", e.getValue()[0], e.getValue()[1], e.getKey());
            }
        } else if (!fresh.isEmpty()) {
            System.out.println("  no file changes outside merges");
        }

        // Purely mechanical: the source files the review names, crossed with the files these commits
        // touch. Touched is not the same as addressed, and the output says so -- deciding that is the
        // re-read this is meant to prompt.
        if (!named.isEmpty()) {
            System.out.printf("%n  files named in the review  (%s)%n", rf);
            for (String n : named) {
                System.out.printf("    %s %s%n", touched(ranked, n) ? "* touched  " : "o untouched", n);
            }
            System.out.println("  touched != addressed — read the hunks before changing the verdict");
        }

        if (!said.isEmpty()) {
            System.out.println();
            System.out.println("  said since the review");
            for (Said s : said) {
                System.out.printf("    %s  %s %s%n", s.at.substring(0, Math.min(10, s.at.length())), s.by, s.kind);
                if (!s.body.isEmpty()) {
                    System.out.printf("      %s%n", s.body);
                }
            }
        }
    }

    // -------------------------------------------------------------------- write ---

    private Integer appendToReview(
            ReviewLedger.Row r,
            int pr,
            Path rf,
            String base,
            String head,
            boolean rebased,
            List<Commit> fresh,
            List<Map.Entry<String, int[]>> ranked,
            Set<String> named,
            List<Said> said,
            Map<String, JsonNode> detail,
            int merges)
            throws IOException {
        if (rf == null) {
            System.err.println(
                    "error  no review file for PR " + pr + " in " + ReviewLedger.DIR + " — nothing to append to");
            return 1;
        }
        String existing = Files.readString(rf);
        // Idempotent by head: running it twice must not append the same section twice, and the head
        // is what the section is actually about.
        if (existing.contains("<!-- since:" + head + " -->")) {
            System.out.printf("%n  %s already records head %s — not appending twice%n", rf, shortSha(head));
            return 0;
        }

        StringBuilder b = new StringBuilder();
        b.append("\n---\n\n");
        b.append("<!-- since:").append(head).append(" -->\n");
        b.append("## Since the review — ").append(LocalDate.now(ZoneOffset.UTC)).append("\n\n");
        b.append("Appended by `oss followup --since ").append(pr).append(" --write`. The review above\n");
        b.append("was written at `").append(shortSha(r.head)).append("` on ").append(r.reviewed);
        b.append("; the head is now `").append(shortSha(head)).append("`.\n\n");
        if (rebased) {
            b.append("> **Rebased or force-pushed.** `").append(shortSha(r.head));
            b.append("` is no longer on the branch, so this\n> lists commits by date rather than by position.");
            b.append(" The old history is gone.\n\n");
        }

        if (fresh.isEmpty()) {
            b.append(
                    "The head moved, but no commit is newer than the review — most likely a\nrebase of the same work.\n");
        } else {
            b.append("### ").append(fresh.size()).append(" new commit").append(fresh.size() == 1 ? "" : "s");
            b.append("\n\n| commit | author | subject |\n|---|---|---|\n");
            for (Commit c : fresh) {
                b.append("| `")
                        .append(shortSha(c.sha))
                        .append("` | ")
                        .append(c.by)
                        .append(" | ");
                b.append(c.subject.replace("|", "\\|"));
                if (c.merge) {
                    b.append(" _(merge of `").append(base).append("`)_");
                }
                b.append(" |\n");
            }
            if (merges > 0) {
                b.append("\n")
                        .append(merges)
                        .append(" of these is a merge of `")
                        .append(base);
                b.append("`. GitHub diffs a merge against its first\nparent, so its file list is the whole base");
                b.append(" branch — it is excluded from the\ncounts below, which are the author's own edits only.\n");
            }
        }

        if (!ranked.isEmpty()) {
            b.append("\n### Files the author touched\n\n| file | + | − |\n|---|---:|---:|\n");
            for (Map.Entry<String, int[]> e : ranked) {
                b.append("| `").append(e.getKey()).append("` | ").append(e.getValue()[0]);
                b.append(" | ").append(e.getValue()[1]).append(" |\n");
            }
        }

        if (!named.isEmpty()) {
            b.append("\n### Against what the review named\n\n");
            b.append("Mechanical: files this review mentions, crossed with the files these\n");
            b.append("commits touch. **Touched is not addressed** — it says where to look.\n\n");
            b.append("| file named in the review | in these commits |\n|---|---|\n");
            for (String n : named) {
                b.append("| `")
                        .append(n)
                        .append("` | ")
                        .append(touched(ranked, n) ? "**touched**" : "—")
                        .append(" |\n");
            }
        }

        if (!said.isEmpty()) {
            b.append("\n### Said since\n\n");
            for (Said s : said) {
                b.append("- **")
                        .append(s.by)
                        .append("** — ")
                        .append(s.kind)
                        .append(", ")
                        .append(s.at)
                        .append("\n");
                if (!s.body.isEmpty()) {
                    b.append("  > ").append(s.body).append("\n");
                }
            }
        }

        // The hunks last: they are the longest part, and everything above is the index into them.
        if (!detail.isEmpty()) {
            b.append("\n### The hunks\n\n");
            for (Commit c : fresh) {
                JsonNode full = detail.get(c.sha);
                if (full == null) {
                    continue;
                }
                b.append("#### `")
                        .append(shortSha(c.sha))
                        .append("` — ")
                        .append(c.subject)
                        .append("\n\n");
                for (JsonNode f : full.path("files")) {
                    b.append("`").append(f.path("filename").asText()).append("` (+");
                    b.append(f.path("additions").asInt())
                            .append(" −")
                            .append(f.path("deletions").asInt());
                    b.append(")\n\n");
                    JsonNode patch = f.path("patch");
                    if (patch.isMissingNode() || patch.isNull()) {
                        b.append("_no textual patch (binary, renamed, or too large)_\n\n");
                        continue;
                    }
                    String text = patch.asText("");
                    b.append("```diff\n")
                            .append(text, 0, Math.min(text.length(), PATCH_LIMIT))
                            .append("\n```\n");
                    if (text.length() > PATCH_LIMIT) {
                        b.append("\n_patch truncated at ").append(PATCH_LIMIT).append(" characters._\n");
                    }
                    b.append("\n");
                }
            }
        }

        Files.writeString(rf, b.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.printf("%n  appended to %s%n", rf);
        System.out.printf("  Review it, then: oss followup --record %d%n", pr);
        return 0;
    }

    // ------------------------------------------------------------------ comment ---

    /**
     * Only the block addressed to the author.
     *
     * <p>A review file is mostly notes to yourself — provenance, what was checked, what is blocking.
     * Printing the whole file would send all of that to whoever you pipe it to. Only the block under
     * a "paste-ready comment" heading is written for the author, so that is what this prints.
     */
    private Integer pasteReady(int pr) throws IOException {
        Path rf = reviewFile(pr);
        if (rf == null) {
            System.err.println("error  no review file for PR " + pr + " in " + ReviewLedger.DIR);
            return 1;
        }
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String line : Files.readAllLines(rf)) {
            if (line.startsWith("## ")) {
                // A file may carry one block per pull request it covers; prefer the one naming this.
                boolean isBlock = line.toLowerCase().contains("paste-ready comment");
                in = isBlock && (line.contains("#" + pr) || !line.contains("for #"));
                continue;
            }
            if (in) {
                out.append(line).append('\n');
            }
        }
        if (out.toString().isBlank()) {
            System.err.println("error  no paste-ready block for PR " + pr + " in " + rf);
            return 1;
        }
        System.out.print(out);
        return 0;
    }

    // ------------------------------------------------------------------- github ---

    /**
     * Why the last {@link #api} call came back empty.
     *
     * <p>Null means "GitHub answered, and the answer was 404". Anything else is the reason it never
     * got an answer at all. Collapsing every failure to null made {@code --record} report
     * {@code could not read owner/name#4234} for a missing pull request, an expired token, a
     * rate limit and a pulled cable alike -- four different problems with four different remedies,
     * behind one sentence that suggests none of them.
     */
    private String lastFailure;

    private JsonNode api(String path) {
        lastFailure = null;
        try {
            String json = new GitHubClient().getJson(path);
            return (json == null || json.isBlank()) ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            lastFailure = com.osscli.github.Reachability.describe(e);
            return null;
        }
    }

    /** What to tell someone about a pull request that could not be read. */
    private String whyNot(String repoName, int pr) {
        return lastFailure != null ? lastFailure : repoName + "#" + pr + " does not exist, or this token cannot see it";
    }

    private JsonNode fetch(String repoName, int pr) {
        return api("/repos/" + repoName + "/pulls/" + pr);
    }

    private List<Commit> commits(String repoName, int pr) {
        List<Commit> out = new ArrayList<>();
        JsonNode arr = api("/repos/" + repoName + "/pulls/" + pr + "/commits?per_page=100");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode c : arr) {
            Commit k = new Commit();
            k.sha = c.path("sha").asText("");
            k.at = c.path("commit").path("committer").path("date").asText("");
            String login = c.path("author").path("login").asText("");
            k.by = login.isEmpty()
                    ? c.path("commit").path("author").path("name").asText("?")
                    : login;
            String msg = c.path("commit").path("message").asText("");
            int nl = msg.indexOf('\n');
            k.subject = nl < 0 ? msg : msg.substring(0, nl);
            k.merge = c.path("parents").size() > 1;
            out.add(k);
        }
        return out;
    }

    private List<Said> saidSince(String repoName, int pr, String cutoff) {
        List<Said> out = new ArrayList<>();
        JsonNode comments = api("/repos/" + repoName + "/issues/" + pr + "/comments?per_page=100");
        if (comments != null && comments.isArray()) {
            for (JsonNode c : comments) {
                out.add(said(
                        c.path("created_at").asText(""),
                        c.path("user").path("login").asText("?"),
                        "comment",
                        c));
            }
        }
        JsonNode reviews = api("/repos/" + repoName + "/pulls/" + pr + "/reviews?per_page=100");
        if (reviews != null && reviews.isArray()) {
            for (JsonNode c : reviews) {
                String kind = c.path("state").asText("review").toLowerCase();
                out.add(said(
                        c.path("submitted_at").asText(""),
                        c.path("user").path("login").asText("?"),
                        kind,
                        c));
            }
        }
        out.removeIf(s -> s.at.isEmpty() || (cutoff != null && !cutoff.isEmpty() && s.at.compareTo(cutoff) <= 0));
        out.sort(Comparator.comparing(s -> s.at));
        return out;
    }

    private Said lastWord(String repoName, int pr) {
        List<Said> all = saidSince(repoName, pr, "");
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static Said said(String at, String by, String kind, JsonNode node) {
        Said s = new Said();
        s.at = at;
        s.by = by;
        s.kind = kind;
        String body = node.path("body").asText("").replaceAll("[\r\n]+", " ");
        s.body = body.length() > 160 ? body.substring(0, 160) : body;
        return s;
    }

    private String me() {
        try {
            String u = SqliteStorage.loadConfig("github.username");
            return u == null ? "" : u.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveRepo(int pr) {
        if (repo != null && !repo.isBlank()) {
            return repo.trim();
        }
        // Falling back to the row means `--record` after a re-read needs only the number, which is
        // the common case and the one worth making short.
        return ReviewLedger.read().stream()
                .filter(r -> r.pr == pr)
                .map(r -> r.repo)
                .findFirst()
                .orElse(null);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? (sha == null ? "" : sha) : sha.substring(0, 8);
    }

    // ------------------------------------------------------------------ reviews ---

    /**
     * The review write-up for a pull request, if one was filed.
     *
     * <p>Matched on the number anywhere in the name rather than as a prefix: one write-up often
     * covers several related pull requests, and is named for all of them.
     */
    static Path reviewFile(int pr) {
        return ReviewLedger.writeUp(pr);
    }

    private static Set<String> namedFiles(Path reviewFile) throws IOException {
        Set<String> named = new LinkedHashSet<>();
        if (reviewFile == null) {
            return named;
        }
        Matcher m = NAMED_FILE.matcher(Files.readString(reviewFile));
        while (m.find()) {
            named.add(m.group());
        }
        return named;
    }

    private static boolean touched(List<Map.Entry<String, int[]>> ranked, String basename) {
        for (Map.Entry<String, int[]> e : ranked) {
            String f = e.getKey();
            if (f.equals(basename) || f.endsWith("/" + basename)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- types ---

    /** One commit on the pull request's own branch. */
    private static final class Commit {
        String sha = "";
        String at = "";
        String by = "?";
        String subject = "";
        boolean merge;
    }

    /** One comment or review, as said on the pull request. */
    private static final class Said {
        String at = "";
        String by = "?";
        String kind = "";
        String body = "";
    }
}
