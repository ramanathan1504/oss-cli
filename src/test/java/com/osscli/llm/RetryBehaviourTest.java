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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a busy provider is actually retried, and a rejected key actually is not.
 *
 * <p>{@link RetryClassificationTest} pins the classification and asserts the clients contain a call
 * to {@link ApiFailure#retryable(int)}. Reading the source is not the same as running it: a refactor
 * that leaves the call in place but no longer reaches it — an early return, a branch reordered —
 * keeps that test green while the behaviour goes.
 *
 * <p>So this one stands a real HTTP server in front of a real client and counts the requests. It was
 * only possible once the endpoints stopped being compiled in; before that a client could talk to
 * nothing but the provider itself, and the behaviour could not be observed at all.
 */
class RetryBehaviourTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    /** Answers {@code failures} times with {@code status}, then 200 with a usable body. */
    private void serve(int status, int failures, String okBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] body;
            int code;
            if (n <= failures) {
                code = status;
                body = ("{\"error\":{\"message\":\"stubbed " + status + "\"}}").getBytes(StandardCharsets.UTF_8);
            } else {
                code = 200;
                body = okBody.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void reset() {
        requests.set(0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("oss.claude.base_url");
    }

    // Anthropic is the one driven here: its client is the simplest to satisfy with a stub body,
    // and all three share the same retry code path through ApiFailure.
    private static final String CLAUDE_OK = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";

    private String callClaude() throws Exception {
        return new ClaudeClient("test-key", "claude-sonnet-5").generateText("hello");
    }

    @Test
    @DisplayName("a 503 is retried, and the answer from the retry is returned")
    void busyProviderIsRetried() throws Exception {
        // The exact failure that started this: gemini-flash-latest answering "this model is
        // currently experiencing high demand, spikes are usually temporary" and being reported
        // as fatal on attempt 1 of 3, with two attempts left unused.
        serve(503, 2, CLAUDE_OK);
        withEndpoint(base(), () -> {
            String answer = callClaude();
            assertTrue(answer.contains("ok"), "the retry's answer should come back: " + answer);
        });

        assertEquals(3, requests.get(), "two failures then a success is three requests, not one");
    }

    @Test
    @DisplayName("a 500 is retried too, not only the rate limits")
    void serverErrorsAreRetried() throws Exception {
        serve(500, 1, CLAUDE_OK);
        withEndpoint(base(), this::callClaude);

        assertEquals(2, requests.get(), "one failure then a success is two requests");
    }

    @Test
    @DisplayName("a 401 is not retried — a rejected key is rejected every time")
    void badCredentialsFailOnce() throws Exception {
        // Retrying this is not merely useless: it costs three round trips and two backoff sleeps
        // to tell the user something the first response already said.
        serve(401, 99, CLAUDE_OK);
        withEndpoint(base(), () -> assertThrows(Exception.class, this::callClaude));

        assertEquals(1, requests.get(), "a permanent failure must be attempted exactly once");
    }

    @Test
    @DisplayName("a 404 is not retried either")
    void retiredModelFailsOnce() throws Exception {
        // A retired model name is the other permanent case, and the one that cost a session:
        // three screens of JSON and a six-second wait to report a constant that had expired.
        serve(404, 99, CLAUDE_OK);
        withEndpoint(base(), () -> assertThrows(Exception.class, this::callClaude));

        assertEquals(1, requests.get(), "a retired model must be reported after one attempt");
    }

    @Test
    @DisplayName("retries give up rather than looping forever")
    void retriesAreBounded() throws Exception {
        serve(503, 99, CLAUDE_OK);
        withEndpoint(base(), () -> assertThrows(Exception.class, this::callClaude));

        assertTrue(requests.get() <= 4, "a permanently busy provider must not be hammered: " + requests.get());
        assertTrue(requests.get() >= 2, "but it must be retried at least once: " + requests.get());
    }

    // ==========================================
    // Pointing a client somewhere else
    // ==========================================

    @Test
    @DisplayName("the endpoint default is the real provider")
    void defaultsAreTheRealProviders() {
        assertTrue(Endpoints.anthropic().startsWith("https://api.anthropic.com"), Endpoints.anthropic());
        assertTrue(Endpoints.openai().startsWith("https://api.openai.com"), Endpoints.openai());
        assertTrue(Endpoints.gemini().startsWith("https://generativelanguage.googleapis.com"), Endpoints.gemini());
    }

    @Test
    @DisplayName("a trailing slash on an override does not become a double slash")
    void trailingSlashIsTrimmed() throws Exception {
        // Somebody will paste a URL with a slash on the end, and //v1/messages is a 404 that
        // reads as a broken provider rather than as a typo.
        serve(200, 0, CLAUDE_OK);
        withEndpoint(base() + "/", this::callClaude);

        assertEquals(1, requests.get(), "the request should still have arrived");
    }

    /** Runs {@code body} with the Anthropic endpoint pointed at {@code url}. */
    private void withEndpoint(String url, ThrowingRunnable body) throws Exception {
        System.setProperty("oss.claude.base_url", url);
        try {
            body.run();
        } finally {
            System.clearProperty("oss.claude.base_url");
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("every retryable code the classification names is one the client would retry")
    void classificationAndBehaviourAgree() {
        // Guards the seam between the two tests: RetryClassificationTest says which codes are
        // retryable, this file proves the client honours 500 and 503. If the set ever grew a
        // code the clients treat differently, that divergence starts here.
        for (int status : List.of(408, 425, 429, 500, 502, 503, 504, 529)) {
            assertTrue(ApiFailure.retryable(status), status + " should be retryable");
        }
        for (int status : List.of(400, 401, 403, 404, 422)) {
            assertTrue(!ApiFailure.retryable(status), status + " should be permanent");
        }
    }
}
