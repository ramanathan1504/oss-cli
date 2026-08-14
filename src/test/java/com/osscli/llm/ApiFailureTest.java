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
package com.osscli.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * That a failure which cannot succeed is not sent again.
 *
 * <p>The bodies below are the real ones, copied from a live run: Anthropic rejecting a key that had
 * come from a desktop-app login rather than the console, and Google reporting a model it had
 * retired. Both were previously retried three times, two and four seconds apart, printing the same
 * JSON each time.
 */
class ApiFailureTest {

    /** Verbatim from a 1.11.2 run against api.anthropic.com. */
    private static final String CLAUDE_401 = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
            + "\"message\":\"invalid x-api-key\"},\"request_id\":\"req_011Ce28UpZSjBYEX5gUf4psw\"}";

    /** Verbatim from a 1.11.2 run against generativelanguage.googleapis.com. */
    private static final String GEMINI_404 =
            "{\n \"error\": {\n  \"code\": 404,\n  \"message\": \"This model models/gemini-2.0-flash is no "
                    + "longer available. Please update your code to use a newer model.\",\n  "
                    + "\"status\": \"NOT_FOUND\"\n }\n}";

    @Nested
    @DisplayName("what may be retried")
    class Retryable {

        @Test
        @DisplayName("rate limits and overload clear on their own")
        void transientAreRetried() {
            assertTrue(ApiFailure.retryable(429), "rate limited");
            assertTrue(ApiFailure.retryable(529), "Anthropic overloaded");
            assertTrue(ApiFailure.retryable(408), "request timeout");
        }

        @Test
        @DisplayName("a server having a moment is worth another try")
        void serverErrorsAreRetried() {
            assertTrue(ApiFailure.retryable(500));
            assertTrue(ApiFailure.retryable(502));
            assertTrue(ApiFailure.retryable(503));
            assertTrue(ApiFailure.retryable(504));
        }

        @Test
        @DisplayName("a rejected key never becomes accepted")
        void authIsNotRetried() {
            assertFalse(ApiFailure.retryable(401), "the exact case that wasted six seconds");
            assertFalse(ApiFailure.retryable(403));
        }

        @Test
        @DisplayName("a retired model does not come back")
        void notFoundIsNotRetried() {
            assertFalse(ApiFailure.retryable(404));
        }

        @Test
        @DisplayName("a malformed request stays malformed, and no credit is no credit")
        void otherClientErrorsAreNotRetried() {
            assertFalse(ApiFailure.retryable(400));
            assertFalse(ApiFailure.retryable(402));
        }

        @Test
        @DisplayName("success is not a failure")
        void successIsNotRetryable() {
            assertFalse(ApiFailure.retryable(200));
        }
    }

    @Nested
    @DisplayName("what the user is told")
    class Explanations {

        @Test
        @DisplayName("a rejected key names where the key came from and where to get one")
        void authExplainsItself() {
            String out = ApiFailure.explain(
                    401, "Claude", CLAUDE_401, "the anthropic_api_key keychain entry", "console.anthropic.com");

            assertTrue(out.contains("invalid x-api-key"), "the provider's own words are kept: " + out);
            assertTrue(out.contains("anthropic_api_key"), "says which credential was used: " + out);
            assertTrue(out.contains("console.anthropic.com"), "says where to get a working one: " + out);
            assertTrue(out.contains("oss setup"), "says how to store it: " + out);
            assertTrue(out.contains("OAuth"), "warns that a desktop login is a different credential: " + out);
        }

        @Test
        @DisplayName("a retired model says so, rather than reprinting JSON")
        void notFoundExplainsItself() {
            String out = ApiFailure.explain(404, "Gemini", GEMINI_404, "GEMINI_API_KEY", "aistudio.google.com/apikey");

            assertTrue(out.contains("no such model"), out);
            assertTrue(out.contains("no longer available"), "the provider's sentence survives: " + out);
            assertTrue(out.contains("oss setup"), "says how to change it: " + out);
            assertFalse(out.contains("\"status\""), "the raw JSON envelope is not shown: " + out);
        }

        @Test
        @DisplayName("an unmapped status still says something specific")
        void unknownStatusIsStillUseful() {
            String out = ApiFailure.explain(418, "OpenAI", "{\"message\":\"teapot\"}", "OPENAI_API_KEY", "example");
            assertTrue(out.contains("418"));
            assertTrue(out.contains("teapot"));
        }

        @Test
        @DisplayName("an empty body does not produce a dangling colon")
        void emptyBodyIsHandled() {
            String out = ApiFailure.explain(401, "Claude", "", "KEY", "console");
            assertTrue(out.startsWith("Claude rejected the key"), out);
            assertFalse(out.contains(": \n"), "no empty detail is appended: " + out);
        }
    }

    @Nested
    @DisplayName("digging the sentence out of the envelope")
    class MessageExtraction {

        @Test
        @DisplayName("the innermost message wins, not the wrapper")
        void innermostMessage() {
            assertEquals("invalid x-api-key", ApiFailure.message(CLAUDE_401));
        }

        @Test
        @DisplayName("newlines in the envelope do not survive into a one-line message")
        void multilineBody() {
            String out = ApiFailure.message(GEMINI_404);
            assertTrue(out.startsWith("This model models/gemini-2.0-flash is no longer available"), out);
            assertFalse(out.contains("\n"), "must stay one line: " + out);
        }

        @Test
        @DisplayName("a body with no message field falls back to the body itself")
        void noMessageField() {
            assertEquals("something went wrong", ApiFailure.message("something went wrong"));
        }

        @Test
        @DisplayName("null and blank bodies are empty, not the word null")
        void nullBody() {
            assertEquals("", ApiFailure.message(null));
            assertEquals("", ApiFailure.message("   "));
        }

        @Test
        @DisplayName("a very long body is clipped rather than filling the terminal")
        void longBodyIsClipped() {
            String out = ApiFailure.message("x".repeat(5000));
            assertTrue(out.length() <= 300, "was " + out.length());
            assertTrue(out.endsWith("…"));
        }

        @Test
        @DisplayName("escaped quotes inside the message are unescaped")
        void escapedQuotes() {
            assertEquals("the \"model\" is gone", ApiFailure.message("{\"message\":\"the \\\"model\\\" is gone\"}"));
        }
    }

    @Nested
    @DisplayName("the exception the retry loops must not swallow")
    class PermanentException {

        @Test
        @DisplayName("it carries its status code")
        void carriesStatus() {
            ApiFailure.Permanent e = new ApiFailure.Permanent(401, "nope");
            assertEquals(401, e.statusCode());
            assertEquals("nope", e.getMessage());
        }

        @Test
        @DisplayName("it is an IOException, so existing signatures still hold")
        void isAnIoException() {
            assertTrue(new ApiFailure.Permanent(404, "gone") instanceof java.io.IOException);
        }

        @Test
        @DisplayName("catching IOException would swallow it, which is why the clients catch it first")
        void wouldBeSwallowed() {
            // This is the shape of the bug: a loop that catches IOException and sleeps will retry a
            // Permanent unless it is caught ahead of it. The clients each do that; this records why.
            boolean caughtAsGenericIo = false;
            try {
                throw new ApiFailure.Permanent(401, "invalid key");
            } catch (java.io.IOException e) {
                caughtAsGenericIo = true;
            }
            assertTrue(caughtAsGenericIo, "a generic catch does match it — so it must be caught before one");
        }
    }
}
