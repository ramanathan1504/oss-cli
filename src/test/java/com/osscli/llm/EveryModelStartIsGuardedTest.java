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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That nothing can start a local model without asking whether it fits.
 *
 * <p>A model that does not fit is not slow, it is a frozen laptop: Ollama loads what it is given,
 * the operating system swaps, and the machine stops answering for minutes. {@link ModelFit} was
 * written for that, and then three of the eight commands that start a model called it while five
 * did not — {@code review}, {@code prompt}, {@code analyze}, {@code onboard} and {@code sync} went
 * straight to the daemon.
 *
 * <p>{@code analyze} is the one that turns a mistake into an ordeal: it runs the model once per
 * issue across the whole backlog, so a model that does not fit is that freeze repeated for every
 * issue in the corpus.
 *
 * <p>The check therefore lives in the client every one of them passes through, and these tests keep
 * it there.
 */
class EveryModelStartIsGuardedTest {

    private static final Path CLIENT = Path.of("src/main/java/com/osscli/llm/OllamaClient.java");

    @Test
    @DisplayName("both ways of generating check the fit first")
    void generateChecksBeforeItLoads() throws IOException {
        String src = Files.readString(CLIENT);

        for (String method : List.of("generateJson", "generateText")) {
            int at = src.indexOf("public String " + method + "(");
            assertTrue(at > 0, method + " is gone; this test needs updating with it");

            String firstLines = src.substring(at, Math.min(src.length(), at + 400));
            assertTrue(
                    firstLines.contains("refuseIfItWillNotFit()"),
                    method + " starts a model without asking whether it fits");
        }
    }

    @Test
    @DisplayName("the refusal is an error, not a warning that scrolls past")
    void itRefusesRatherThanWarns() throws IOException {
        String src = Files.readString(CLIENT);
        int at = src.indexOf("private void refuseIfItWillNotFit()");
        assertTrue(at > 0, "the guard is gone");

        String body = src.substring(at, Math.min(src.length(), at + 1_600));
        // A warning inside a command that then reports success is worse than no warning: the model
        // still loads and the machine still freezes.
        assertTrue(body.contains("throw new ApiFailure.Permanent"), "the guard must refuse, not warn");
        assertTrue(body.contains("fit.explain()"), "a refusal that does not say what to do is a complaint");
    }

    @Test
    @DisplayName("no command reaches the daemon around the client")
    void nothingBypassesTheClient() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/osscli"))) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.equals(CLIENT) || file.getFileName().toString().equals("Endpoints.java")) {
                    continue;
                }
                String text = Files.readString(file);
                // /api/generate is the daemon's own endpoint. Anything calling it directly has gone
                // around the one place that checks memory.
                if (text.contains("/api/generate")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these talk to the daemon without the fit check: " + offenders);
    }
}
