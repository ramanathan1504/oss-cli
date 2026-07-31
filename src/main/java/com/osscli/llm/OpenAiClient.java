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

public class OpenAiClient {
    private static final Logger LOGGER = LogManager.getLogger(OpenAiClient.class);
    private static final String API_URL = "https://api.openai.com/v1/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 3;
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public OpenAiClient(String model) {
        this(com.osscli.util.CredentialManager.getOpenAiKey(), model);
    }

    public OpenAiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model == null || model.isEmpty() ? "gpt-4o-mini" : model;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public String generateText(String prompt) throws IOException, InterruptedException {
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> requestBody = Map.of("model", model, "messages", List.of(message), "temperature", 0.7);

        String jsonPayload = MAPPER.writeValueAsString(requestBody);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(60))
                .build();

        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                LOGGER.info("Sending request to OpenAI (Model: {}, attempt {}/{})...", model, attempt, MAX_RETRIES);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    long waitSeconds = retryAfterSeconds(response, attempt);
                    LOGGER.warn(
                            "OpenAI rate limit hit (429). Waiting {}s before retry {}/{}...",
                            waitSeconds,
                            attempt,
                            MAX_RETRIES);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(waitSeconds * 1000);
                        continue;
                    }
                    lastException =
                            new IOException("OpenAI API failed after " + MAX_RETRIES + " retries: " + response.body());
                    break;
                }

                if (response.statusCode() != 200) {
                    throw new IOException("OpenAI API failed: " + response.body());
                }

                Map<?, ?> responseMap = MAPPER.readValue(response.body(), Map.class);
                List<?> choices = (List<?>) responseMap.get("choices");
                Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
                Map<?, ?> msg = (Map<?, ?>) firstChoice.get("message");
                return (String) msg.get("content");

            } catch (IOException e) {
                lastException = e;
                LOGGER.error("Failed to communicate with OpenAI API: {}", e.getMessage());
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(2000L * attempt);
                }
            }
        }
        throw lastException != null
                ? lastException
                : new IOException("OpenAI request failed after " + MAX_RETRIES + " attempts.");
    }

    /** Reads retry-after or retry-after-ms header; falls back to exponential backoff if absent. */
    private long retryAfterSeconds(HttpResponse<?> response, int attempt) {
        // OpenAI may send retry-after-ms (milliseconds) or retry-after (seconds)
        return response.headers()
                .firstValue("retry-after-ms")
                .map(v -> {
                    try {
                        return Long.parseLong(v) / 1000 + 1;
                    } catch (NumberFormatException e) {
                        return 30L;
                    }
                })
                .orElseGet(() -> response.headers()
                        .firstValue("retry-after")
                        .map(v -> {
                            try {
                                return Long.parseLong(v);
                            } catch (NumberFormatException e) {
                                return 30L;
                            }
                        })
                        .orElse(10L * attempt));
    }
}
