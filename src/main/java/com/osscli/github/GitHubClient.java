/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.osscli.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.model.Issue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GitHubClient {
    private static final Logger LOGGER = LogManager.getLogger(GitHubClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Where the API lives. Overridable, so what a disconnected machine does can be tested.
     *
     * <p>Not a feature request — the offline behaviour of six commands was wrong at once and nothing
     * in the suite could have caught it, because every test either had a network or mocked the layer
     * above the failure. Pointed at a host that does not resolve, this reproduces a pulled cable
     * exactly: the same exception, from the same place, with the same null message.
     *
     * <p>{@code GITHUB_API_URL} is also what GitHub Enterprise installations set, so this is the
     * name someone would already reach for.
     */
    static String apiBase() {
        for (String candidate : new String[] {System.getProperty("oss.github.api"), System.getenv("GITHUB_API_URL")}) {
            if (candidate != null && !candidate.isBlank()) {
                String trimmed = candidate.trim();
                return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            }
        }
        return "https://api.github.com";
    }

    private final HttpClient httpClient;
    private final String token;

    public GitHubClient(String token) {

        this.httpClient = HttpClient.newHttpClient();
        this.token = token;
    }

    public GitHubClient() {
        this(com.osscli.util.CredentialManager.getGitHubToken());
    }

    public List<Issue> getOpenIssues(String owner, String repo, String since) throws IOException, InterruptedException {
        List<Issue> allIssues = new ArrayList<>();

        for (int page = 1; ; page++) {
            String url = apiBase() + "/repos/" + owner + "/" + repo + "/issues?state=open&per_page=100&page=" + page;

            // Append since parameter to the API URL if present
            if (since != null && !since.trim().isEmpty()) {
                url += "&since=" + java.net.URLEncoder.encode(since, java.nio.charset.StandardCharsets.UTF_8);
            }

            List<Issue> issues = fetchPage(url);
            if (issues.isEmpty()) {
                break;
            }
            allIssues.addAll(issues);
            LOGGER.info("Fetched page {} ({} issues)", page, issues.size());
            if (issues.size() < 100) {
                break;
            }
        }
        return allIssues;
    }

    public List<Issue> fetchPage(String url) throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw Reachability.asFailure(e);
        }

        if (response.statusCode() != 200) {
            throw new IOException(describeApiFailure(response));
        }

        return MAPPER.readValue(response.body(), new TypeReference<List<Issue>>() {});
    }

    /**
     * Turns a non-200 GitHub response into a message that names the actual problem. Without this the error body (a JSON
     * object) reaches Jackson, which reports an unhelpful "cannot deserialize ArrayList<Issue> from Object value".
     */
    private static String describeApiFailure(HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = response.headers()
                .firstValue("x-github-request-id")
                .map(id -> " (request id " + id + ")")
                .orElse("");

        return switch (status) {
            case 401 ->
                "GitHub rejected the token (401 Bad credentials)" + detail
                        + ". The stored token is expired or revoked -- create a new one at "
                        + "https://github.com/settings/tokens and register it with 'oss setup'.";
            case 403, 429 -> {
                boolean exhausted = response.headers()
                        .firstValue("x-ratelimit-remaining")
                        .map("0"::equals)
                        .orElse(false);
                yield exhausted
                        ? "GitHub rate limit exhausted (" + status + ")" + detail + ". Resets at epoch seconds "
                                + response.headers()
                                        .firstValue("x-ratelimit-reset")
                                        .orElse("unknown") + "."
                        : "GitHub denied the request (" + status + ")" + detail
                                + ". The token is likely missing the required scopes.";
            }
            case 404 ->
                "Repository or endpoint not found (404)" + detail
                        + ". It may be private, renamed, or the token lacks access.";
            default -> {
                String body = com.osscli.util.Redactor.redact(response.body()).text();
                yield "GitHub API call failed with status " + status + detail + ": "
                        + (body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            }
        };
    }

    /**
     * How many results a search will collect before it stops.
     *
     * <p>GitHub's search API caps at a thousand however it is asked, so this is the ceiling rather
     * than a choice. What matters is that the caller is told when it was reached.
     */
    public static final int SEARCH_LIMIT = 1_000;

    /** What a search found, and how much of it there was. */
    public record Found(List<Issue> items, int totalAvailable) {

        /** Whether the answer is a page of the answer. */
        public boolean truncated() {
            return totalAvailable > items.size();
        }
    }

    /**
     * Every result a search matches, up to the API's own ceiling.
     *
     * <p>This sent no {@code per_page} and read one page, so it returned <b>thirty</b> results
     * whatever matched — and said nothing about it. A profile built from
     * {@code author:<you> type:pr is:merged} was therefore built from thirty pull requests, and a
     * harvest of {@code involves:<you>} collected thirty items out of 1,218. Neither reported a
     * cap, so both read as "this is everything you have done".
     *
     * <p>A truncation that does not announce itself is worse than a smaller number: the reader
     * acts on it as a total.
     */
    public Found search(String query) throws IOException, InterruptedException {
        List<Issue> all = new ArrayList<>();
        int total = 0;
        for (int page = 1; all.size() < SEARCH_LIMIT; page++) {
            String url = apiBase() + "/search/issues?per_page=100&page=" + page + "&q="
                    + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpResponse<String> response = searchOnce(url);
            Map<?, ?> body = MAPPER.readValue(response.body(), Map.class);
            if (total == 0 && body.get("total_count") instanceof Number n) {
                total = n.intValue();
            }
            List<?> items = (List<?>) body.get("items");
            if (items == null || items.isEmpty()) {
                break;
            }
            String itemsJson = MAPPER.writeValueAsString(items);
            all.addAll(MAPPER.readValue(
                    itemsJson, MAPPER.getTypeFactory().constructCollectionType(List.class, Issue.class)));
            if (items.size() < 100) {
                break;
            }
        }
        return new Found(List.copyOf(all), Math.max(total, all.size()));
    }

    /** One page of a search, or a described failure. */
    private java.net.http.HttpResponse<String> searchOnce(String urlString) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(20))
                .build();
        java.net.http.HttpResponse<String> response;
        try {
            response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw Reachability.asFailure(e);
        }
        if (response.statusCode() != 200) {
            throw new IOException(
                    "GitHub Search API failed with status code " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    public List<Issue> searchIssuesAndPrs(String query) throws IOException, InterruptedException {
        String urlString = apiBase() + "/search/issues?q="
                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(20))
                .build();

        java.net.http.HttpResponse<String> response;
        try {
            response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw Reachability.asFailure(e);
        }

        if (response.statusCode() != 200) {
            throw new IOException(
                    "GitHub Search API failed with status code " + response.statusCode() + ": " + response.body());
        }

        Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
        List<?> itemsList = (List<?>) responseMap.get("items");

        // Deserialize the nested items array into standard Issue records
        String itemsJson = MAPPER.writeValueAsString(itemsList);
        return MAPPER.readValue(itemsJson, MAPPER.getTypeFactory().constructCollectionType(List.class, Issue.class));
    }

    public List<String> getPullRequestFiles(String owner, String repo, long prNumber)
            throws IOException, InterruptedException {
        // apiBase(), like every other call in this class. This one had api.github.com written into
        // it, so a GitHub Enterprise install -- the case GITHUB_API_URL exists for -- sent thirteen
        // requests to its own host and this one to the public API, where its token is not valid and
        // its repository does not exist. The failure lands on "the files in this pull request",
        // which reads as a permissions problem with the PR rather than a wrong host.
        String urlString = String.format("%s/repos/%s/%s/pulls/%d/files", apiBase(), owner, repo, prNumber);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

        java.net.http.HttpResponse<String> response;
        try {
            response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw Reachability.asFailure(e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("GitHub Pull Request Files API failed with status code " + response.statusCode());
        }

        List<?> filesList = MAPPER.readValue(response.body(), List.class);
        List<String> filePaths = new ArrayList<>();
        for (Object obj : filesList) {
            Map<?, ?> fileMap = (Map<?, ?>) obj;
            String filename = (String) fileMap.get("filename");
            if (filename != null) {
                filePaths.add(filename);
            }
        }
        return filePaths;
    }

    // ── Pull request review surface ──────────────────────────────────────────
    // Everything below reads from the API alone, so a review works for a repository the user has
    // never cloned. A local checkout can add build and test evidence on top, but must not be the
    // price of entry for someone reviewing a project they just discovered.

    /** Raw JSON body of an arbitrary API path, e.g. {@code /repos/o/r/pulls/1}. Null when the endpoint 404s. */
    public String getJson(String path) throws IOException, InterruptedException {
        return get(apiBase() + path, "application/vnd.github+json");
    }

    /** The unified diff for a pull request, via the {@code .diff} media type. Null when unavailable. */
    public String getPullRequestDiff(String owner, String repo, long prNumber)
            throws IOException, InterruptedException {
        return get(apiBase() + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber, "application/vnd.github.v3.diff");
    }

    /**
     * Fetches every page of a paginated collection endpoint and returns the concatenated elements.
     *
     * <p>Paginating matters for review accuracy rather than completeness alone: a reviewer shown the first 30 of 80
     * changed files has no signal that 50 are missing, and would sign off on a diff they never saw.
     */
    public List<Map<String, Object>> getPaged(String path, int maxPages) throws IOException, InterruptedException {
        List<Map<String, Object>> all = new ArrayList<>();
        String separator = path.contains("?") ? "&" : "?";

        for (int page = 1; page <= maxPages; page++) {
            String body =
                    get(apiBase() + path + separator + "per_page=100&page=" + page, "application/vnd.github+json");
            if (body == null) {
                break;
            }
            List<Map<String, Object>> pageItems =
                    MAPPER.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
            all.addAll(pageItems);
            if (pageItems.size() < 100) {
                break;
            }
        }
        return all;
    }

    /**
     * Decoded contents of a file in the default branch, or null when it does not exist.
     *
     * <p>A missing file is a normal answer here -- profiling asks for many documents a given repository may simply not
     * have -- so absence is reported as null rather than raised, and only real failures throw.
     */
    public String getFileContent(String owner, String repo, String filePath) throws IOException, InterruptedException {
        String body = get(
                apiBase() + "/repos/" + owner + "/" + repo + "/contents/" + filePath, "application/vnd.github.v3.raw");
        return body;
    }

    /**
     * Open an issue, and return its number.
     *
     * <p>The only call in this class that writes, and the only one anywhere in this program that
     * writes to a repository at all. It is reachable from exactly one place -- {@code oss bug},
     * after the whole body has been printed and confirmed -- and that is the arrangement the rule
     * "never act unasked" turns into here: not "no writes", but no write whose text the person did
     * not read first.
     *
     * <p>A 403 is called out by name. Filing needs a token with issue write on the target
     * repository, and the default read-only token most people have gives exactly that status with a
     * body nobody can act on.
     */
    public long createIssue(String owner, String repo, String title, String body, List<String> labels)
            throws IOException, InterruptedException {
        String payload = MAPPER.writeValueAsString(
                Map.of("title", title, "body", body, "labels", labels == null ? List.of() : labels));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase() + "/repos/" + owner + "/" + repo + "/issues"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw Reachability.asFailure(e);
        }
        if (response.statusCode() == 403 || response.statusCode() == 404) {
            throw new IOException("GitHub refused the issue (" + response.statusCode()
                    + "). A token that can file needs issue write on " + owner + "/" + repo + ".");
        }
        if (response.statusCode() != 201) {
            throw new IOException(describeApiFailure(response));
        }
        Map<?, ?> created = MAPPER.readValue(response.body(), Map.class);
        Object number = created.get("number");
        return number instanceof Number n ? n.longValue() : -1;
    }

    /** Shared GET returning the body, null on 404, and a described failure otherwise. */
    private String get(String url, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", accept)
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Translated here rather than in each caller. A disconnected machine raises
            // ConnectException with a null message, and six different catch blocks were each
            // inventing their own wrong explanation for it -- including "private, deleted, or no
            // token" against seventeen pull requests that were none of those things.
            throw Reachability.asFailure(e);
        }

        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException(describeApiFailure(response));
        }
        return response.body();
    }
}
