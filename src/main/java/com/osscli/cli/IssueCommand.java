package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Read an issue as filed, without leaving the terminal.
 *
 * <p>The body is printed unaltered. A bug report is evidence, and summarising evidence before
 * someone has read it is how the one detail that mattered gets dropped -- usually the version, or
 * the line of configuration that turns out to be the whole thing.
 *
 * <p>In the core, because reading an issue is an API call. Nothing about it needs a clone.
 */
@Command(name = "issue", mixinStandardHelpOptions = true, description = "Read an issue as it was filed")
public class IssueCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", description = "Issue number")
    int number;

    @Option(names = "--repo", required = true, description = "owner/name")
    String repo;

    @Option(names = "--comments", description = "Include the discussion")
    boolean comments;

    @Override
    public Integer call() {
        try {
            GitHubClient gh = new GitHubClient();
            JsonNode issue = MAPPER.readTree(gh.getJson("/repos/" + repo + "/issues/" + number));
            if (issue.has("message") && !issue.has("number")) {
                System.err.println("error  " + issue.path("message").asText());
                return 1;
            }

            // An issue endpoint also answers for pull requests, and the two need reading very
            // differently -- so say which this is rather than letting someone assume.
            boolean isPr = issue.has("pull_request");

            System.out.printf("%n  %s #%d%s%n", repo, number, isPr ? "  (this is a pull request)" : "");
            System.out.printf("  %s%n%n", issue.path("title").asText(""));
            System.out.printf(
                    "  author      %s%n", issue.path("user").path("login").asText("?"));
            System.out.printf("  state       %s%n", issue.path("state").asText("?"));
            System.out.printf("  opened      %s%n", issue.path("created_at").asText(""));

            StringBuilder labels = new StringBuilder();
            issue.path("labels").forEach(l -> labels.append(labels.length() == 0 ? "" : ", ")
                    .append(l.path("name").asText()));
            if (labels.length() > 0) {
                System.out.printf("  labels      %s%n", labels);
            }

            System.out.println();
            System.out.println("  ── as filed ──");
            System.out.println();
            String body = issue.path("body").asText("");
            System.out.println(body.isBlank() ? "  (no description)" : body);

            if (comments) {
                JsonNode all =
                        MAPPER.readTree(gh.getJson("/repos/" + repo + "/issues/" + number + "/comments?per_page=100"));
                System.out.printf("%n  ── %d comment(s) ──%n", all.size());
                all.forEach(c -> {
                    System.out.printf(
                            "%n  %s  %s%n%n",
                            c.path("user").path("login").asText("?"),
                            c.path("created_at").asText(""));
                    System.out.println(c.path("body").asText(""));
                });
            }

            System.out.printf("%n  https://github.com/%s/issues/%d%n", repo, number);
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }
}
