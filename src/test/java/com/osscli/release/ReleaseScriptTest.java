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
package com.osscli.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the release script can still find the release before this one.
 *
 * <p>Nothing else tests {@code release.sh}: it pushes, opens a pull request and merges, so it is
 * run once per release by a person and never by the suite. The step this guards has no such excuse
 * — choosing the previous tag is a line of shell that either names a version or does not.
 *
 * <p>It stopped naming one. The script's last act is to move a {@code stable} tag onto the release
 * it just cut, so {@code stable} and {@code v4.9.7} name the same commit, and an unqualified
 * {@code git describe --tags --abbrev=0} answered {@code stable}. The guard was then asked for the
 * bump between "stable" and "4.9.8" and threw. Every release after the first one to move that tag
 * would have died the same way, after the fetch and before anything was written — and the tag was
 * introduced by this same script, so it broke the next release each time it succeeded.
 */
class ReleaseScriptTest {

    @Test
    @DisplayName("the previous release is looked for among version tags only")
    void previousTagIsAVersion() throws IOException {
        String script = Files.readString(Path.of("release.sh"));

        int at = script.indexOf("PREV_TAG=$(git describe");
        assertTrue(at > 0, "release.sh no longer reads the previous tag; this test guards that line");
        String line = script.substring(at, script.indexOf('\n', at));

        assertTrue(
                line.contains("--match"), "an unqualified describe answers `stable`, which is not a version: " + line);
        assertTrue(line.contains("v[0-9]"), "the match has to select version tags: " + line);
    }

    @Test
    @DisplayName("a main with unpushed commits is refused before anything is written")
    void unpushedCommitsAreRefused() throws IOException {
        String script = Files.readString(Path.of("release.sh"));

        // Squash-merging a release branch cut from a main that is ahead of origin folds those
        // commits into the release commit, and the pull after the merge can then never
        // fast-forward -- v4.10.0 stopped there, merged and untagged.
        int ahead = script.indexOf("git rev-list --count origin/main..HEAD");
        int merge = script.indexOf("gh pr merge");
        assertTrue(ahead > 0, "release.sh no longer checks for commits that were never pushed");
        assertTrue(merge > 0, "release.sh no longer merges; this test guards what happens before it");
        assertTrue(ahead < merge, "the check has to run before the merge, while nothing is written");
    }

    @Test
    @DisplayName("the merge waits for CI on the exact commit, and is confirmed by asking GitHub")
    void mergeIsGatedOnCiAndVerified() throws IOException {
        String script = Files.readString(Path.of("release.sh"));
        int wait = script.indexOf("gh run list --commit \"$HEAD_SHA\" --workflow CI");
        int merge = script.indexOf("gh pr merge");
        int confirmed = script.indexOf("gh pr view \"$PR_NUMBER\" --json state");
        int tag = script.indexOf("git tag -a \"v$VERSION\"");

        // v4.10.3 was merged sixteen seconds after its pull request opened: gh pr checks --watch
        // returned once the checks it could already see had finished, before CI had registered.
        assertTrue(wait > 0 && wait < merge, "the merge must wait for the CI workflow on the release commit");
        assertFalse(script.contains("gh pr checks \"$PR_NUMBER\" --watch"), "the racy watch is back");
        // v4.10.0 stopped merged and untagged because gh exited nonzero after merging.
        assertTrue(confirmed > merge && confirmed < tag, "the merge must be confirmed before tagging");
    }
}
