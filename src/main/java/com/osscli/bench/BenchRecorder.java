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
package com.osscli.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import java.util.List;

/**
 * Turn one runner invocation into a row somebody can read later.
 *
 * <p>Separate from {@link BenchLedger} because the ledger is a file format and this is the two
 * lookups that fill a row in: what GitHub says the pull request's head is, and what commit this
 * machine is actually sitting on. Both are best-effort — neither a missing token nor a directory
 * that is not a git repository is a reason to lose the result of a build that really ran.
 */
public final class BenchRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BenchRecorder() {}

    /**
     * Record one run, and say on the terminal what was recorded.
     *
     * <p>Says it out loud because the value of this is entirely in it being read back later, and a
     * silent write is one nobody knows to look for.
     */
    public static void record(String repoOption, int pr, String verb, int exit, String runner) {
        String repo = resolveRepo(repoOption);
        if (repo == null) {
            System.err.println("  (not recorded: which repository is #" + pr + " in? add --repo owner/name)");
            return;
        }

        BenchLedger.Row row = new BenchLedger.Row();
        row.repo = repo;
        row.pr = pr;
        row.verb = verb;
        row.exit = exit;
        row.runner = runner == null ? "built-in" : runner;
        row.ranAt = java.time.Instant.now().toString();
        row.prHead = prHead(repo, pr);
        row.ranOn = localHead();

        BenchLedger.record(row);

        System.out.println();
        System.out.println("  recorded against " + repo + "#" + pr + " — " + row.summary());
        if (row.trust() == BenchLedger.Trust.OTHER_CODE) {
            // Said at the moment it happens rather than only when it is read back. Somebody who
            // meant to test the change can still check the branch out and run it again; somebody
            // who reads this a week later can only wonder.
            System.out.println("  the pull request is at " + BenchLedger.shortSha(row.prHead)
                    + " — check that branch out and run it again for a result about this change.");
        }
    }

    /**
     * Which repository, when it was not typed.
     *
     * <p>Only when there is exactly one it could be. Guessing among several would attach a build to
     * the wrong pull request, and a ledger with a wrong row in it is worse than one with a gap:
     * the gap is visible.
     */
    static String resolveRepo(String given) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        try {
            List<String> repos = com.osscli.storage.SqliteStorage.loadMonitoredRepositories();
            return repos.size() == 1 ? repos.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What GitHub says the pull request's head is. Blank when it cannot be read. */
    static String prHead(String repo, int pr) {
        try {
            String json = new GitHubClient().getJson("/repos/" + repo + "/pulls/" + pr);
            if (json == null || json.isBlank()) {
                return "";
            }
            JsonNode node = MAPPER.readTree(json);
            return node.path("head").path("sha").asText("");
        } catch (Exception e) {
            // Offline, no token, or a pull request that is not readable. The run still happened.
            return "";
        }
    }

    /** The commit this machine is on, or blank when this is not a git checkout. */
    static String localHead() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            // A sha and nothing else. Any error message git prints is not one.
            return p.exitValue() == 0 && out.matches("[0-9a-fA-F]{7,40}") ? out : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
