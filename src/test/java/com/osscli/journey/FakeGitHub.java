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
package com.osscli.journey;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A GitHub that answers on localhost, so a journey can sync something and then use it.
 *
 * <p>Nothing here pretends to be GitHub. It serves the few shapes this tool reads -- an issue list,
 * a search result, a pull request and its files -- which is enough to type {@code sync}, then
 * {@code review}, then {@code followup} in order and see whether what the first fetched is what the
 * last reports on.
 *
 * <p>It also records every path it was asked for. That is not decoration: one call in
 * {@code GitHubClient} had {@code api.github.com} written into it while the other thirteen used
 * {@code apiBase()}, so on a GitHub Enterprise install -- the case {@code GITHUB_API_URL} exists
 * for -- that one request left for the public API, where the token is not valid and the repository
 * does not exist. A stub that records what reached it is how that stops being invisible.
 */
public final class FakeGitHub implements AutoCloseable {

    private final HttpServer server;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<String> posted = new CopyOnWriteArrayList<>();
    private volatile boolean duplicateExists;

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            asked.add(path + (query == null ? "" : "?" + query));
            // A write, kept apart from every read above it. `oss bug` is the only thing in this
            // program that POSTs anywhere, and a stub that answered 200 to everything would let a
            // test claim "it filed the issue" without anything ever having been sent.
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                posted.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] created = "{ \"number\": 4321, \"html_url\": \"http://example.invalid/4321\" }"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, created.length);
                exchange.getResponseBody().write(created);
                exchange.close();
                return;
            }
            byte[] body = bodyFor(path, query, duplicateExists).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    /** Where to point {@code GITHUB_API_URL}. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every body this server was asked to store, in order. Empty means nothing was written. */
    public List<String> posted() {
        return new ArrayList<>(posted);
    }

    /**
     * Answer the duplicate search with a hit, so the "this is already filed" path can be walked.
     *
     * <p>Off by default: a search that always matches would make every filing journey report a
     * duplicate and never post, which is the failure looking exactly like the success.
     */
    public void withADuplicateAlreadyFiled() {
        duplicateExists = true;
    }

    /** Every path this server was asked for, in order. */
    public List<String> asked() {
        return new ArrayList<>(asked);
    }

    /** Whether any request went here at all for a path containing the given fragment. */
    public boolean sawPathContaining(String fragment) {
        return asked.stream().anyMatch(p -> p.contains(fragment));
    }

    private static String bodyFor(String path, String query, boolean duplicateExists) {
        if (path.contains("/pulls/") && path.endsWith("/files")) {
            return """
                   [ { "filename": "src/main/java/com/example/Pool.java", "status": "modified",
                       "additions": 12, "deletions": 3, "changes": 15 } ]
                   """;
        }
        if (path.contains("/pulls/")) {
            return pullRequest();
        }
        if (path.startsWith("/search/issues")) {
            // The duplicate check searches for a signature. Answering that with the stock issue
            // would tell every reporter their bug was already filed.
            if (query != null && query.contains("oss-signature") && !duplicateExists) {
                return "{ \"total_count\": 0, \"incomplete_results\": false, \"items\": [] }";
            }
            return "{ \"total_count\": 1, \"incomplete_results\": false, \"items\": [" + issue() + "] }";
        }
        if (path.contains("/issues")) {
            // An issue AND a pull request. GitHub returns pull requests from the issues endpoint
            // with a "pull_request" marker on them, and it is that marker which sends sync down the
            // path that fetches a PR's changed files -- the path where one call had the public API
            // written into it. A stub that returned only issues never reached it.
            return "[" + issue() + ", " + pullRequestAsIssue() + "]";
        }
        // A repository, a user, anything else this tool asks about in passing.
        return "{}";
    }

    private static String issue() {
        return """
               {
                 "number": 4129,
                 "title": "Pool deadlocks above 200 threads",
                 "body": "The borrow path takes the locks in the opposite order to the eviction sweep.",
                 "state": "open",
                 "comments": 2,
                 "created_at": "2026-08-01T09:00:00Z",
                 "updated_at": "2026-08-02T09:00:00Z",
                 "labels": [ { "name": "bug" } ],
                 "user": { "login": "someone" },
                 "author_association": "CONTRIBUTOR",
                 "html_url": "https://example.invalid/owner/name/issues/4129"
               }
               """;
    }

    /** How GitHub hands a pull request back from the issues endpoint: an issue with a marker. */
    private static String pullRequestAsIssue() {
        return """
               {
                 "number": 812,
                 "title": "Order the eviction sweep like borrow",
                 "body": "Removes the cycle described in #4129.",
                 "state": "open",
                 "comments": 0,
                 "created_at": "2026-08-03T09:00:00Z",
                 "updated_at": "2026-08-04T09:00:00Z",
                 "pull_request": { "url": "https://example.invalid/owner/name/pull/812" },
                 "labels": [],
                 "user": { "login": "someone" },
                 "author_association": "CONTRIBUTOR",
                 "html_url": "https://example.invalid/owner/name/pull/812"
               }
               """;
    }

    private static String pullRequest() {
        return """
               {
                 "number": 812,
                 "title": "Order the eviction sweep like borrow",
                 "body": "Removes the cycle described in #4129.",
                 "state": "open",
                 "merged": false,
                 "draft": false,
                 "user": { "login": "someone" },
                 "head": { "sha": "abc123def456", "ref": "fix/lock-order" },
                 "base": { "ref": "main" },
                 "created_at": "2026-08-03T09:00:00Z",
                 "updated_at": "2026-08-04T09:00:00Z",
                 "additions": 12, "deletions": 3, "changed_files": 1, "comments": 0,
                 "html_url": "https://example.invalid/owner/name/pull/812"
               }
               """;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
