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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.model.Issue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a search says when it returned a page rather than an answer.
 *
 * <p>The search sent no {@code per_page} and read one page, so it returned <b>thirty</b> results
 * whatever matched, and reported those thirty as the finding. A harvest of {@code involves:}
 * collected thirty items out of 1,218; a personal profile built from
 * {@code author:… type:pr is:merged} was built from thirty pull requests.
 *
 * <p>Neither said so. A truncation that does not announce itself is worse than a small number,
 * because the reader acts on it as a total.
 */
class SearchPaginationTest {

    private static Issue at(int n) {
        return new Issue(
                n, "t", "b", "open", 0, null, null, null, List.of(), null, null, "https://github.com/o/n/pull/" + n);
    }

    @Test
    @DisplayName("a result that is a page of the answer knows it")
    void truncationIsVisible() {
        GitHubClient.Found partial = new GitHubClient.Found(List.of(at(1), at(2)), 1_218);

        assertTrue(partial.truncated());
        assertEquals(1_218, partial.totalAvailable());
    }

    @Test
    @DisplayName("a complete result does not claim to be truncated")
    void completeIsNotTruncated() {
        GitHubClient.Found whole = new GitHubClient.Found(List.of(at(1), at(2)), 2);

        assertFalse(whole.truncated());
        // Nothing found at all is complete, not truncated: there was nothing to cut.
        assertFalse(new GitHubClient.Found(List.of(), 0).truncated());
    }

    @Test
    @DisplayName("the search asks for pages, and asks for full ones")
    void theRequestIsPaged() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/github/GitHubClient.java"));
        int at = src.indexOf("public Found search(");
        assertTrue(at > 0, "the paginating search is gone");

        String body = src.substring(at, Math.min(src.length(), at + 1_400));
        assertTrue(body.contains("per_page=100"), "a search that asks for the default gets thirty");
        assertTrue(body.contains("page=") && body.contains("for ("), "one page is not the answer");
        assertTrue(body.contains("total_count"), "without the total, truncation cannot be reported");
    }

    @Test
    @DisplayName("nothing that harvests or profiles reads only the first page")
    void callersUseThePagingOne() throws IOException {
        for (Path p : List.of(
                Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"),
                Path.of("src/main/java/com/osscli/cli/SyncCommand.java"))) {
            String src = Files.readString(p);
            assertFalse(
                    src.contains("searchIssuesAndPrs("),
                    p.getFileName() + " still reads one page and reports it as the total");
        }
    }
}
