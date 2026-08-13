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
package com.osscli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What counts as a reference, and — more importantly — what does not.
 *
 * <p>The precision cases matter more than the recall ones. A missed edge costs a link nobody sees;
 * a wrong edge pulls an unrelated issue into the context of every question about this one, spends
 * the token budget on it, and reports nothing. Most of what follows is therefore about text that
 * looks like a reference and is not.
 */
class ReferencesTest {

    private static final String REPO = "owner/name";

    private static List<String> keys(String text) {
        return References.parse(text, REPO).stream().map(IssueReference::key).collect(Collectors.toList());
    }

    private static IssueReference.Kind kindOf(String text, String key) {
        return References.parse(text, REPO).stream()
                .filter(r -> r.key().equals(key))
                .map(IssueReference::kind)
                .findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("issue references")
    class IssueRefs {

        @Test
        @DisplayName("a bare #123 belongs to the repository it was written in")
        void bareNumber() {
            assertEquals(List.of("owner/name#123"), keys("see #123 for background"));
        }

        @Test
        @DisplayName("owner/name#123 names its own repository, even a different one")
        void qualifiedNumber() {
            assertEquals(List.of("other/project#77"), keys("blocked by other/project#77"));
        }

        @Test
        @DisplayName("several references in one body are all kept, in the order written")
        void severalInOrder() {
            assertEquals(List.of("owner/name#1", "owner/name#2", "owner/name#3"), keys("#1 then #2 and finally #3"));
        }

        @Test
        @DisplayName("the same target twice is one edge, not two")
        void duplicatesCollapse() {
            assertEquals(List.of("owner/name#12"), keys("#12 is related. as noted in #12 above."));
        }

        @Test
        @DisplayName("a number with no hash is not a reference")
        void plainNumber() {
            assertTrue(keys("bumped the timeout to 4100 milliseconds").isEmpty());
        }

        @Test
        @DisplayName("a markdown heading is not a reference")
        void heading() {
            assertTrue(keys("# 4100 is not an issue\n\n## 12 neither").isEmpty());
        }

        @Test
        @DisplayName("a URL fragment is not a reference")
        void urlFragment() {
            assertTrue(keys("see https://example.invalid/docs/page#123 for the anchor")
                    .isEmpty());
        }

        @Test
        @DisplayName("an HTML entity is not a reference")
        void htmlEntity() {
            assertTrue(keys("the separator is &#123; in the template").isEmpty());
        }

        @Test
        @DisplayName("an absurd number is not an issue")
        void absurdNumber() {
            assertTrue(keys("error code #123456789012").isEmpty());
        }
    }

    @Nested
    @DisplayName("closing keywords")
    class Closes {

        @Test
        @DisplayName("fixes #N is stronger than a mention, because the author said so")
        void fixes() {
            assertEquals(IssueReference.Kind.CLOSES, kindOf("fixes #4100", "owner/name#4100"));
        }

        @Test
        @DisplayName("every spelling GitHub accepts is accepted here")
        void allSpellings() {
            for (String word : new String[] {
                "close", "closes", "closed", "fix", "fixes", "fixed", "resolve", "resolves", "resolved"
            }) {
                assertEquals(
                        IssueReference.Kind.CLOSES,
                        kindOf(word + " #9", "owner/name#9"),
                        word + " should record a CLOSES edge");
            }
        }

        @Test
        @DisplayName("the keyword is case-insensitive, as people actually type it")
        void caseInsensitive() {
            assertEquals(IssueReference.Kind.CLOSES, kindOf("Fixes #9", "owner/name#9"));
            assertEquals(IssueReference.Kind.CLOSES, kindOf("FIXES #9", "owner/name#9"));
        }

        @Test
        @DisplayName("a colon between keyword and issue is still a claim to fix")
        void withColon() {
            assertEquals(IssueReference.Kind.CLOSES, kindOf("Fixes: #9", "owner/name#9"));
        }

        @Test
        @DisplayName("a closing keyword can name another repository")
        void crossRepoClose() {
            assertEquals(IssueReference.Kind.CLOSES, kindOf("fixes other/project#5", "other/project#5"));
        }

        @Test
        @DisplayName("without a keyword it is only a mention")
        void bareIsMention() {
            assertEquals(IssueReference.Kind.MENTIONS, kindOf("related to #9", "owner/name#9"));
        }

        @Test
        @DisplayName("mentioned once and claimed once stays the claim")
        void claimWins() {
            assertEquals(IssueReference.Kind.CLOSES, kindOf("fixes #9. see also #9 for history.", "owner/name#9"));
        }
    }

    @Nested
    @DisplayName("code is not prose")
    class CodeStripping {

        @Test
        @DisplayName("a fenced block cannot create an edge")
        void fenced() {
            String body = "Broken since:\n\n```\n  at Foo.bar(Foo.java:42) #123\n  channel #456 closed\n```\n";
            assertTrue(keys(body).isEmpty(), "references inside fenced code must be ignored");
        }

        @Test
        @DisplayName("a tilde-fenced block cannot either")
        void tildeFenced() {
            assertTrue(keys("~~~\nlog: retry #7\n~~~").isEmpty());
        }

        @Test
        @DisplayName("inline code cannot create an edge")
        void inlineCode() {
            assertTrue(keys("the literal `#42` is a comment marker").isEmpty());
        }

        @Test
        @DisplayName("prose around a fenced block still counts")
        void proseSurvivesAroundCode() {
            String body = "Fixes #10.\n\n```\nnoise #999\n```\n\nAlso see #11.";
            List<String> found = keys(body);
            assertTrue(found.contains("owner/name#10"));
            assertTrue(found.contains("owner/name#11"));
            assertFalse(found.contains("owner/name#999"), "the fenced number must not survive");
        }
    }

    @Nested
    @DisplayName("commits")
    class Commits {

        private static final String SHA = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

        @Test
        @DisplayName("a full forty-character hash is unambiguous enough on its own")
        void fullSha() {
            assertEquals(List.of("sha:" + SHA), keys("regressed in " + SHA));
        }

        @Test
        @DisplayName("a commit URL names a commit however short the hash")
        void commitUrl() {
            assertEquals(List.of("sha:abc1234"), keys("https://github.invalid/owner/name/commit/abc1234 did it"));
        }

        @Test
        @DisplayName("the literal word commit names one too")
        void commitWord() {
            assertEquals(List.of("sha:abc1234"), keys("introduced by commit abc1234"));
        }

        @Test
        @DisplayName("a bare short hash is indistinguishable from a hundred other things")
        void bareShortHexIgnored() {
            assertTrue(keys("the value was deadbeef when it failed").isEmpty());
        }

        @Test
        @DisplayName("a commit is recorded with no issue number")
        void commitHasNoNumber() {
            IssueReference ref = References.parse("commit " + SHA, REPO).get(0);
            assertEquals(IssueReference.Kind.COMMIT, ref.kind());
            assertEquals(0, ref.toNumber());
            assertEquals(SHA, ref.toSha());
        }

        @Test
        @DisplayName("hashes are recorded lower case, so one commit is one edge")
        void shaLowercased() {
            assertEquals(List.of("sha:" + SHA), keys("commit " + SHA.toUpperCase()));
        }
    }

    @Nested
    @DisplayName("bounds and bad input")
    class Bounds {

        @Test
        @DisplayName("null and blank bodies produce nothing rather than failing")
        void nullAndBlank() {
            assertTrue(References.parse(null, REPO).isEmpty());
            assertTrue(References.parse("", REPO).isEmpty());
            assertTrue(References.parse("   \n  ", REPO).isEmpty());
        }

        @Test
        @DisplayName("no repository means no way to resolve a bare number, so nothing is guessed")
        void nullRepository() {
            assertTrue(References.parse("#123", null).isEmpty());
        }

        @Test
        @DisplayName("one body cannot drag unlimited issues into a prompt")
        void capped() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 200; i++) {
                sb.append('#').append(i).append(' ');
            }
            assertTrue(References.parse(sb.toString(), REPO).size() <= 40);
        }

        @Test
        @DisplayName("#0 is not an issue number")
        void zero() {
            assertTrue(keys("see #0").isEmpty());
        }
    }
}
