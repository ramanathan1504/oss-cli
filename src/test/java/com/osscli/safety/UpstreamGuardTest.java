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
package com.osscli.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one thing standing between a model and somebody else's repository.
 *
 * <p>Nothing in oss writes to GitHub today — every call is a read. This exists for the moment
 * something does: a review a model drafted, a comment a workflow wants to post. At that point the
 * question stops being "is the tool careful" and becomes "can this happen without a person
 * deciding", and these are the properties that answer it.
 *
 * <p>The interactive half cannot run here: a test has no {@code System.console()}, so every call
 * reaches the terminal check and refuses. That is itself the most important property in the file —
 * <b>an upstream write is never performed unattended, approved or not</b> — so the tests assert the
 * refusals, which is where the safety actually lives.
 */
class UpstreamGuardTest {

    @ParameterizedTest
    @DisplayName("an approval must name owner and repository, because a bare name is ambiguous")
    @ValueSource(
            strings = {"logging-log4j2", "apache", "", "  ", "apache/", "/logging-log4j2", "https://github.com/a/b"})
    void aBareNameIsNotARepository(String raw) {
        // `logging-log4j2` could be apache's or your fork of it, and those are very different
        // places to post. The slash is what makes an approval name one repository.
        assertThrows(IllegalArgumentException.class, () -> UpstreamGuard.normaliseRepo(raw));
    }

    @ParameterizedTest
    @DisplayName("owner/name is accepted, in the spellings people actually type")
    @ValueSource(strings = {"apache/logging-log4j2", "APACHE/Logging-Log4j2", "  apache/logging-log4j2  ", "a.b/c-d_e"})
    void ownerSlashNameIsAccepted(String raw) {
        // The property is "trimmed and lowercased, otherwise unchanged" -- stated against the input
        // rather than against one hardcoded answer. The first version of this asserted a single
        // expected string for four different inputs and reached it with a .replace(), which is a
        // test agreeing with itself.
        assertEquals(raw.trim().toLowerCase(java.util.Locale.ROOT), UpstreamGuard.normaliseRepo(raw));
    }

    @Test
    @DisplayName("case does not change which repository was approved")
    void approvalIsCaseInsensitive() {
        assertEquals(
                UpstreamGuard.normaliseRepo("apache/logging-log4j2"),
                UpstreamGuard.normaliseRepo("Apache/Logging-Log4J2"));
    }

    @Test
    @DisplayName("a write with no approval is refused")
    void noApprovalIsRefused() {
        assertFalse(UpstreamGuard.allow("devon file", "apache/logging-log4j2", null));
    }

    @Test
    @DisplayName("an approval for one repository is not an approval for another")
    void approvalDoesNotTransfer() {
        // The failure this prevents: approving a write to your own fork, and the verb writing to
        // upstream because both were "approved" in the same run.
        assertFalse(UpstreamGuard.allow("devon file", "apache/logging-log4j2", "ramanathan1504/logging-log4j2"));
    }

    @Test
    @DisplayName("a write whose target is unknown can never be approved")
    void anUnknownTargetIsRefused() {
        // No target means no approval can match it. Prompting anyway would let "approved" drift
        // from meaning one specific repository to meaning "the operator said yes to something".
        assertFalse(UpstreamGuard.allow("devon file", null, "apache/logging-log4j2"));
        assertFalse(UpstreamGuard.allow("devon file", "   ", "apache/logging-log4j2"));
    }

    @Test
    @DisplayName("with no terminal it refuses even when the approval is correct")
    void unattendedIsAlwaysRefused() {
        // This test runs without a console, which is precisely the condition it asserts: cron, CI,
        // a launchd agent and a background task all look like this, and none of them may post.
        assertFalse(
                UpstreamGuard.allow("devon file", "apache/logging-log4j2", "apache/logging-log4j2"),
                "an approved write went through with no terminal to confirm at");
    }

    @Test
    @DisplayName("the flag names itself, so the refusal can be acted on")
    void theFlagIsNamed() {
        assertTrue(UpstreamGuard.APPROVE_FLAG.startsWith("--"), UpstreamGuard.APPROVE_FLAG);
        // The refusal prints this flag followed by the target, and somebody copies that line.
        assertEquals("--approve-upstream", UpstreamGuard.APPROVE_FLAG);
    }
}
