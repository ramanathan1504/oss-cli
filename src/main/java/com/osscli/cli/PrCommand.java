package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Every mechanical fact about a pull request, without leaving the terminal.
 *
 * <p>Deliberately facts only — size, checks, who wrote it, what it touches. Nothing here judges,
 * because the judgement is the part worth doing yourself and a tool that mixes the two makes it hard
 * to tell which you are reading.
 *
 * <p>In the core rather than a runner: reading a pull request needs an API call and nothing else.
 * What genuinely needs a bench is checking the branch out and building it, and that stays there.
 */
@Command(name = "pr", mixinStandardHelpOptions = true, description = "Every mechanical fact about a pull request")
public class PrCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", description = "Pull request number")
    int number;

    @Option(names = "--repo", required = true, description = "owner/name")
    String repo;

    @Option(names = "--diff", description = "The patch itself")
    boolean diff;

    @Option(names = "--files", description = "Only the files it touches")
    boolean filesOnly;

    @Override
    public Integer call() {
        try {
            GitHubClient gh = new GitHubClient();
            JsonNode pr = MAPPER.readTree(gh.getJson("/repos/" + repo + "/pulls/" + number));
            if (pr.has("message")) {
                System.err.println("error  " + pr.path("message").asText());
                return 1;
            }

            if (diff) {
                System.out.println(gh.getPullRequestDiff(owner(), name(), number));
                return 0;
            }

            String files = gh.getJson("/repos/" + repo + "/pulls/" + number + "/files?per_page=100");
            JsonNode fileNodes = MAPPER.readTree(files);

            if (filesOnly) {
                fileNodes.forEach(f -> System.out.printf(
                        "  +%-5d -%-5d %s%n",
                        f.path("additions").asInt(),
                        f.path("deletions").asInt(),
                        f.path("filename").asText()));
                return 0;
            }

            System.out.printf("%n  %s #%d%n", repo, number);
            System.out.printf("  %s%n%n", pr.path("title").asText(""));
            System.out.printf(
                    "  author      %s%n", pr.path("user").path("login").asText("?"));
            System.out.printf(
                    "  state       %s%s%n",
                    pr.path("state").asText("?"), pr.path("draft").asBoolean(false) ? " (draft)" : "");
            System.out.printf("  base        %s%n", pr.path("base").path("ref").asText("?"));
            System.out.printf(
                    "  head        %s%n", shortSha(pr.path("head").path("sha").asText("")));
            System.out.printf(
                    "  size        +%d −%d, %d file(s)%n",
                    pr.path("additions").asInt(),
                    pr.path("deletions").asInt(),
                    pr.path("changed_files").asInt());
            System.out.printf(
                    "  mergeable   %s%n",
                    pr.path("mergeable").isNull()
                            ? "unknown"
                            : pr.path("mergeable").asText());

            // Checks come from the head commit rather than the pull request, because a pull request
            // has no status of its own -- asking it returns nothing and reads like "CI is green".
            String sha = pr.path("head").path("sha").asText("");
            if (!sha.isEmpty()) {
                JsonNode checks = MAPPER.readTree(gh.getJson("/repos/" + repo + "/commits/" + sha + "/check-runs"));
                int total = checks.path("total_count").asInt();
                StringBuilder failing = new StringBuilder();
                checks.path("check_runs").forEach(c -> {
                    if ("failure".equalsIgnoreCase(c.path("conclusion").asText(""))) {
                        failing.append(failing.length() == 0 ? "" : ", ")
                                .append(c.path("name").asText());
                    }
                });
                if (total == 0) {
                    // "0 runs" reads as "fine". It is not the same as passing: nothing has
                    // reported on this head at all, which is the state a stale or unrun CI is in.
                    System.out.println("  checks      none reported on this head (not the same as passing)");
                } else {
                    System.out.printf(
                            "  checks      %d run%s%s%n",
                            total,
                            total == 1 ? "" : "s",
                            failing.length() == 0 ? "  all green" : "  FAILING: " + failing);
                }
            }

            System.out.println();
            fileNodes.forEach(f -> System.out.printf(
                    "    +%-5d -%-5d %s%n",
                    f.path("additions").asInt(),
                    f.path("deletions").asInt(),
                    f.path("filename").asText()));
            System.out.println();
            System.out.printf("  https://github.com/%s/pull/%d%n", repo, number);
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    private String owner() {
        return repo.split("/")[0];
    }

    private String name() {
        return repo.split("/")[1];
    }

    private static String shortSha(String s) {
        return s.length() < 8 ? s : s.substring(0, 8);
    }
}
