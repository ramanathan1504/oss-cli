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
package com.osscli.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The five commands nothing named.
 *
 * <p>A sweep of every command against every test found {@code guide}, {@code inspect},
 * {@code onboard}, {@code profile} and {@code trend} mentioned by none of them. Each assertion
 * below is a property that has already been got wrong once here — the point is not coverage for
 * its own sake, it is that the failures this repository has already paid for cannot come back
 * quietly.
 */
class UncoveredCommandsTest {

    private static String source(String command) throws IOException {
        return Files.readString(Path.of("src/main/java/com/osscli/cli/" + command + "Command.java"));
    }

    @Test
    @DisplayName("guide offers the cloud flags before it refuses for want of a local model")
    void guideResolvesBothBackends() throws IOException {
        String src = source("Guide");

        // The recorded failure: guide returned before it had read --gemini, the flag that exists
        // precisely to bypass the local model. A capability may degrade; it may not be gated on one
        // provider, and this one was gated on the provider the flag was there to avoid.
        int refusal = src.indexOf("Cloud:  pass --gemini");
        assertTrue(refusal > 0, "guide no longer tells the reader the cloud flags exist");
        for (String flag : List.of("--gemini", "--openai", "--claude")) {
            assertTrue(src.contains(flag), "guide should name " + flag + " as a way through");
        }
    }

    @Test
    @DisplayName("inspect asks whether a local model is there before predicting it will answer")
    void inspectProbesRatherThanAssumes() throws IOException {
        String src = source("Inspect");

        // It printed "Ollama WILL answer locally" from the token count alone, so on a machine with
        // no Ollama -- a supported state, not a broken one -- it described something that could not
        // happen, and prompt built an expert prompt instead.
        assertTrue(src.contains("isServerReachable"), "inspect predicts a local answer without checking");
        int probe = src.indexOf("isServerReachable");
        int claim = src.indexOf("WILL answer locally");
        assertTrue(claim < 0 || probe < claim, "the claim is made before the check");
    }

    @Test
    @DisplayName("onboard reports what to do, not which plugin is configured")
    void onboardSpeaksInInstructions() throws IOException {
        String src = source("Onboard");

        // A newcomer cannot act on "bnd-baseline-maven-plugin". They can act on being told that
        // adding a public class fails the build until the baseline is updated.
        assertTrue(
                src.contains("will not compile") || src.contains("fail the build") || src.contains("enforced"),
                "onboard should say what the rule means, not name the plugin that enforces it");
    }

    @Test
    @DisplayName("profile follows the inherited chain rather than only this repository")
    void profileReadsTheParentChain() throws IOException {
        // Many projects publish their packaging and API rules in a parent artifact rather than
        // committing them, so a profile of the checkout alone reports conventions that are not
        // there and misses the ones that are.
        String chain = Files.readString(Path.of("src/main/java/com/osscli/profile/MavenParentChain.java"));
        String src = source("Profile");

        assertTrue(chain.contains("inherited"), "the chain no longer marks where a rule came from");

        // A stored profile is reused rather than re-fetched, and the message says how to force a
        // re-read: an answer from a cache that does not say it is one is how a stale profile gets
        // trusted for months.
        assertTrue(src.contains("--rebuild"), "profile no longer offers a way to re-read the repository");
        assertTrue(src.contains("Stored profile for"), "profile no longer says the answer came from storage");
    }

    @Test
    @DisplayName("trend names a flag it actually has when it has nothing to show")
    void trendPointsAtARealFlag() throws IOException {
        String src = source("Trend");

        // An empty result is where a tool teaches you the next command, and a message naming a flag
        // the program does not have costs the reader a usage block instead of an answer.
        int told = src.indexOf("trend --save");
        assertTrue(told > 0, "trend no longer says how to create the first snapshot");
        assertTrue(src.contains("\"--save\""), "trend tells the reader to pass --save and has no such flag");
    }
}
