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

import java.io.IOException;

/**
 * Whether an API said "try again" or "no".
 *
 * <p>All three cloud clients grew the same bug independently. Each one correctly singled out 429 as
 * retryable, threw an {@code IOException} for every other non-200, and then caught {@code
 * IOException} in a loop that slept and tried again. So a rejected API key was sent three times, two
 * and four seconds apart, printing the same raw JSON three times — six seconds spent on something
 * that could not possibly succeed, and an error a reader has to parse to understand.
 *
 * <p>Retrying a permanent failure is not merely wasted time. It buries the one line that says what
 * to fix under two identical repetitions of itself, which is how a clear message becomes noise.
 *
 * <p>One class rather than three copies, because three copies is how the bug happened.
 */
public final class ApiFailure {

    private ApiFailure() {}

    /**
     * True when trying again could plausibly work.
     *
     * <p>Rate limits and overload clear on their own; a gateway or a socket may be having a moment.
     * Anything else — a bad key, a model that no longer exists, a malformed request — will fail
     * identically for as long as it is retried.
     */
    public static boolean retryable(int statusCode) {
        return statusCode == 408 // request timeout
                || statusCode == 425 // too early
                || statusCode == 429 // rate limited
                || statusCode == 529 // Anthropic: overloaded
                || statusCode >= 500; // gateway, unavailable, internal
    }

    /**
     * The failure as something a person can act on.
     *
     * @param provider what to call the service on screen, e.g. {@code "Claude"}
     * @param credentialName the environment variable or keychain entry the key came from
     * @param consoleUrl where a working key is obtained
     */
    public static String explain(
            int statusCode, String provider, String body, String credentialName, String consoleUrl) {
        String detail = message(body);
        return switch (statusCode) {
            case 401, 403 ->
                provider + " rejected the key" + (detail.isEmpty() ? "" : ": " + detail) + "\n"
                        + "  The key came from " + credentialName + ".\n"
                        + "  Get one at " + consoleUrl + ", then: oss setup\n"
                        + "  Note: signing in to a desktop app is usually OAuth, not an API key — they are different.";
            case 404 ->
                provider + " has no such model" + (detail.isEmpty() ? "" : ": " + detail) + "\n"
                        + "  Model names are retired over time; the one configured no longer exists.\n"
                        + "  oss setup changes it.";
            case 400 ->
                provider + " rejected the request" + (detail.isEmpty() ? "" : ": " + detail) + "\n"
                        // The hint follows the message, not only the status. Anthropic answers a
                        // billing problem with 400 and a sentence that says so, and the canned
                        // "usually a model name" line was then printed under "your credit balance
                        // is too low" -- sending somebody to change a model that was never wrong.
                        + (looksLikeBilling(detail)
                                ? "  Your account is out of credit. Everything in oss that does not need a"
                                        + " cloud model still works, and oss llm <command> uses a local one."
                                        + cliRoute(provider)
                                : "  This is usually a model name that does not accept the request as sent.");
            case 402 ->
                provider + " reports no available credit" + (detail.isEmpty() ? "" : ": " + detail) + "\n"
                        + "  Everything in oss that does not need a cloud model still works."
                        + cliRoute(provider);
            default -> provider + " returned HTTP " + statusCode + (detail.isEmpty() ? "" : ": " + detail);
        };
    }

    /**
     * The route that still works, when this provider's own tool is installed.
     *
     * <p>Named, not taken. An API out of credit and a logged-in command-line tool are two accounts,
     * and switching between them without being asked would change who paid, which harness ran, and
     * what the tool could read -- while the line the user typed still said something else. Telling
     * them the one keystroke that recovers costs nothing and decides nothing on their behalf.
     *
     * <p>Empty when the tool is not installed: advice that cannot be followed is worse than none,
     * because it reads as a step that was missed.
     */
    private static String cliRoute(String provider) {
        Ai.Engine engine =
                switch (provider.toLowerCase(java.util.Locale.ROOT)) {
                    case "claude" -> Ai.Engine.CLAUDE;
                    case "openai" -> Ai.Engine.OPENAI;
                    case "gemini" -> Ai.Engine.GEMINI;
                    default -> null;
                };
        CliClient.Spec spec = engine == null ? null : CliClient.specFor(engine);
        if (spec == null || !new CliClient(spec, 1).available()) {
            return "";
        }
        return "\n  " + spec.binary() + " is installed — " + engine.typed()
                + " --cli <command> answers on that subscription instead.";
    }

    /** Whether a provider's own words say this is about money rather than about the request. */
    private static boolean looksLikeBilling(String detail) {
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("credit") || lower.contains("billing") || lower.contains("quota");
    }

    /**
     * Pulls the human-readable sentence out of an error body.
     *
     * <p>Every provider nests it differently and all of them wrap it in enough JSON to hide it. This
     * looks for the innermost {@code "message"} and falls back to the whole body, trimmed — a
     * clumsy extraction is better than making the reader find it themselves, and a wrong guess here
     * costs nothing because the status code carries the meaning.
     */
    static String message(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body);
        String found = null;
        while (m.find()) {
            // Last match wins: providers put the outer wrapper first and the useful sentence inside.
            found = m.group(1);
        }
        if (found != null) {
            return found.replace("\\\"", "\"").replace("\\n", " ").trim();
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 299) + "…" : flat;
    }

    /**
     * A failure that will not become a success by being repeated.
     *
     * <p>A distinct type rather than a flag, so the retry loops cannot accidentally swallow it: they
     * catch {@code IOException}, and this is the one they must rethrow untouched.
     */
    public static class Permanent extends IOException {

        private static final long serialVersionUID = 1L;

        private final int statusCode;

        public Permanent(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
