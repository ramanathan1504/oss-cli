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
    private static final String GITHUB_API = "https://api.github.com";

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
            String url = GITHUB_API + "/repos/" + owner + "/" + repo + "/issues?state=open&per_page=100&page=" + page;

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

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }

    public List<Issue> searchIssuesAndPrs(String query) throws IOException, InterruptedException {
        String urlString = "https://api.github.com/search/issues?q="
                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(20))
                .build();

        java.net.http.HttpResponse<String> response =
                httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

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
        String urlString = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", owner, repo, prNumber);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(urlString))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

        java.net.http.HttpResponse<String> response =
                httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

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
}
