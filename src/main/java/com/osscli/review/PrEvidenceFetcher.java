package com.osscli.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.model.PrEvidence;
import com.osscli.storage.SqliteStorage;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Collects the facts a reviewer needs about a pull request, in one pass, from the GitHub API.
 *
 * <p>This is Layer 0 of review: it requires nothing but a token. No local clone, no Ollama, no cloud key, no knowledge
 * base. Everything above it is optional enrichment, so a user who has just installed the tool still gets a real answer
 * rather than a list of things to go and install.
 */
public final class PrEvidenceFetcher {

    private static final Logger LOGGER = LogManager.getLogger(PrEvidenceFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pages of 100 are plenty for review; beyond this a pull request is a migration, not a change to read. */
    private static final int MAX_PAGES = 10;

    private PrEvidenceFetcher() {}

    /**
     * Returns evidence for {@code prNumber}, reusing the cache when the head commit has not moved.
     *
     * <p>The head SHA is resolved first, with a single cheap call, and everything else is fetched only on a miss. That
     * ordering is what makes the cache trustworthy: it is verified against the live pull request rather than assumed
     * from an age, so a re-review after a force-push can never be served from stale rows.
     */
    public static PrEvidence fetch(String repository, long prNumber, boolean refresh) throws Exception {
        String[] parts = repository.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Repository must be 'owner/name', got: " + repository);
        }
        String owner = parts[0];
        String name = parts[1];

        GitHubClient client = new GitHubClient();

        String prJson = client.getJson("/repos/" + owner + "/" + name + "/pulls/" + prNumber);
        if (prJson == null) {
            throw new IllegalArgumentException("Pull request #" + prNumber + " was not found in '" + repository + "'."
                    + " Check the number, or that the repository is accessible with your token.");
        }
        JsonNode pr = MAPPER.readTree(prJson);
        String headSha = pr.path("head").path("sha").asText(null);

        if (!refresh && headSha != null) {
            PrEvidence cached = SqliteStorage.loadPrEvidence(repository, prNumber, headSha);
            if (cached != null) {
                LOGGER.info(
                        "  ↳ Head commit {} is unchanged — using cached evidence. Pass --refresh to re-fetch.",
                        shortSha(headSha));
                return cached;
            }
        }

        LOGGER.info("  ↳ Fetching pull request evidence from GitHub (head {})...", shortSha(headSha));

        String base = "/repos/" + owner + "/" + name + "/pulls/" + prNumber;
        List<Map<String, Object>> commits = client.getPaged(base + "/commits", MAX_PAGES);
        List<Map<String, Object>> files = client.getPaged(base + "/files", MAX_PAGES);
        List<Map<String, Object>> reviews = client.getPaged(base + "/reviews", MAX_PAGES);
        List<Map<String, Object>> comments = client.getPaged(base + "/comments", MAX_PAGES);

        // Checks hang off the commit, not the pull request, and are absent on repositories with no CI.
        String checksJson = null;
        if (headSha != null) {
            try {
                checksJson = client.getJson("/repos/" + owner + "/" + name + "/commits/" + headSha + "/check-runs");
            } catch (Exception e) {
                LOGGER.debug("No check runs available for {}: {}", shortSha(headSha), e.getMessage());
            }
        }

        // The diff is the largest single payload and the most likely to be refused on a huge PR. Losing it must not
        // discard the metadata already gathered -- a file list and CI state still make a usable review.
        String diff = null;
        try {
            diff = client.getPullRequestDiff(owner, name, prNumber);
        } catch (Exception e) {
            LOGGER.warn("  ⚠ Diff unavailable ({}). Review will use the file list only.", e.getMessage());
        }

        PrEvidence evidence = new PrEvidence(
                repository,
                prNumber,
                headSha == null ? "unknown" : headSha,
                pr.path("title").asText(null),
                pr.path("user").path("login").asText(null),
                pr.path("state").asText(null),
                pr.path("base").path("ref").asText(null),
                pr.path("body").asText(null),
                MAPPER.writeValueAsString(commits),
                MAPPER.writeValueAsString(files),
                diff,
                MAPPER.writeValueAsString(reviews),
                MAPPER.writeValueAsString(comments),
                checksJson,
                pr.path("additions").asInt(0),
                pr.path("deletions").asInt(0),
                pr.path("changed_files").asInt(0));

        SqliteStorage.savePrEvidence(evidence);
        return evidence;
    }

    public static String shortSha(String sha) {
        return sha == null ? "unknown" : sha.substring(0, Math.min(7, sha.length()));
    }
}
