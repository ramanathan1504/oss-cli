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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CredentialManager {
    private static final Logger LOGGER = LogManager.getLogger(CredentialManager.class);

    private static String getKey(String envVar, String keychainName) {
        String key = System.getenv(envVar);
        if (key != null && !key.trim().isEmpty()) return key.trim();

        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                Process process = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "security find-generic-password -s " + keychainName + " -w 2>/dev/null || true"
                });
                try (java.io.BufferedReader reader =
                        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty()) return line.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String getGitHubToken() {
        return requireKey(getKey("GITHUB_TOKEN", "github_token"), "GitHub Token", "github_token");
    }

    public static String getGeminiKey() {
        return requireKey(getKey("GEMINI_API_KEY", "gemini_api_key"), "Gemini API Key", "gemini_api_key");
    }

    public static String getOpenAiKey() {
        return requireKey(getKey("OPENAI_API_KEY", "openai_api_key"), "OpenAI API Key", "openai_api_key");
    }

    public static String getClaudeKey() {
        return requireKey(getKey("ANTHROPIC_API_KEY", "anthropic_api_key"), "Anthropic API Key", "anthropic_api_key");
    }

    private static String requireKey(String key, String displayName, String keychainName) {
        if (key == null) {
            String error = String.format("%s is missing. Run 'oss setup' to register it.", displayName);
            LOGGER.error(error);
            throw new RuntimeException(error);
        }
        return key;
    }
}
