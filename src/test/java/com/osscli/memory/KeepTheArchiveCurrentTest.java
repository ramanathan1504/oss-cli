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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.knowledge.Curriculum;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That the daily job keeps every page it writes current, without doubling or thinning any of them.
 *
 * <p>Harvest was the only writer on a schedule, so contributions and the curriculum went stale in
 * the order nobody remembered to run them. Running them daily is only safe if a daily run cannot
 * file a second copy of a note, cannot replace a note that has its review with one that does not,
 * and cannot rewrite five hundred unchanged pages every morning.
 */
class KeepTheArchiveCurrentTest {

    @Test
    @DisplayName("checkouts are read from kb.json, and a ~ means the home directory")
    void checkoutsAreConfigured() throws IOException {
        KnowledgePack pack = KnowledgePack.parse(new ObjectMapper()
                .readTree("{\"archive\":\"/tmp/archive\",\"checkouts\":[\"~/src/owner-name\",\"\",\"/abs/other\"]}"));

        assertEquals(
                List.of(Path.of(System.getProperty("user.home") + "/src/owner-name"), Path.of("/abs/other")),
                pack.checkouts());
        // Nothing is guessed: no key, no checkouts.
        assertTrue(KnowledgePack.parse(new ObjectMapper().readTree("{}"))
                .checkouts()
                .isEmpty());
    }

    @Test
    @DisplayName("a pull request filed under two topics is found as one change with two notes")
    void contributionsAreFoundByChange(@TempDir Path projects) throws IOException {
        String note = "---\ntitle: t\ntopic: %s\nproject: owner/name\nkind: contribution\npr: 4111\n---\n\n# t\n";
        for (String topic : List.of("java-concurrency", "log4j")) {
            Path folder = projects.resolve(topic).resolve("contributions");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("2026-05-21-pr-4111-t.md"), note.formatted(topic), StandardCharsets.UTF_8);
        }

        Map<String, List<Path>> byChange = BuiltinMemory.contributionNotesByPr(projects);

        // The topic scorer moved and the same change was filed a second time under a second
        // subject. Found by project and number, both copies are one change.
        assertEquals(1, byChange.size(), byChange.toString());
        assertEquals(2, byChange.get("owner/name#4111").size());
    }

    @Test
    @DisplayName("changes that name no pull request are never grouped together")
    void noPullRequestIsNoIdentity(@TempDir Path projects) throws IOException {
        Path folder = projects.resolve("log4j").resolve("contributions");
        Files.createDirectories(folder);
        for (String name : List.of("a", "b")) {
            Files.writeString(
                    folder.resolve("2026-01-01-pr-0-" + name + ".md"),
                    "---\nproject: owner/name\npr: 0\n---\n\n# " + name + "\n",
                    StandardCharsets.UTF_8);
        }

        // "#0" shared by every change without a number would make the first overwrite the rest.
        assertFalse(BuiltinMemory.contributionNotesByPr(projects).containsKey("owner/name#0"));
    }

    @Test
    @DisplayName("a checkout sees what was merged upstream after it was last fetched")
    void checkoutsAreFetchedBeforeFiling(@TempDir Path dir) throws Exception {
        Path origin = dir.resolve("origin.git");
        Path upstream = dir.resolve("upstream");
        Path checkout = dir.resolve("checkout");
        git(dir, "init", "--quiet", "--bare", "-b", "main", origin.toString());
        git(dir, "clone", "--quiet", origin.toString(), upstream.toString());
        commit(upstream, "first");
        git(upstream, "push", "--quiet", "origin", "HEAD:main");
        git(dir, "clone", "--quiet", origin.toString(), checkout.toString());

        // Merged upstream after the checkout last fetched: invisible to origin/main until a fetch.
        commit(upstream, "merged this morning");
        git(upstream, "push", "--quiet", "origin", "HEAD:main");
        String before = git(checkout, "rev-parse", "origin/main");

        assertEquals("", com.osscli.knowledge.Contributions.fetch(checkout));

        String after = git(checkout, "rev-parse", "origin/main");
        assertFalse(before.equals(after), "origin/main did not move, so the job would file yesterday's work");
        // Only the remote-tracking ref moved: the working tree and local branch are the person's.
        assertEquals(before, git(checkout, "rev-parse", "HEAD"));
    }

    @Test
    @DisplayName("a checkout that cannot be fetched says why instead of hanging")
    void anUnreachableRemoteIsReported(@TempDir Path dir) throws Exception {
        Path checkout = dir.resolve("checkout");
        git(dir, "init", "--quiet", "-b", "main", checkout.toString());
        git(
                checkout,
                "remote",
                "add",
                "origin",
                dir.resolve("does-not-exist.git").toString());

        String why = com.osscli.knowledge.Contributions.fetch(checkout);

        assertFalse(why.isEmpty(), "a fetch that failed reported success");
    }

    private static void commit(Path repo, String message) throws Exception {
        Files.writeString(repo.resolve("file.txt"), message + "\n", StandardCharsets.UTF_8);
        git(repo, "add", "file.txt");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "--quiet", "-m", message);
    }

    private static String git(Path in, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(in.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + ": " + out);
        return out;
    }

    @Test
    @DisplayName("an unchanged curriculum page is not rewritten")
    void curriculumLeavesUnchangedPagesAlone(@TempDir Path archive) throws IOException {
        Curriculum.Item item = new Curriculum.Item("log4j", "Appenders", "backlog", 12, 40, "note.md");
        Curriculum.write(archive, item, List.of());
        Path page;
        try (var walk = Files.walk(archive)) {
            page = walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        FileTime before = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(page, before);

        Curriculum.write(archive, item, List.of());

        // The daily job runs this. The same bytes written every morning are a sync client and an
        // indexer each re-reading 506 pages to learn that nothing moved.
        assertEquals(before, Files.getLastModifiedTime(page));
    }
}
