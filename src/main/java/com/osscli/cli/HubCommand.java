package com.osscli.cli;

import com.osscli.review.Waiting;
import com.osscli.ui.Out;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * One question, answered on opening: is anyone waiting on me?
 *
 * <p>Following work in someone else's project fails in a quiet way. Nothing breaks and no
 * notification arrives; a pull request you asked for changes on simply gets them, and sits there,
 * and the person who did the work waits. The cost of that is paid by a contributor, not by you,
 * which is exactly why it goes unnoticed.
 *
 * <p>So this sorts everything you have reviewed into two buckets by whose move it is. The sorting
 * itself is {@link Waiting}, not this class: what is here is the terminal rendering of it, and the
 * board page is another rendering of the same call. It used to be one thing — the rule and the
 * {@code printf} in the same method — and the only way the page could show this list was to run the
 * command and paste the text into a browser, which is what it did.
 *
 * <p><b>It reads and does nothing else.</b> There is no send path here, no draft, no comment, and
 * no flag that could grow into one. Posting to a project you do not own is a decision a person
 * makes in their own words; a dashboard that could do it on your behalf is a dashboard that
 * eventually does.
 */
@Command(
        name = "hub",
        mixinStandardHelpOptions = true,
        description = "Is anyone waiting on you? Every project you follow, in one list")
public class HubCommand implements Callable<Integer> {

    @Option(
            names = {"-r", "--repo"},
            description = "Only this repository, as owner/name")
    String repo;

    @Option(names = "--all", description = "Include the ones where the ball is not in your court")
    boolean all;

    private String me = "";

    @Override
    public Integer call() {
        me = Waiting.me();

        // One API call per recorded review, and nothing said until all of them are back: 44 seconds
        // of blank terminal on a 17-row ledger, measured on the installed build. The rule this
        // repository states is that anything slower than a second reports what it is doing, and a
        // command that reads a network in a loop is the case the rule was written for. The page
        // passes Progress.SILENT for the same call, because a browser has no line to overwrite.
        com.osscli.ui.Live live = com.osscli.ui.Live.start("reading recorded review(s)");
        Waiting.Result result =
                Waiting.read(repo, me, (done, total, what) -> live.step(done + " of " + total + " — " + what));

        if (result.nothingRecorded()) {
            live.done("nothing recorded");
            // Two ways to read nothing, and they are not the same news. An empty ledger means you
            // have not started; a filter that matched none of it means you have, elsewhere.
            if (repo != null && !repo.isBlank()) {
                System.out.printf("Nothing recorded for %s — oss hub lists every repository.%n", repo.trim());
                return 0;
            }
            System.out.println("Nothing reviewed yet, so nobody is waiting on you.");
            System.out.println();
            System.out.println("  oss followup --record <pr> --repo owner/name --verdict take");
            return 0;
        }

        live.done(result.checked() + " read");
        System.out.println();
        print("Waiting on you", result.onYou(), true);
        if (all) {
            print("Waiting on them", result.onThem(), false);
        } else if (!result.onThem().isEmpty()) {
            Out.gap();
            Out.hint("oss hub --all", result.onThem().size() + " more, not waiting on you");
        }
        if (result.unreachable() > 0) {
            // The reason is asked for rather than assumed. With the wifi off this listed seventeen
            // pull requests as "private, deleted, or no token" -- three explanations, all wrong,
            // each of which sends the reader hunting for a problem they do not have.
            Out.note(result.unreachable() + " unreachable — " + com.osscli.github.Reachability.whyUnreadable());
        }
        return 0;
    }

    private void print(String heading, List<Waiting.Item> items, boolean urgent) {
        // Was SHOUTED IN CAPITALS, which is the oldest way of making a heading stand out and the
        // one that stops working the moment there are two of them. Out.section is dim and lower
        // case: the indentation under it is already saying "these belong to that".
        Out.section(heading);
        if (items.isEmpty()) {
            Out.none("nothing" + (urgent ? " — you are clear" : ""));
            return;
        }
        for (Waiting.Item i : items) {
            // The pull request is the thing you act on, so it is the thing that is coloured.
            Out.item(String.format(
                    "%-28s %-8s %-12s %s",
                    i.row().repo, Out.cmd("#" + i.row().pr), i.row().verdict, Out.faint(i.why(me))));
            Out.item("  " + trim(i.title(), 74));
            // The one line here that came from running the code rather than reading it, which is
            // why it is the one that gets a colour rather than a prefix nobody scans for.
            if (!i.benchSaid().isEmpty()) {
                Out.item("  " + Out.good("runner") + " " + trim(i.benchSaid(), 74));
            }
            if (urgent && i.pushed()) {
                Out.item("  " + Out.cmd("oss followup --since " + i.row().pr));
            }
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
