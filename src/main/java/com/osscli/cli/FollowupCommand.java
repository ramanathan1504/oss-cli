package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.AppPaths;
import com.osscli.github.GitHubClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
 */
@Command(
        name = "followup",
        mixinStandardHelpOptions = true,
        description = "What moved on a reviewed pull request since you reviewed it")
public class FollowupCommand implements Callable<Integer> {

    /** Kept beside the database, not inside any clone: it outlives every checkout it describes. */
    private static final Path LEDGER = AppPaths.BASE_DIR.resolve("reviews").resolve("ledger.tsv");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", description = "One pull request, reported in full")
    Integer only;

    @Option(names = "--repo", description = "owner/name (default: the repo already on that row)")
    String repo;

    @Option(names = "--changed", description = "Only the ones that moved")
    boolean changed;

    @Option(names = "--record", description = "Record a PR as reviewed at its current head")
    Integer record;

    @Option(names = "--verdict", description = "With --record: take | changes | blocked | routine")
    String verdict = "none";

    @Option(names = "--note", description = "With --record: one line, for you, later")
    String note = "";

    @Override
    public Integer call() {
        try {
            if (record != null) {
                return doRecord(record);
            }
            List<Row> rows = read();
            if (rows.isEmpty()) {
                System.out.println("Nothing recorded yet.");
                System.out.println();
                System.out.println("  oss followup --record <pr> --repo owner/name --verdict take");
                return 0;
            }
            for (Row r : rows) {
                if (only != null && r.pr != only) {
                    continue;
                }
                report(r);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
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
            System.err.println("error  could not read " + target + "#" + pr);
            return 1;
        }
        List<Row> rows = read();
        rows.removeIf(r -> r.pr == pr && r.repo.equalsIgnoreCase(target));

        Row row = new Row();
        row.repo = target;
        row.pr = pr;
        row.verdict = verdict;
        // A full UTC timestamp, not a date. This is compared against comment timestamps, and
        // "2026-08-09T15:18:04Z" > "2026-08-09" lexically -- so a date alone could never clear a
        // badge raised by activity earlier the same day, and the row would look permanently stuck.
        row.reviewed = Instant.now().toString().replaceAll("\\.\\d+", "");
        row.head = pull.path("head").path("sha").asText("");
        row.author = pull.path("user").path("login").asText("?");
        row.note = note;
        rows.add(row);
        write(rows);

        System.out.printf("  recorded %s#%d at %s%n", target, pr, shortSha(row.head));
        System.out.println("  Only do this after actually reading it at that head.");
        return 0;
    }

    // ------------------------------------------------------------------ report ---

    private void report(Row r) throws Exception {
        JsonNode pull = fetch(r.repo, r.pr);
        if (pull == null) {
            System.out.printf("  %-28s #%-6d %s%n", r.repo, r.pr, "unreachable");
            return;
        }
        String head = pull.path("head").path("sha").asText("");
        String state = pull.path("state").asText("?");
        boolean merged = !pull.path("merged_at").isNull() && pull.path("merged_at").asText("").length() > 0;

        List<String> moved = new ArrayList<>();
        if (!head.isEmpty() && !head.equals(r.head)) {
            moved.add("pushed");
        }
        if (merged) {
            moved.add("merged");
        } else if (!"open".equalsIgnoreCase(state)) {
            moved.add(state.toLowerCase());
        }

        if (changed && moved.isEmpty()) {
            return;
        }

        if (only == null) {
            System.out.printf(
                    "  %-28s #%-6d %-14s %-12s %s%n",
                    r.repo, r.pr, r.verdict, r.author, moved.isEmpty() ? "—" : String.join(",", moved));
            return;
        }

        System.out.printf("%n  %s #%d  %s  (%s)%n", r.repo, r.pr, state, r.author);
        System.out.printf("  verdict     %s%s%n", r.verdict, r.note.isEmpty() ? "" : "   " + r.note);
        System.out.printf("  reviewed    %s at %s%n", r.reviewed, shortSha(r.head));
        System.out.printf("  head now    %s%s%n", shortSha(head), moved.contains("pushed") ? "   <- author pushed" : "");
        if (!moved.isEmpty()) {
            System.out.println();
            System.out.println("  Re-read before trusting the verdict above, then:");
            System.out.printf("    oss followup --record %d --repo %s%n", r.pr, r.repo);
        }
    }

    // ------------------------------------------------------------------ github ---

    private JsonNode fetch(String repoName, int pr) {
        try {
            String json = new GitHubClient().getJson("/repos/" + repoName + "/pulls/" + pr);
            return (json == null || json.isBlank()) ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveRepo(int pr) {
        if (repo != null && !repo.isBlank()) {
            return repo.trim();
        }
        // Falling back to the row means `--record` after a re-read needs only the number, which is
        // the common case and the one worth making short.
        return read().stream().filter(r -> r.pr == pr).map(r -> r.repo).findFirst().orElse(null);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? (sha == null ? "" : sha) : sha.substring(0, 8);
    }

    // ------------------------------------------------------------------ ledger ---

    /** One reviewed pull request, as it was when it was reviewed. */
    private static final class Row {
        String repo = "";
        int pr;
        String verdict = "none";
        String reviewed = "";
        String head = "";
        String author = "";
        String note = "";
    }

    private List<Row> read() {
        List<Row> rows = new ArrayList<>();
        if (!Files.isRegularFile(LEDGER)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(LEDGER)) {
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
                } catch (NumberFormatException e) {
                    continue; // a malformed row is skipped, not fatal: the rest is still useful
                }
                r.verdict = f[2];
                r.reviewed = f[3];
                r.head = f[4];
                r.author = f[5];
                r.note = f.length > 6 ? f[6] : "";
                rows.add(r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + LEDGER, e);
        }
        return rows;
    }

    private void write(List<Row> rows) {
        try {
            Files.createDirectories(LEDGER.getParent());
            StringBuilder sb = new StringBuilder("# repo\tpr\tverdict\treviewed\thead_at_review\tauthor\tnote\n");
            for (Row r : rows) {
                sb.append(String.join("\t", r.repo, String.valueOf(r.pr), r.verdict, r.reviewed, r.head, r.author,
                                r.note.replace('\t', ' ')))
                        .append('\n');
            }
            Path tmp = LEDGER.resolveSibling(LEDGER.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            // Write-then-move: an interrupted write must not leave a ledger that every later run
            // fails to parse, because the ledger is the only thing that cannot be re-derived.
            Files.move(tmp, LEDGER, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + LEDGER, e);
        }
    }
}
