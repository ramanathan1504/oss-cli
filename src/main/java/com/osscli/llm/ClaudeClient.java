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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ClaudeClient {
    private static final Logger LOGGER = LogManager.getLogger(ClaudeClient.class);

    private static String apiUrl() {
        return Endpoints.anthropic() + "/messages";
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;

    /** Backoff base, multiplied by the attempt number. */
    private static final long BACKOFF_MS = 1_500L;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null || model.isEmpty() ? "claude-3-5-haiku-20241022" : model;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public ClaudeClient(String model) {
        this(com.osscli.util.CredentialManager.getClaudeKey(), model);
    }

    public String generateText(String prompt) throws IOException, InterruptedException {
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> requestBody = Map.of("model", model, "max_tokens", 4096, "messages", List.of(message));

        String jsonPayload = MAPPER.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl()))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(60))
                .build();

        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                LOGGER.info(
                        "Sending request to Anthropic Claude (Model: {}, attempt {}/{})...",
                        model,
                        attempt,
                        MAX_RETRIES);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // 429 = rate limit, 529 = API overloaded — both are retryable
                if (response.statusCode() == 429 || response.statusCode() == 529) {
                    long waitSeconds = retryAfterSeconds(response, attempt);
                    LOGGER.warn(
                            "Claude API returned {} (rate limit/overload). Waiting {}s before retry {}/{}...",
                            response.statusCode(),
                            waitSeconds,
                            attempt,
                            MAX_RETRIES);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(waitSeconds * 1000);
                        continue;
                    }
                    lastException = new IOException(
                            "Anthropic API failed after " + MAX_RETRIES + " retries: " + response.body());
                    break;
                }

                if (response.statusCode() != 200) {
                    String explained = ApiFailure.explain(
                            response.statusCode(),
                            "Claude",
                            response.body(),
                            "ANTHROPIC_API_KEY or the anthropic_api_key keychain entry",
                            "console.anthropic.com");
                    // "Anything not already handled above" was 429 and 529 only, so a 500 or a 503
                    // -- both temporary -- failed like a rejected key. The judgement belongs in one
                    // place, and ApiFailure is it.
                    if (ApiFailure.retryable(response.statusCode()) && attempt < MAX_RETRIES) {
                        LOGGER.warn(
                                "Claude returned {}; retrying ({}/{})...", response.statusCode(), attempt, MAX_RETRIES);
                        Thread.sleep(BACKOFF_MS * attempt);
                        continue;
                    }
                    throw new ApiFailure.Permanent(response.statusCode(), explained);
                }

                Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
                List<?> content = (List<?>) responseMap.get("content");
                Map<?, ?> firstBlock = (Map<?, ?>) content.get(0);
                return (String) firstBlock.get("text");

            } catch (ApiFailure.Permanent e) {
                // Straight out. Retrying prints the same message twice more and buries the one line
                // that says what to fix under two copies of itself.
                LOGGER.error("{}", e.getMessage());
                throw e;
            } catch (IOException e) {
                lastException = e;
                LOGGER.error("Failed to communicate with Claude API: {}", e.getMessage());
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw lastException != null
                ? lastException
                : new IOException("Claude request failed after " + MAX_RETRIES + " attempts.");
    }

    /** Reads retry-after header; falls back to exponential backoff if absent. */
    private long retryAfterSeconds(HttpResponse<?> response, int attempt) {
        return response.headers()
                .firstValue("retry-after")
                .map(v -> {
                    try {
                        return Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return 30L;
                    }
                })
                .orElse(10L * attempt); // 10s, 20s, 30s
    }
}
