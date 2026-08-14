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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * That a busy provider is retried and a rejected key is not.
 *
 * <p>All three clients decided this for themselves and all three got it wrong, each differently:
 * Gemini and OpenAI retried only 429, Claude retried 429 and 529. Everything else — including 500,
 * 502 and 503 — was thrown as permanent. So {@code gemini-flash-latest} answering <em>"this model
 * is currently experiencing high demand, spikes are usually temporary"</em> was reported as fatal on
 * attempt 1 of 3, and a model that was merely busy looked exactly like one that had been retired.
 * Observed twice in one session, which is what prompted this.
 *
 * <p>The judgement lives in {@link ApiFailure#retryable(int)} and nowhere else. These tests pin both
 * halves: the classification itself, and the fact that each client actually consults it rather than
 * keeping a fourth opinion.
 */
class RetryClassificationTest {

    // ==========================================
    // The classification
    // ==========================================

    @ParameterizedTest(name = "{0} is worth retrying")
    @ValueSource(ints = {408, 425, 429, 500, 502, 503, 504, 529})
    @DisplayName("temporary failures are retryable")
    void temporaryFailuresRetry(int status) {
        assertTrue(ApiFailure.retryable(status), status + " is temporary and must be retried");
    }

    @ParameterizedTest(name = "{0} is not worth retrying")
    @ValueSource(ints = {400, 401, 403, 404, 422})
    @DisplayName("a request that is wrong is wrong however many times it is sent")
    void permanentFailuresDoNot(int status) {
        // Retrying these is not merely useless: three identical rejections take three round trips
        // and two backoff sleeps to tell the user something the first response already said.
        assertFalse(ApiFailure.retryable(status), status + " will fail identically on every attempt");
    }

    @Test
    @DisplayName("503 specifically — the one that was reported as fatal")
    void theRegression() {
        assertTrue(
                ApiFailure.retryable(503),
                "503 is 'try again later'; treating it as permanent is how a busy model"
                        + " became indistinguishable from a retired one");
    }

    // ==========================================
    // That the clients actually ask
    // ==========================================

    @ParameterizedTest(name = "{0} consults ApiFailure.retryable")
    @ValueSource(strings = {"GeminiClient", "OpenAiClient", "ClaudeClient"})
    @DisplayName("no client keeps its own opinion about what is retryable")
    void everyClientDefersToApiFailure(String client) throws IOException {
        // Asserted against the source rather than by driving a live provider: the failure being
        // guarded against is somebody adding a fourth client, or reverting one of these three to a
        // hand-rolled status check, and neither needs a network to catch.
        String src = Files.readString(Path.of("src/main/java/com/osscli/llm/" + client + ".java"));

        assertTrue(
                src.contains("ApiFailure.retryable(response.statusCode())"),
                client + " decides retryability itself instead of asking ApiFailure");
        assertTrue(
                src.contains("throw new ApiFailure.Permanent"),
                client + " should still fail permanently on what is genuinely permanent");
    }

    @ParameterizedTest(name = "{0} backs off between attempts")
    @ValueSource(strings = {"GeminiClient", "OpenAiClient", "ClaudeClient"})
    @DisplayName("a retry waits, so three attempts are not three simultaneous rejections")
    void everyClientBacksOff(String client) throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/osscli/llm/" + client + ".java"));
        assertTrue(src.contains("BACKOFF_MS"), client + " retries without waiting");
    }
}
