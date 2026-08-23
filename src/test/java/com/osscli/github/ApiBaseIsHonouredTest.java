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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.journey.FakeGitHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every call goes to the host this install was told to use.
 *
 * <p>{@code getPullRequestFiles} had {@code https://api.github.com} written into it while the other
 * thirteen calls in that class used {@code apiBase()}. On a GitHub Enterprise install -- the case
 * {@code GITHUB_API_URL} exists for -- that one request left for the public API, where the token is
 * not valid and the repository does not exist. The failure surfaces as "could not extract changed
 * files", which reads as a problem with the pull request rather than with the host.
 *
 * <p>Asserted here rather than through a journey. The only caller is {@code sync --me}, behind a
 * configured username and a search for merged pull requests, and reaching it that way took three
 * attempts that each went green with the bug deliberately put back -- a test that proves nothing is
 * worse than no test, because it is believed. This calls the method.
 */
class ApiBaseIsHonouredTest {

    @Test
    @DisplayName("the pull-request files call uses the configured host, not the public API")
    void pullRequestFilesGoesToTheConfiguredHost() throws Exception {
        try (FakeGitHub github = new FakeGitHub()) {
            String previous = System.getProperty("oss.github.api");
            System.setProperty("oss.github.api", github.url());
            try {
                new GitHubClient("ghp_notarealtokenusedonlyintests").getPullRequestFiles("owner", "name", 812);
            } finally {
                if (previous == null) {
                    System.clearProperty("oss.github.api");
                } else {
                    System.setProperty("oss.github.api", previous);
                }
            }

            assertTrue(
                    github.sawPathContaining("/repos/owner/name/pulls/812/files"),
                    "the request went somewhere other than the configured host: " + github.asked());
        }
    }
}
