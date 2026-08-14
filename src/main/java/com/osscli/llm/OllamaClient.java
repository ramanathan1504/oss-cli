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
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OllamaClient {

    private static final Logger LOGGER = LogManager.getLogger(OllamaClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * How long to wait for a generation to finish.
     *
     * <p>Generation time scales with context size, model size and hardware, and the spread is
     * enormous: the same 6000-token request that a GPU answers in seconds can take minutes on a
     * laptop CPU. The old fixed 45s was below that range for any sizeable context, so every
     * full-context request failed -- and, because the caller could not tell a timeout from bad
     * output, it was reported as a parse error, pointing diagnosis at the model instead of the
     * clock. Configurable via {@code ollama.timeout_seconds}.
     */
    /**
     * Long enough for a 7B model on a laptop, which is the machine this runs on.
     *
     * <p>It was 300. A 7B guidance model answering a realistic prompt on an M2 Air was measured at
     * <b>482 seconds</b> end to end, 194 of them in prompt evaluation alone -- so the default cut
     * off a request that was working, and did it late enough to look like a hang. Waiting is a
     * choice the user can make; being told at 300 seconds that a correct setup has failed is not.
     *
     * <p>Anyone who would rather fail fast sets {@code ollama.timeout_seconds} lower.
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;

    private final HttpClient httpClient;
    private final String model;
    private final Duration requestTimeout;
    private final String baseUrl;

    public OllamaClient(String model) {
        this(model, resolveTimeout());
    }

    public OllamaClient(String model, Duration requestTimeout) {
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.baseUrl = resolveBaseUrl();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Where the daemon is, from {@code ollama.url}.
     *
     * <p>This used to be the string {@code http://localhost:11434}, written into three request
     * builders. The key was seeded at install, offered by {@code setup} and reported by {@code
     * doctor} -- which pinged the configured address and said "reachable" -- while every actual
     * request went to localhost regardless. Pointing this at a machine with a GPU therefore produced
     * a clean bill of health and a tool that could not reach a model, with nothing connecting the
     * two symptoms.
     *
     * <p>Ollama is external by design: it is a connector you attach for local generation, and
     * attaching it has to include saying where it is.
     */
    private static String resolveBaseUrl() {
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("ollama.url");
            if (configured != null && !configured.isBlank()) {
                String trimmed = configured.trim();
                return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read ollama.url, using default: {}", e.getMessage());
        }
        return com.osscli.Defaults.OLLAMA_URL;
    }

    /** The address in use, so a caller reporting a failure can name the host it actually tried. */
    public String endpoint() {
        return baseUrl;
    }

    private static Duration resolveTimeout() {
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("ollama.timeout_seconds");
            if (configured != null && !configured.isBlank()) {
                int seconds = Integer.parseInt(configured.trim());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not read ollama.timeout_seconds, using default: {}", e.getMessage());
        }
        return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
    }

    public String generateJson(String prompt) throws IOException, InterruptedException {
        Map<String, Object> requestBody = Map.of("model", model, "prompt", prompt, "stream", false, "format", "json");

        String jsonPayload = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(requestTimeout)
                .build();

        try {
            LOGGER.debug("Sending JSON payload to Ollama: {}", jsonPayload);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.error("Ollama API failed with status code {}: {}", response.statusCode(), response.body());
                throw new IOException("Ollama returned unexpected HTTP status: " + response.statusCode());
            }

            Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
            return (String) responseMap.get("response");

        } catch (java.net.http.HttpTimeoutException e) {
            throw explainTimeout(e);
        } catch (IOException e) {
            LOGGER.error("Failed to connect or communicate with Ollama service: {}", e.getMessage());
            throw e;
        }
    }

    // No generateEmbedding here. Embedding is done in-process by
    // com.osscli.retrieval.Embeddings, and leaving a second one reachable through this class is how
    // there came to be two in the first place: one corpus embedded by a daemon, another by the
    // bundled model, and vectors that could not be compared across the two. Generation is what this
    // client is for.

    /**
     * Whether the Ollama daemon answers on its default port. Checked separately from {@link #isModelAvailable()} so a
     * stopped daemon is never reported as a missing model -- "ollama pull" cannot fix a server that is not running.
     */
    public boolean isServerReachable() {
        try {
            return tagList() != null;
        } catch (Exception e) {
            LOGGER.debug("Ollama daemon unreachable: {}", e.getMessage());
            return false;
        }
    }

    public boolean isModelAvailable() {
        try {
            String body = tagList();
            // Safe check to see if the requested model name is present in the local tag list
            return body != null && (body.contains("\"name\":\"" + model) || body.contains("\"model\":\"" + model));
        } catch (Exception e) {
            LOGGER.error("Ollama connection failed while checking model availability: {}", e.getMessage());
            return false;
        }
    }

    /** Returns the raw /api/tags body, or null if the daemon responded with a non-200. */
    private String tagList() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5)) // Fast 5-second connection check
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    /**
     * Says what a timeout actually means, because "request timed out" tells nobody anything.
     *
     * <p>It is almost never a slow machine. It is a prompt the model was never going to finish -- an
     * oversized context, or weights still loading on the first call. A real run of {@code guide}
     * produced exactly this, and what reached the user was that sentence followed by sixteen lines
     * of Java stack trace, which is not a message.
     *
     * <p>One method, called from both request paths. The first attempt at this patched only one of
     * them, and the other went on printing the useless version -- which is what two copies of a fix
     * reliably produce.
     */
    private IOException explainTimeout(java.net.http.HttpTimeoutException cause) {
        long seconds = requestTimeout.toSeconds();
        LOGGER.error("{} did not answer within {}s.", model, seconds);
        LOGGER.error("  Usually one of:");
        LOGGER.error("    · the prompt is larger than the model's context — lower ollama.context_limit,");
        LOGGER.error("      or see what was retrieved with: oss inspect <issue>");
        LOGGER.error("    · the model is loading for the first time — warm it once: ollama run {}", model);
        LOGGER.error("    · a larger model than this machine is comfortable with");
        LOGGER.error("  Or raise the ceiling: ollama.timeout_seconds (currently {}s)", seconds);
        return new IOException(model + " did not answer within " + seconds + "s", cause);
    }

    public String generateText(String prompt) throws IOException, InterruptedException {
        // Standard payload without the "format": "json" constraint
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false);

        String jsonPayload = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(requestTimeout)
                .build();

        try {
            LOGGER.debug("Sending text payload to Ollama: {}", jsonPayload);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.error("Ollama API failed with status code {}: {}", response.statusCode(), response.body());
                throw new IOException("Ollama returned unexpected HTTP status: " + response.statusCode());
            }

            Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
            return (String) responseMap.get("response");

        } catch (java.net.http.HttpTimeoutException e) {
            throw explainTimeout(e);
        } catch (IOException e) {
            LOGGER.error("Failed to connect or communicate with Ollama service: {}", e.getMessage());
            throw e;
        }
    }
}
