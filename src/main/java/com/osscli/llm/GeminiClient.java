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

public class GeminiClient {

    private static final Logger LOGGER = LogManager.getLogger(GeminiClient.class);
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public GeminiClient(String model) {
        this(com.osscli.util.CredentialManager.getGeminiKey(), model);
    }

    // Default model: gemini-2.0-flash has 1M tokens/min free tier (4x more than gemini-2.5-flash's 250K)
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";
    private static final int MAX_RETRIES = 3;

    public GeminiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null || model.isEmpty() ? DEFAULT_MODEL : model;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public String generateText(String prompt) throws IOException, InterruptedException {
        String url = String.format(GEMINI_URL, model, apiKey);

        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        String jsonPayload = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(60))
                .build();

        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                LOGGER.info(
                        "Sending prompt to Google Gemini API (Model: {}, attempt {}/{})...",
                        model,
                        attempt,
                        MAX_RETRIES);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    long retryAfterSeconds = parseRetryAfter(response.body());
                    LOGGER.warn(
                            "Gemini rate limit hit (429). Waiting {}s before retry {}/{}...",
                            retryAfterSeconds,
                            attempt,
                            MAX_RETRIES);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(retryAfterSeconds * 1000);
                        continue;
                    }
                    lastException =
                            new IOException("Gemini returned unexpected HTTP status: 429 (quota exhausted after "
                                    + MAX_RETRIES + " retries)");
                    break;
                }

                if (response.statusCode() != 200) {
                    LOGGER.error("Gemini API failed with status code {}: {}", response.statusCode(), response.body());
                    throw new IOException("Gemini returned unexpected HTTP status: " + response.statusCode());
                }

                return parseGeminiResponse(response.body());

            } catch (IOException e) {
                lastException = e;
                LOGGER.error("Failed to connect or communicate with Gemini API: {}", e.getMessage());
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(2000L * attempt); // exponential backoff for network errors
                }
            }
        }
        throw lastException != null
                ? lastException
                : new IOException("Gemini request failed after " + MAX_RETRIES + " attempts.");
    }

    /** Extracts retryDelay seconds from a 429 response body, defaults to 35s if not parseable. */
    private long parseRetryAfter(String responseBody) {
        try {
            Map<?, ?> errorMap = MAPPER.readValue(responseBody, Map.class);
            Map<?, ?> error = (Map<?, ?>) errorMap.get("error");
            if (error != null) {
                List<?> details = (List<?>) error.get("details");
                if (details != null) {
                    for (Object detail : details) {
                        Map<?, ?> detailMap = (Map<?, ?>) detail;
                        Object retryDelay = detailMap.get("retryDelay");
                        if (retryDelay != null) {
                            String delayStr =
                                    retryDelay.toString().replace("s", "").trim();
                            return (long) Math.ceil(Double.parseDouble(delayStr));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 35; // safe default
    }

    private String parseGeminiResponse(String responseBody) throws IOException {
        Map<?, ?> responseMap = MAPPER.readValue(responseBody, Map.class);
        List<?> candidates = (List<?>) responseMap.get("candidates");
        if (candidates != null && !candidates.isEmpty()) {
            Map<?, ?> firstCandidate = (Map<?, ?>) candidates.get(0);
            Map<?, ?> contentMap = (Map<?, ?>) firstCandidate.get("content");
            List<?> parts = (List<?>) contentMap.get("parts");
            if (parts != null && !parts.isEmpty()) {
                Map<?, ?> firstPart = (Map<?, ?>) parts.get(0);
                return (String) firstPart.get("text");
            }
        }
        throw new IOException("Failed to parse expected Gemini API response structure.");
    }
}
