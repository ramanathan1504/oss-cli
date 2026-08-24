package com.osscli.cli;

import com.osscli.retrieval.Suggestions;
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
 * <p>The scoring itself is {@link Suggestions}; this is the terminal rendering of it, and the board
 * page is another rendering of the same call. It used to be one method, and the only way the page
 * could show this ranking was to run the command and paste its output into a browser — which turned
 * a score and a list of matched titles back into a column of spaces.
 */
@Command(
        name = "pick",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "What to work on next, scored against what you have already worked on")
public class PickCommand implements Callable<Integer> {

    @Option(
            names = {"-r", "--repo"},
            description = "Only this repository, as owner/name")
    String repo;

    @Option(names = "--limit", description = "How many to suggest (default 10)")
    int limit = 10;

    @Option(names = "--issues-only", description = "Skip pull requests")
    boolean issuesOnly;

    @Override
    public Integer call() {
        try {
            // Anything past a second says what it is doing. This said nothing for a hundred
            // seconds, which is indistinguishable from a hang -- and on the run that crashed, it
            // was indistinguishable from a hang right up until the JVM died. The page passes
            // Progress.SILENT for the same call, because a browser has no line to overwrite.
            com.osscli.ui.Live live = com.osscli.ui.Live.start("scoring the open backlog against your profile");
            Suggestions.Result r = Suggestions.read(repo, limit, issuesOnly, new Suggestions.Progress() {
                @Override
                public void step(int done, int total) {
                    live.step("scored " + done + " of " + total);
                }

                @Override
                public void note(String what) {
                    live.step(what);
                }
            });

            switch (r.why()) {
                case NO_PROFILE -> {
                    live.done("nothing to score against");
                    System.out.println("Nothing to score against yet.");
                    System.out.println();
                    System.out.println("  oss memory file <notes.md>          keep what you work out");
                    System.out.println("  oss followup --record <pr> --repo … record what you review");
                    System.out.println();
                    System.out.println("  Both build the profile this ranks against. Reviews count for more,");
                    System.out.println("  because reviewing something means you read it.");
                    return 0;
                }
                case NOTHING_SYNCED -> {
                    live.done("nothing cached");
                    System.out.println("No issues cached. Run: oss sync");
                    return 0;
                }
                case NO_OVERLAP -> {
                    live.done(r.candidates() + " scored");
                    System.out.println("Nothing in the backlog overlaps what you have written about.");
                    System.out.println("  That is a real answer: file a few notes, or widen with oss sync.");
                    return 0;
                }
                default -> {}
            }

            live.done(r.candidates() + " scored"
                    + (r.embeddedHere() > 0
                            ? ", " + r.embeddedHere() + " embedded here (the rest were already vectors)"
                            : ""));
            System.out.printf(
                    "%n  Scored against %d thing(s) you have written or reviewed — %s%n%n", r.profileSize(), r.how());
            for (Suggestions.Item i : r.items()) {
                System.out.printf("  %.2f  %-28s #%-6d %s%n", i.score(), i.repo(), i.number(), trim(i.title(), 58));
                // Naming what matched is the difference between a ranking you can act on and one you
                // have to take on faith. It is also how you notice when it matched on nothing useful.
                if (!i.because().isEmpty()) {
                    System.out.printf("        because you wrote: %s%n", trim(String.join(" · ", i.because()), 66));
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

    static {
        // Keeps Locale-sensitive formatting predictable in the table above.
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
    }
}
