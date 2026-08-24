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
import com.osscli.AppPaths;
import com.osscli.github.GitHubClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The pull request's own code, on disk, for as long as it takes to run one verb.
 *
 * <p>Without this, {@code oss run --pr} records whatever tree you happen to be standing in — which
 * for anyone reviewing from their {@code main} checkout is never the change under review. The
 * ledger said so honestly every time, and "not this change" on every row is a warning rather than
 * an answer. This makes the useful case the default one.
 *
 * <p>A worktree rather than a clone or a branch switch. A clone of a large repository costs
 * minutes and a gigabyte; a branch switch mutates the checkout somebody is working in, and a build
 * that fails halfway leaves them somewhere they did not ask to be. A worktree is a second directory
 * sharing the same object store: cheap, detached, and removable without touching the tree you were
 * in.
 *
 * <p><b>Fetching a fork's code is safe; running it is not.</b> GitHub publishes every pull request's
 * head under {@code refs/pull/N/head} on the base repository, so the fetch needs no special access
 * and reveals nothing. Executing what comes back is another matter — a build file is a script, and
 * a contributor's is somebody else's script running on a machine that holds your token. So a pull
 * request from a fork is refused unless it is explicitly allowed, one run at a time.
 */
public final class Checkout {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Checkout() {}

    /** Where the worktrees live: beside the data, never inside the repository being reviewed. */
    public static final Path DIR = AppPaths.BASE_DIR.resolve("worktrees");

    /** A prepared checkout, and what is worth knowing before running anything in it. */
    public record Prepared(Path dir, String sha, boolean fork, String headRepo) {}

    /** Raised with a sentence a reader can act on. Never a stack trace at somebody. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * What GitHub says about a pull request's head, before anything is fetched.
     *
     * <p>Separate from the fetch so the fork question can be asked and answered before a single
     * byte of somebody else's code is on disk.
     */
    public static Prepared describe(String repo, int pr) {
        JsonNode node;
        try {
            String json = new GitHubClient().getJson("/repos/" + repo + "/pulls/" + pr);
            if (json == null || json.isBlank()) {
                throw new Refused(
                        "could not read " + repo + "#" + pr + " — is the number right, and is there a token?");
            }
            node = MAPPER.readTree(json);
        } catch (Refused e) {
            throw e;
        } catch (Exception e) {
            throw new Refused("could not read " + repo + "#" + pr + ": " + e.getMessage());
        }
        String sha = node.path("head").path("sha").asText("");
        if (sha.isBlank()) {
            throw new Refused(repo + "#" + pr + " has no head commit — it may have been deleted.");
        }
        String headRepo = node.path("head").path("repo").path("full_name").asText("");
        String baseRepo = node.path("base").path("repo").path("full_name").asText(repo);
        // A head repository that is blank means the fork was deleted. Treated as a fork, because
        // the one thing that is certain is that it is not this repository's own branch.
        boolean fork = headRepo.isBlank() || !headRepo.equalsIgnoreCase(baseRepo);
        return new Prepared(null, sha, fork, headRepo.isBlank() ? "(deleted fork)" : headRepo);
    }

    /**
     * Fetch the pull request's head and put it in a worktree.
     *
     * <p>Runs in {@code from}, which must be a git checkout of the repository the pull request
     * belongs to — the object store is what makes this cheap, and there is no honest way to build a
     * worktree without one.
     */
    public static Prepared prepare(Path from, String repo, int pr, Prepared described) {
        requireGitRepoFor(from, repo);

        // refs/pull/N/head exists on the base repository for forks too, which is why no remote has
        // to be added and no fork has to be trusted to fetch from.
        git(from, 180, "fetch", "--quiet", "origin", "refs/pull/" + pr + "/head");

        Path dir = DIR.resolve(repo.replace('/', '-') + "-" + BenchLedger.shortSha(described.sha()));
        try {
            Files.createDirectories(DIR);
        } catch (Exception e) {
            throw new Refused("could not make " + DIR + ": " + e.getMessage());
        }
        // Left over from a run that was killed. Removing it is safe: nothing here is a place
        // anybody edits, and the sha in the name says it holds exactly what we are about to make.
        if (Files.exists(dir)) {
            discard(from, dir);
        }
        git(from, 120, "worktree", "add", "--detach", "--quiet", dir.toString(), described.sha());
        return new Prepared(dir, described.sha(), described.fork(), described.headRepo());
    }

    /** Take the worktree away again, whatever happened in it. */
    public static void discard(Path from, Path dir) {
        if (dir == null) {
            return;
        }
        try {
            git(from, 60, "worktree", "remove", "--force", dir.toString());
        } catch (RuntimeException e) {
            // A worktree that will not go is worth saying out loud -- it is disk somebody now owns
            // -- but it is not a reason to fail a build that already ran.
            System.err.println("  (left behind " + dir + ": " + e.getMessage() + ")");
        }
    }

    private static void requireGitRepoFor(Path from, String repo) {
        String top;
        try {
            top = git(from, 30, "rev-parse", "--show-toplevel");
        } catch (RuntimeException e) {
            throw new Refused("this is not a git checkout — run --checkout from a clone of " + repo + ".");
        }
        if (top.isBlank()) {
            throw new Refused("this is not a git checkout — run --checkout from a clone of " + repo + ".");
        }
        String remotes;
        try {
            remotes = git(from, 30, "remote", "-v");
        } catch (RuntimeException e) {
            remotes = "";
        }
        // Compared on owner/name rather than on a whole URL: ssh, https and a personal fork's
        // remote all name the same repository in different words.
        String needle = repo.toLowerCase(java.util.Locale.ROOT);
        if (!remotes.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
            throw new Refused("this checkout has no remote for " + repo
                    + " — run --checkout from a clone of it, or pass --repo for the one you are in.");
        }
    }

    private static String git(Path in, int timeoutSeconds, String... args) {
        List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(argv)
                    .directory(in.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Refused("git " + args[0] + " did not finish within " + timeoutSeconds + "s");
            }
            if (p.exitValue() != 0) {
                throw new Refused("git " + args[0] + " failed: " + out);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused("interrupted");
        } catch (Refused e) {
            throw e;
        } catch (Exception e) {
            throw new Refused("could not run git: " + e.getMessage());
        }
    }
}
