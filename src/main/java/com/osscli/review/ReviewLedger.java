package com.osscli.review;

import com.osscli.AppPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What you reviewed, and the head it was at when you reviewed it.
 *
 * <p>One reader, shared, deliberately. Follow-up and the hub both answer questions about reviewed
 * pull requests, and when each parsed the file itself they were free to disagree about what a row
 * meant. That is exactly how this capability came to exist twice with different flags in the first
 * place; a second copy of the parsing would be the same mistake one layer down.
 *
 * <p>Kept beside the database rather than inside any clone: it outlives every checkout it describes,
 * and it carries the repository on every row so that "PR 4234" still means something once you
 * follow more than one project.
 */
public final class ReviewLedger {

    /** Reviews and their write-ups, together — the ledger indexes the markdown beside it. */
    public static final Path DIR = AppPaths.BASE_DIR.resolve("reviews");

    private static final Path FILE = DIR.resolve("ledger.tsv");

    private static final String HEADER = "# repo\tpr\tverdict\treviewed\thead_at_review\tauthor\tposted\tnote";

    private ReviewLedger() {}

    /** One reviewed pull request, as it was when it was reviewed. */
    public static final class Row {
        public String repo = "";
        public int pr;
        public String verdict = "none";
        public String reviewed = "";
        public String head = "";
        public String author = "";
        public String posted = "no";
        public String note = "";
    }

    public static List<Row> read() {
        List<Row> rows = new ArrayList<>();
        if (!Files.isRegularFile(FILE)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(FILE)) {
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
                r.posted = f.length > 6 ? f[6] : "no";
                r.note = f.length > 7 ? f[7] : "";
                rows.add(r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + FILE, e);
        }
        return rows;
    }

    public static void write(List<Row> rows) {
        try {
            Files.createDirectories(DIR);
            StringBuilder sb = new StringBuilder(HEADER).append('\n');
            for (Row r : rows) {
                sb.append(String.join(
                                "\t",
                                r.repo,
                                String.valueOf(r.pr),
                                r.verdict,
                                r.reviewed,
                                r.head,
                                r.author,
                                r.posted,
                                r.note.replace('\t', ' ')))
                        .append('\n');
            }
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString());
            // Write-then-move: an interrupted write must not leave a ledger that every later run
            // fails to parse, because the ledger is the only thing here that cannot be re-derived.
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + FILE, e);
        }
    }

    /**
     * The review write-up for a pull request, if one was filed.
     *
     * <p>Matched on the number anywhere in the name rather than as a prefix: one write-up often
     * covers several related pull requests, and is named for all of them.
     */
    public static Path writeUp(int pr) {
        if (!Files.isDirectory(DIR)) {
            return null;
        }
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> p.getFileName().toString().contains(String.valueOf(pr)))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
