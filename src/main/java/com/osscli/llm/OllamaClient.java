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

public class OllamaClient {

    private static final Logger LOGGER = LogManager.getLogger(OllamaClient.class);
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String OLLAMA_EMBED_URL = "http://localhost:11434/api/embed";
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
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final HttpClient httpClient;
    private final String model;
    private final Duration requestTimeout;

    public OllamaClient(String model) {
        this(model, resolveTimeout());
    }

    public OllamaClient(String model, Duration requestTimeout) {
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
                .uri(URI.create(OLLAMA_URL))
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

        } catch (IOException e) {
            LOGGER.error("Failed to connect or communicate with Ollama service: {}", e.getMessage());
            throw e;
        }
    }

    public double[] generateEmbedding(String text) throws IOException, InterruptedException {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", text);

        String jsonPayload = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_EMBED_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(requestTimeout)
                .build();

        try {
            LOGGER.debug("Sending embedding request to Ollama for input length: {}", text.length());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.error("Ollama Embed API failed with status code {}: {}", response.statusCode(), response.body());
                throw new IOException("Ollama returned unexpected HTTP status: " + response.statusCode());
            }

            Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
            List<?> embeddingsList = (List<?>) responseMap.get("embeddings");
            if (embeddingsList == null || embeddingsList.isEmpty()) {
                LOGGER.error("No embeddings array returned by Ollama service response.");
                throw new IOException("No embeddings returned by Ollama service");
            }

            List<?> vectorList = (List<?>) embeddingsList.get(0);
            double[] vector = new double[vectorList.size()];
            for (int i = 0; i < vectorList.size(); i++) {
                vector[i] = ((Number) vectorList.get(i)).doubleValue();
            }
            return vector;

        } catch (IOException e) {
            LOGGER.error("Failed to connect or generate vector embedding with Ollama service: {}", e.getMessage());
            throw e;
        }
    }

    public boolean isModelAvailable() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5)) // Fast 5-second connection check
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                // Safe check to see if the requested model name is present in the local tag list
                return body.contains("\"name\":\"" + model) || body.contains("\"model\":\"" + model);
            }
        } catch (Exception e) {
            LOGGER.error("Ollama connection failed while checking model availability: {}", e.getMessage());
        }
        return false;
    }

    public String generateText(String prompt) throws IOException, InterruptedException {
        // Standard payload without the "format": "json" constraint
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false);

        String jsonPayload = MAPPER.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
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

        } catch (IOException e) {
            LOGGER.error("Failed to connect or communicate with Ollama service: {}", e.getMessage());
            throw e;
        }
    }
}
