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
package com.osscli.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What must never reach the disk, the database, or a vector.
 *
 * <p>This runs before anything is written or embedded, so a miss is not recoverable by fixing the
 * rule later: the secret is already in the corpus, already in a vector, and possibly already in a
 * prompt sent to a cloud model. The tests are correspondingly blunt — for each rule, the secret must
 * be gone from the output, not merely flagged.
 */
class RedactorTest {

    private static void gone(String secret, String text, String label) {
        Redactor.Result r = Redactor.redact(text);
        assertFalse(r.text().contains(secret), label + ": the secret survived redaction");
        assertTrue(r.redactedAnything(), label + ": nothing was reported as redacted");
        assertTrue(
                r.counts().containsKey(label),
                label + ": tallied as " + r.counts().keySet());
    }

    @Test
    @DisplayName("an AWS access key is removed")
    void awsKey() {
        gone("AKIAIOSFODNN7EXAMPLE", "creds: AKIAIOSFODNN7EXAMPLE trailing", "aws-access-key");
    }

    @Test
    @DisplayName("a GitHub token is removed, in each of its prefixes")
    void githubToken() {
        gone("ghp_" + "a".repeat(36), "token ghp_" + "a".repeat(36), "github-token");
        gone("gho_" + "b".repeat(36), "token gho_" + "b".repeat(36), "github-token");
        gone("github_pat_" + "c".repeat(30), "token github_pat_" + "c".repeat(30), "github-token");
    }

    @Test
    @DisplayName("a Google API key is removed")
    void googleKey() {
        String key = "AIza" + "0123456789012345678901234567890123X".substring(0, 35);
        gone(key, "key=" + key, "google-api-key");
    }

    @Test
    @DisplayName("a Slack token is removed")
    void slackToken() {
        gone("xoxb-1234567890-abcdefghij", "slack xoxb-1234567890-abcdefghij", "slack-token");
    }

    @Test
    @DisplayName("a private key block is removed whole, not line by line")
    void privateKey() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nAQEA\n-----END RSA PRIVATE KEY-----";
        Redactor.Result r = Redactor.redact("here it is:\n" + pem + "\ndone");
        assertFalse(r.text().contains("MIIEow"), "key material survived");
        assertTrue(r.text().contains("done"), "text after the block was lost");
    }

    @Test
    @DisplayName("a password assignment is removed")
    void password() {
        gone("hunter2hunter2", "password=hunter2hunter2", "password");
        gone("hunter2hunter2", "passwd: 'hunter2hunter2'", "password");
    }

    @Test
    @DisplayName("a template reference points at a secret and is not one")
    void templateReferenceKept() {
        Redactor.Result r = Redactor.redact("password=${DB_PASSWORD}");
        assertTrue(r.text().contains("${DB_PASSWORD}"), "a placeholder must survive; it reveals nothing");
        assertFalse(r.redactedAnything());
    }

    @Test
    @DisplayName("a bearer token is removed")
    void bearer() {
        String tok = "abcdefghijklmnopqrstuvwxyz012345";
        gone(tok, "Authorization: Bearer " + tok, "bearer-token");
    }

    @Test
    @DisplayName("a password inside a connection string is removed, and the host kept")
    void jdbcCredentials() {
        Redactor.Result r = Redactor.redact("jdbc:postgresql://appuser:s3cr3tpass@db.internal:5432/prod");
        assertFalse(r.text().contains("s3cr3tpass"), "the password survived");
        assertTrue(r.text().contains("db.internal"), "the host is not a secret and should remain");
    }

    @Test
    @DisplayName("clean text is returned untouched and reports nothing")
    void cleanText() {
        String clean = "The rollover leaves a zero-length file when compression is enabled.";
        Redactor.Result r = Redactor.redact(clean);
        assertEquals(clean, r.text());
        assertFalse(r.redactedAnything());
        assertEquals("", r.summary());
    }

    @Test
    @DisplayName("null and empty are handled rather than thrown at")
    void nullAndEmpty() {
        assertFalse(Redactor.redact(null).redactedAnything());
        assertFalse(Redactor.redact("").redactedAnything());
    }

    @Test
    @DisplayName("redacting twice does not re-report what is already gone")
    void idempotent() {
        String once = Redactor.redact("jdbc:postgresql://appuser:s3cr3tpass@db.internal/prod")
                .text();
        Redactor.Result twice = Redactor.redact(once);
        assertEquals(once, twice.text(), "a second pass changed already-redacted text");
        assertFalse(twice.redactedAnything(), "a marker was counted as a fresh secret: " + twice.summary());
    }

    @Test
    @DisplayName("several distinct secrets are each reported")
    void severalSecrets() {
        Redactor.Result r = Redactor.redact("AKIAIOSFODNN7EXAMPLE and ghp_" + "z".repeat(36));
        assertTrue(r.counts().containsKey("aws-access-key"));
        assertTrue(r.counts().containsKey("github-token"));
        assertTrue(r.summary().contains("aws-access-key"));
    }
}
