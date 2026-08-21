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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A provider's own command-line tool, used as an engine.
 *
 * <p>Each of these is already installed and already logged in on the machine of anybody who uses
 * it, and it bills against the subscription they are paying for rather than against API credit.
 * That is the whole reason it exists here: an account with no API credit is not a broken install,
 * and {@code oss claude review} failing on billing while the same person has a working
 * {@code claude} on their PATH is a dead end that did not need to be one.
 *
 * <p><b>One implementation, three configurations.</b> The alternative is a client per provider, and
 * this package already has the cautionary tale: {@link ApiFailure} exists because three cloud
 * clients each grew the same retry bug independently. Three copies is how that happened; this is
 * deliberately not a fourth, fifth and sixth.
 *
 * <p>Every one of these is an <b>agent harness</b>, not a completion endpoint — it can read files
 * and run commands if allowed to. So each is invoked in the most constrained way its own flags
 * offer, and never in the repository being reviewed. A verdict is worth less than the guarantee
 * that asking for one changed nothing.
 */
public final class CliClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How to drive one provider's tool headlessly, and how to read what it returns. */
    public record Spec(String binary, Ai.Engine engine, boolean verified) {}

    public static final Spec CLAUDE = new Spec("claude", Ai.Engine.CLAUDE, true);
    public static final Spec CODEX = new Spec("codex", Ai.Engine.OPENAI, true);
    // Not installed on the machine this was written on, so the invocation is documented rather than
    // demonstrated. It says so in doctor rather than presenting an unrun guess as a working route.
    public static final Spec GEMINI = new Spec("gemini", Ai.Engine.GEMINI, false);

    private final Spec spec;
    private final long timeoutSeconds;

    public CliClient(Spec spec, long timeoutSeconds) {
        this.spec = spec;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static Spec specFor(Ai.Engine engine) {
        return switch (engine) {
            case CLAUDE -> CLAUDE;
            case OPENAI -> CODEX;
            case GEMINI -> GEMINI;
            // Ollama is a daemon this already speaks to over HTTP, and the built-in model runs in
            // this process. Neither has a command-line tool to stand in front of.
            default -> null;
        };
    }

    /**
     * The names this tool could have on disk.
     *
     * <p>On Windows an executable on the PATH is {@code claude.cmd} or {@code claude.exe}; there is
     * no extensionless file to find. Looking only for the bare name reports "not on your PATH" on a
     * machine where the tool is installed and working -- and the advice that follows sends somebody
     * to install what they already have.
     */
    private static List<String> candidateNames(String binary) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        return windows ? List.of(binary + ".cmd", binary + ".exe", binary + ".bat", binary) : List.of(binary);
    }

    /** Whether the tool is on the PATH at all. */
    public boolean available() {
        return resolve() != null;
    }

    /** Where the tool is, or null when it is not on the PATH. */
    private Path resolve() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String name : candidateNames(spec.binary())) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * The command that asks this tool one question and gets one answer back.
     *
     * <p>Kept as a value so it can be read without running it — the same reason the autostart
     * definitions are values. What a tool is told is the part worth checking, and on three tools
     * only one of which is installed here, running is not a way to check it.
     */
    public List<String> commandFor(String prompt, Path lastMessage) {
        List<String> cmd = new ArrayList<>(List.of(spec.binary()));
        switch (spec.engine()) {
            case CLAUDE -> {
                cmd.add("-p");
                cmd.add(prompt);
                cmd.add("--output-format");
                cmd.add("json");
            }
            case OPENAI -> {
                cmd.add("exec");
                // Read-only, and not asked to reason about the checkout it happens to be standing
                // in: this is being asked to judge a diff it was handed, not to go exploring.
                cmd.add("--sandbox");
                cmd.add("read-only");
                cmd.add("--skip-git-repo-check");
                cmd.add("-o");
                cmd.add(lastMessage.toString());
                cmd.add(prompt);
            }
            default -> {
                cmd.add("-p");
                cmd.add(prompt);
            }
        }
        return List.copyOf(cmd);
    }

    /**
     * The answer, or an {@link ApiFailure.Permanent} naming what to fix.
     *
     * <p>Nothing here is retried. A missing binary, a logged-out tool and a refused subscription all
     * fail identically however many times they are asked, and this package's own history is that
     * retrying such a thing buries the line that says what to fix under copies of itself.
     */
    public String generateText(String prompt) throws IOException {
        if (!available()) {
            // Status 0: there was no HTTP exchange to have a status. Permanent because a missing
            // binary fails identically however many times it is asked.
            throw new ApiFailure.Permanent(
                    0, spec.binary() + " is not on your PATH — install it, or drop --cli to use the API");
        }
        Path lastMessage = Files.createTempFile("oss-cli-" + spec.binary(), ".txt");
        try {
            Path binary = resolve();
            List<String> cmd = new ArrayList<>(commandFor(prompt, lastMessage));
            // The resolved file, not the bare name: on Windows ProcessBuilder will not find
            // "claude" when the file is "claude.cmd".
            cmd.set(0, binary == null ? spec.binary() : binary.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ApiFailure.Permanent(0, spec.binary() + " did not answer within " + timeoutSeconds + "s");
            }
            if (p.exitValue() != 0) {
                throw new ApiFailure.Permanent(
                        0,
                        spec.binary() + " exited " + p.exitValue() + " — "
                                + firstMeaningfulLine(err.isBlank() ? out : err));
            }
            return extract(out, lastMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(spec.binary() + " was interrupted", e);
        } finally {
            Files.deleteIfExists(lastMessage);
        }
    }

    /** The answer out of whatever envelope the tool wraps it in. */
    String extract(String stdout, Path lastMessage) throws IOException {
        if (spec.engine() == Ai.Engine.CLAUDE) {
            JsonNode node = MAPPER.readTree(stdout);
            // is_error is the tool reporting a failure in a process that exited 0, which is exactly
            // the shape that gets mistaken for an answer.
            if (node.path("is_error").asBoolean(false)) {
                throw new ApiFailure.Permanent(
                        0, "claude reported an error — " + node.path("result").asText(""));
            }
            return node.path("result").asText("");
        }
        if (spec.engine() == Ai.Engine.OPENAI && Files.exists(lastMessage)) {
            String written = Files.readString(lastMessage);
            if (!written.isBlank()) {
                return written;
            }
        }
        return stdout;
    }

    private static String firstMeaningfulLine(String text) {
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "no output";
    }
}
