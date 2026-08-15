package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.review.ReviewLedger;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>So this sorts everything you have reviewed into two buckets by whose move it is. It reads the
 * ledger for what you reviewed and at which head, then asks GitHub what has happened since.
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--repo", description = "Only this repository, as owner/name")
    String repo;

    @Option(names = "--all", description = "Include the ones where the ball is not in your court")
    boolean all;

    @Override
    public Integer call() {
        List<ReviewLedger.Row> rows = ReviewLedger.read();
        if (rows.isEmpty()) {
            System.out.println("Nothing reviewed yet, so nobody is waiting on you.");
            System.out.println();
            System.out.println("  oss followup --record <pr> --repo owner/name --verdict take");
            return 0;
        }

        String me = me();
        List<Item> yours = new ArrayList<>();
        List<Item> theirs = new ArrayList<>();
        int unreachable = 0;

        for (ReviewLedger.Row r : rows) {
            if (repo != null && !repo.isBlank() && !r.repo.equalsIgnoreCase(repo.trim())) {
                continue;
            }
            JsonNode pull = api("/repos/" + r.repo + "/pulls/" + r.pr);
            if (pull == null) {
                unreachable++;
                continue;
            }
            Item it = new Item();
            it.row = r;
            it.state = pull.path("state").asText("?");
            it.merged = pull.path("merged_at").asText("").length() > 0;
            it.head = pull.path("head").path("sha").asText("");
            it.title = pull.path("title").asText("");
            it.pushed = !it.head.isEmpty() && !it.head.equals(r.head);

            Said last = lastWord(r.repo, r.pr);
            it.lastBy = last == null ? "" : last.by;
            it.lastAt = last == null ? "" : last.at;

            // Whose move it is. Two things put it back on you: the author pushed after you looked,
            // or the last word is somebody else's. Both mean the thing you decided was decided
            // against a state that no longer exists.
            it.onYou = !it.merged
                    && "open".equalsIgnoreCase(it.state)
                    && (it.pushed || (!it.lastBy.isEmpty() && !it.lastBy.equals(me)));
            (it.onYou ? yours : theirs).add(it);
        }

        yours.sort(Comparator.comparing((Item i) -> i.lastAt).reversed());
        theirs.sort(Comparator.comparing((Item i) -> i.lastAt).reversed());

        System.out.println();
        print("Waiting on you", yours, true);
        if (all) {
            print("Waiting on them", theirs, false);
        } else if (!theirs.isEmpty()) {
            System.out.printf("  %d not waiting on you — oss hub --all%n%n", theirs.size());
        }
        if (unreachable > 0) {
            // The reason is asked for rather than assumed. With the wifi off this listed seventeen
            // pull requests as "private, deleted, or no token" -- three explanations, all wrong,
            // each of which sends the reader hunting for a problem they do not have.
            System.out.printf("  %d unreachable (%s)%n%n", unreachable, com.osscli.github.Reachability.whyUnreadable());
        }
        return 0;
    }

    private void print(String heading, List<Item> items, boolean urgent) {
        System.out.println("  " + heading.toUpperCase());
        if (items.isEmpty()) {
            System.out.println("    nothing" + (urgent ? " — you are clear" : ""));
            System.out.println();
            return;
        }
        for (Item i : items) {
            String why = i.merged
                    ? "merged"
                    : i.pushed && !i.lastBy.isEmpty() && !i.lastBy.equals(me())
                            ? "pushed + replied"
                            : i.pushed ? "pushed since your review" : i.lastBy.isEmpty() ? "-" : "reply:" + i.lastBy;
            System.out.printf("    %-28s #%-6d %-12s %s%n", i.row.repo, i.row.pr, i.row.verdict, why);
            System.out.printf("      %s%n", trim(i.title, 74));
            if (urgent && i.pushed) {
                System.out.printf("      oss followup --since %d%n", i.row.pr);
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------- github ---

    private JsonNode api(String path) {
        try {
            String json = new GitHubClient().getJson(path);
            return (json == null || json.isBlank()) ? null : MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Said lastWord(String repoName, int pr) {
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

    private String me() {
        try {
            String u = SqliteStorage.loadConfig("github.username");
            return u == null ? "" : u.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final class Item {
        ReviewLedger.Row row;
        String state = "";
        String title = "";
        String head = "";
        String lastBy = "";
        String lastAt = "";
        boolean merged;
        boolean pushed;
        boolean onYou;
    }

    private static final class Said {
        String at = "";
        String by = "";
    }
}
