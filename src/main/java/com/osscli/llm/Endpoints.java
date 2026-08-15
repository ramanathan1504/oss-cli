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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Where each provider is, when it is not where it usually is.
 *
 * <p>The three cloud clients had their endpoints compiled in. That is fine until somebody sits
 * behind a gateway — a corporate proxy, an Azure-style deployment, a self-hosted relay — and then
 * the whole provider is simply unreachable with no way to say otherwise.
 *
 * <p>It also made the retry behaviour untestable. A client that can only ever talk to
 * {@code api.openai.com} cannot be shown to retry a 503, so the tests asserted that the source
 * <em>contained</em> a call to {@link ApiFailure#retryable(int)} and hoped. A test that reads the
 * program instead of running it passes through exactly the refactor it exists to catch.
 *
 * <p>Environment first, then stored config, then the real endpoint. Same order as credentials, for
 * the same reason: a variable in the shell is the thing you reach for when you want this run to
 * differ, and it should not require editing anything.
 */
public final class Endpoints {

    private static final Logger LOGGER = LogManager.getLogger(Endpoints.class);

    private Endpoints() {}

    public static String gemini() {
        return resolve(
                "GEMINI_BASE_URL", "gemini.base_url", "https://generativelanguage.googleapis.com/v1beta", "Gemini");
    }

    public static String openai() {
        return resolve("OPENAI_BASE_URL", "openai.base_url", "https://api.openai.com/v1", "OpenAI");
    }

    public static String anthropic() {
        return resolve("ANTHROPIC_BASE_URL", "claude.base_url", "https://api.anthropic.com/v1", "Claude");
    }

    /**
     * The first of environment, config, default that is actually set.
     *
     * <p>Says when an override is in force. A request going somewhere other than the provider's own
     * API is worth one line, because the alternative is diagnosing a redirected client as a broken
     * key — and a trailing slash is stripped so callers can concatenate without thinking about it.
     */
    private static String resolve(String envVar, String configKey, String fallback, String provider) {
        // A JVM property first. It is the only one of the three that can be set for a single run
        // without touching the environment or the database, which is what a test needs and what
        // -D on a command line is for.
        String fromProperty = System.getProperty("oss." + configKey);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return trimSlash(fromProperty.strip());
        }

        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            LOGGER.info("  {} requests go to {} (from {})", provider, fromEnv.strip(), envVar);
            return trimSlash(fromEnv.strip());
        }
        try {
            String stored = com.osscli.storage.SqliteStorage.loadConfig(configKey);
            if (stored != null && !stored.isBlank()) {
                LOGGER.info("  {} requests go to {} (from {})", provider, stored.strip(), configKey);
                return trimSlash(stored.strip());
            }
        } catch (Exception e) {
            // No database yet, or it cannot be read. The default is still correct.
            LOGGER.debug("Could not read {}: {}", configKey, e.getMessage());
        }
        return fallback;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
