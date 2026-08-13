package com.osscli.cli;

import com.osscli.model.Issue;
import com.osscli.model.RepoIssue;
import com.osscli.retrieval.Corpus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * What should I work on next — scored against what you have already done.
 *
 * <p>Every backlog tool ranks by the same public signals: reactions, labels, age, how many people
 * commented. Those say what is popular. None of them say what is <em>yours</em> — which of four
 * hundred open issues sits in the part of the codebase you have read, argued about and fixed before.
 *
 * <p>This scores the backlog against your own writing instead: the notes you filed and the pull
 * requests you reviewed. Reviewing something is the strongest signal available, because it is the
 * one place you demonstrably read the code rather than merely saved a link.
 *
 * <p>It needs no model. Term overlap against your own corpus is arithmetic, and it works on a
 * machine with nothing installed. A local model makes the matching better; its absence costs
 * quality, not the feature.
 */
@Command(
        name = "pick",
        mixinStandardHelpOptions = true,
        description = "What to work on next, scored against what you have already worked on")
public class PickCommand implements Callable<Integer> {

    /** Reviewing something means you read it. Filing a note means you thought it worth keeping. */
    private static final int WEIGHT_REVIEWED = 4;

    private static final int WEIGHT_NOTE = 1;

    @Option(names = "--repo", description = "Only this repository, as owner/name")
    String repo;

    @Option(names = "--limit", description = "How many to suggest (default 10)")
    int limit = 10;

    @Option(names = "--issues-only", description = "Skip pull requests")
    boolean issuesOnly;

    @Override
    public Integer call() {
        try {
            Corpus profile = Corpus.load(m -> System.err.println("  " + m));
            if (profile.size() == 0) {
                System.out.println("Nothing to score against yet.");
                System.out.println();
                System.out.println("  oss memory file <notes.md>          keep what you work out");
                System.out.println("  oss followup --record <pr> --repo … record what you review");
                System.out.println();
                System.out.println("  Both build the profile this ranks against. Reviews count for more,");
                System.out.println("  because reviewing something means you read it.");
                return 0;
            }

            List<RepoIssue> all = com.osscli.storage.SqliteStorage.loadAllIssues();
            if (all.isEmpty()) {
                System.out.println("No issues cached. Run: oss sync");
                return 0;
            }

            List<Scored> scored = new ArrayList<>();
            for (RepoIssue ri : all) {
                Issue i = ri.issue();
                if (!"open".equalsIgnoreCase(i.state())) {
                    continue;
                }
                if (issuesOnly && i.isPullRequest()) {
                    continue;
                }
                if (repo != null && !repo.isBlank() && !ri.repository().equalsIgnoreCase(repo.trim())) {
                    continue;
                }
                String text = (i.title() == null ? "" : i.title()) + " " + (i.body() == null ? "" : i.body());
                List<Corpus.Hit> hits = profile.search(text, 3);
                if (hits.isEmpty()) {
                    continue;
                }
                double s = hits.stream().mapToDouble(Corpus.Hit::score).sum();
                scored.add(new Scored(ri, s, hits));
            }

            if (scored.isEmpty()) {
                System.out.println("Nothing in the backlog overlaps what you have written about.");
                System.out.println("  That is a real answer: file a few notes, or widen with oss sync.");
                return 0;
            }

            scored.sort(Comparator.comparingDouble((Scored x) -> -x.score));
            System.out.printf(
                    "%n  Scored against %d thing(s) you have written or reviewed — %s%n%n",
                    profile.size(), profile.semantic() ? "by meaning" : "by shared terms");
            int n = 0;
            for (Scored x : scored) {
                if (n++ >= limit) {
                    break;
                }
                Issue i = x.ri.issue();
                System.out.printf(
                        "  %.2f  %-28s #%-6d %s%n", x.score, x.ri.repository(), i.number(), trim(i.title(), 58));
                // Naming what matched is the difference between a ranking you can act on and one you
                // have to take on faith. It is also how you notice when it matched on nothing useful.
                String why = x.hits.stream()
                        .map(Corpus.Hit::title)
                        .filter(t -> t != null && !t.isBlank())
                        .distinct()
                        .limit(2)
                        .reduce((a, b) -> a + " · " + b)
                        .orElse("");
                if (!why.isEmpty()) {
                    System.out.printf("        because you wrote: %s%n", trim(why, 66));
                }
            }
            System.out.println();
            System.out.println("  oss issue <n> --repo <owner/name>   read one");
            System.out.println("  oss backlog                         the whole backlog, by public signal");
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private record Scored(RepoIssue ri, double score, List<Corpus.Hit> hits) {}

    static {
        // Keeps Locale-sensitive formatting predictable in the table above.
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
    }
}
