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
        return getKey(new String[] {envVar}, keychainName);
    }

    /**
     * The first of several environment variables that is set, then the keychain.
     *
     * <p>More than one name because a credential can have more than one conventional spelling, and
     * the tool should not be the one insisting. {@code GH_TOKEN} is the GitHub CLI's own variable,
     * so somebody who has authenticated with {@code gh} very likely has it and not
     * {@code GITHUB_TOKEN} -- and {@code oss doctor} already reported that as fine while this method
     * ignored it. A green health check followed by "GitHub Token is missing" on the next command is
     * worse than either answer on its own.
     */
    private static String getKey(String[] envVars, String keychainName) {
        for (String envVar : envVars) {
            String key = System.getenv(envVar);
            if (key != null && !clean(key).isEmpty()) return clean(key);
        }

        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                Process process = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "security find-generic-password -s " + keychainName + " -w 2>/dev/null || true"
                });
                try (java.io.BufferedReader reader =
                        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !clean(line).isEmpty()) return clean(line);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Strips the punctuation a key picks up on its way in.
     *
     * <p>Documentation writes keys as {@code <your-key-here>} and shells quote them, so a key
     * arrives wrapped in angle brackets or quotes often enough to be worth expecting. Sending one
     * verbatim produces a 401, and a 401 says "your key is wrong" -- which is how a perfectly good
     * key with a leading {@code <} costs somebody an afternoon re-issuing credentials that were
     * never the problem. Found exactly that way: a stored Anthropic key beginning {@code <sk-ant-}.
     *
     * <p>Only wrapping characters go. Anything inside the key is left alone, because a key this
     * method has quietly rewritten is worse than one it rejected.
     */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String out = raw.strip();
        while (!out.isEmpty() && "<>\"'`".indexOf(out.charAt(0)) >= 0) {
            out = out.substring(1);
        }
        while (!out.isEmpty() && "<>\"'`".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1);
        }
        return out.strip();
    }

    public static String getGitHubToken() {
        return requireKey(
                getKey(new String[] {"GITHUB_TOKEN", "GH_TOKEN"}, "github_token"), "GitHub Token", "github_token");
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
