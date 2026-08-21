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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * That the advice matches the failure, not the status code it arrived under.
 *
 * <p>Found on a real call. Anthropic answers an empty account with <b>400</b> and the sentence
 * "Your credit balance is too low to access the Anthropic API", and the 400 branch appended its
 * standing hint: <em>this is usually a model name that does not accept the request as sent</em>.
 *
 * <p>So the tool told somebody whose billing had lapsed to go and change a model that was working
 * perfectly. That is worse than saying nothing: it is a confident instruction to fix the wrong
 * thing, printed under the provider's own correct explanation.
 */
class BillingFailureTest {

    private static final String CONSOLE = "https://console.anthropic.com";

    @ParameterizedTest
    @DisplayName("a 400 that is really about money says so, whatever the provider calls it")
    @ValueSource(
            strings = {
                "{\"error\":{\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}",
                "{\"error\":{\"message\":\"You have exceeded your current quota.\"}}",
                "{\"error\":{\"message\":\"Billing is not configured for this account.\"}}"
            })
    void moneyProblemsAreNotModelProblems(String body) {
        String explained = ApiFailure.explain(400, "Claude", body, "anthropic_api_key", CONSOLE);

        assertFalse(
                explained.contains("usually a model name"),
                "told the user to change the model over a billing problem:\n" + explained);
        assertTrue(
                explained.toLowerCase().contains("credit"),
                "the advice must name what is actually wrong:\n" + explained);
        // And the thing that still works, because a lapsed card is not a broken install.
        assertTrue(explained.contains("oss llm"), "must point at the local route:\n" + explained);
    }

    @Test
    @DisplayName("a 400 that is genuinely about the request keeps the advice that fits it")
    void requestProblemsKeepTheirAdvice() {
        String explained = ApiFailure.explain(
                400,
                "Claude",
                "{\"error\":{\"message\":\"max_tokens is too large for this model\"}}",
                "anthropic_api_key",
                CONSOLE);

        // The hint was right for the case it was written for; the bug was applying it to every 400.
        assertTrue(explained.contains("usually a model name"), explained);
    }

    @Test
    @DisplayName("402 still reads as money, since some providers use the status the way it was meant")
    void theStatusForMoneyStillWorks() {
        String explained = ApiFailure.explain(402, "Claude", "{}", "anthropic_api_key", CONSOLE);

        assertTrue(explained.toLowerCase().contains("credit"), explained);
    }

    @Test
    @DisplayName("a rejected key still reads as a key, and names where it came from")
    void authenticationFailuresAreUnchanged() {
        String explained = ApiFailure.explain(401, "Claude", "{}", "anthropic_api_key", CONSOLE);

        assertTrue(explained.contains("anthropic_api_key"), "must name the source of the key: " + explained);
        assertTrue(explained.contains(CONSOLE), explained);
        // The distinction that costs people the most time when it is missing.
        assertTrue(explained.contains("OAuth"), "signing into a desktop app is not an API key: " + explained);
    }
}
