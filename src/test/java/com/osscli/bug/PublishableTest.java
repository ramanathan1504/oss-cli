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
package com.osscli.bug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing here that cannot be undone.
 *
 * <p>Everything else this program does is local and reversible. Filing an issue is neither: a public
 * issue can be edited but not unpublished, and it is assembled from the three places a machine keeps
 * its private business -- a command line, a stack trace and a working directory. So the tests are
 * written the way a leak would happen rather than the way the code is organised.
 */
class PublishableTest {

    private static final String HOME = "/Users/someone-real";

    @Test
    @DisplayName("the home directory, and the account name inside it, both go")
    void homeAndAccount() {
        String out = Publishable.text(
                "could not read /Users/someone-real/.oss-cli/store.db (owner someone-real)", HOME, Set.of());

        assertEquals("could not read ~/.oss-cli/store.db (owner someone)", out);
        assertFalse(out.contains("someone-real"), out);
    }

    @Test
    @DisplayName("every credential shape a machine running this could be holding")
    void credentials() {
        // Through com.osscli.util.Redactor, which is the one implementation of this and knows more
        // shapes than a bug reporter would have thought to. Asserted here anyway: what this class
        // promises is that nothing gets out, and "the other class handles it" is a promise that
        // stops being true the moment somebody changes the other class.
        for (String secret : new String[] {
            "ghp_" + "A".repeat(36),
            "gho_" + "B".repeat(36),
            "github_pat_" + "C".repeat(40),
            "AIza" + "D".repeat(35),
            "AKIA" + "E".repeat(16),
            "xoxb-" + "1".repeat(20),
            "sk-" + "F".repeat(48),
            "sk-ant-" + "G".repeat(40)
        }) {
            String out = Publishable.text("passed --key " + secret + " and it failed", HOME, Set.of());
            assertFalse(out.contains(secret), "a key survived: " + out);
            assertTrue(out.contains("[REDACTED:"), out);
        }
    }

    @Test
    @DisplayName("an Authorization header goes even when the token's shape is not one we know")
    void unknownShapedToken() {
        String token = "z".repeat(30);

        String out = Publishable.text("Authorization: Bearer " + token, HOME, Set.of());

        assertFalse(out.contains(token), out);
    }

    @Test
    @DisplayName("a key inside a path is not saved by the path being rewritten around it")
    void secretsBeforePaths() {
        // Order matters and this is why: rewriting the home directory first would leave
        // ~/.config/ghp_… , with the key intact and the check that would have caught it already run.
        String out = Publishable.text(HOME + "/.netrc: ghp_" + "Z".repeat(36), HOME, Set.of());

        assertFalse(out.contains("ghp_Z"), out);
        assertTrue(out.startsWith("~/.netrc"), out);
    }

    @Test
    @DisplayName("the repositories somebody follows are not published by a crash report")
    void repositoryNames() {
        // The rule this enforces is the repository's own: a worked example never names a
        // third-party project, because naming one reads as "this tool is for that project". A crash
        // report filed from a laptop is a worked example that publishes itself.
        String out = Publishable.text(
                "hub failed for apache/logging-log4j2 while reading logging-log4j2 issues",
                HOME,
                Set.of("apache/logging-log4j2"));

        assertEquals("hub failed for owner/name while reading name issues", out);
    }

    @Test
    @DisplayName("a name that contains another name is replaced before its own substring is")
    void longestFirst() {
        // owner/log4j replaced first leaves "owner/name-extras", which still names the project.
        String out = Publishable.text(
                "owner/log4j-extras and owner/log4j", HOME, Set.of("owner/log4j", "owner/log4j-extras"));

        assertEquals("owner/name and owner/name", out);
    }

    @Test
    @DisplayName("a name the text names itself is found, even when the store has never heard of it")
    void namesInTheTextItself() {
        // A journey caught this and no unit test could have: the store's list is empty on exactly
        // the machines that file bug reports -- a fresh install, or one whose store is the fault --
        // and `--repo someorg/their-project` went straight into a public issue.
        assertEquals(
                Set.of("someorg/their-project"), Publishable.namesIn("oss hub --repo someorg/their-project --json"));
        assertEquals(Set.of("someorg/their-project"), Publishable.namesIn("-r someorg/their-project"));
        assertEquals(Set.of("someorg/their-project"), Publishable.namesIn("repo:someorg/their-project is:issue"));
        assertEquals(
                Set.of("someorg/their-project"),
                Publishable.namesIn("cloned https://github.com/someorg/their-project.git yesterday"));
    }

    @Test
    @DisplayName("it does not eat every slash it sees")
    void doesNotOvermatch() {
        // Anchored on the flag or the host, never on the shape. A bare a/b is every relative path
        // and half of every stack trace, and a redactor that eats the evidence gets worked around.
        assertTrue(Publishable.namesIn("at com.osscli.cli.HubCommand.call(HubCommand.java:71)")
                .isEmpty());
        assertTrue(Publishable.namesIn("src/main/java/com/osscli/Main.java").isEmpty());
        assertTrue(Publishable.namesIn("").isEmpty());
    }

    @Test
    @DisplayName("an address is not a diagnostic")
    void email() {
        String out = Publishable.text("git author someone@example.com", HOME, Set.of());

        assertFalse(out.contains("@example.com"), out);
    }

    @Test
    @DisplayName("what is left is still a bug report")
    void doesNotDestroyTheEvidence() {
        // A redactor that took out the useful half would be obeyed once and then worked around.
        String out = Publishable.text(
                "java.lang.IllegalStateException: no rung at com.osscli.agent.Loop.step(Loop.java:88)",
                HOME,
                Set.of("apache/logging-log4j2"));

        assertTrue(out.contains("IllegalStateException"), out);
        assertTrue(out.contains("com.osscli.agent.Loop.step(Loop.java:88)"), out);
    }

    @Test
    @DisplayName("nothing to redact is not an error")
    void empties() {
        assertEquals("", Publishable.text(null, HOME, Set.of()));
        assertEquals("", Publishable.text("   ", HOME, Set.of()));
        assertEquals("plain", Publishable.text("plain", null, null));
        assertFalse(Publishable.changed("a", "a"));
        assertTrue(Publishable.changed("a", "b"));
    }
}
