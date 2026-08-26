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
    // JetBrains Junie. Read from its own --help rather than guessed: a bare task argument is the
    // non-interactive form, and --output-format text is what stops it answering in JSON.
    public static final Spec JUNIE = new Spec("junie", Ai.Engine.JUNIE, true);

    /**
     * Every provider tool, in one place.
     *
     * <p>Because the alternative was a list written by hand in {@code doctor}, and adding Junie
     * proved what that costs: {@code oss --help} offered an engine that the command whose entire
     * job is saying what is reachable did not know existed.
     */
    public static final List<Spec> ALL = List.of(CLAUDE, CODEX, GEMINI, JUNIE);

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
            case JUNIE -> JUNIE;
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
            case JUNIE -> {
                // --task, because -p means --project here and would hand it a directory as the
                // question. Read from its own --help rather than assumed from the others.
                cmd.add("--task");
                cmd.add(prompt);
                // Otherwise it answers JSON, and the reply would be parsed as prose.
                cmd.add("--output-format");
                cmd.add("text");
                // Junie is an agent that runs code tasks and has no read-only switch. What it does
                // have is --project, so it is pointed at an empty directory of ours: it is being
                // asked to judge something it was handed, not to go and edit whatever checkout the
                // terminal happens to be standing in. Same rule as codex's read-only sandbox,
                // enforced with the only flag this tool offers.
                cmd.add("--project");
                cmd.add(workspaceFor(lastMessage).toString());
                cmd.add("--timeout");
                cmd.add(String.valueOf(timeoutSeconds * 1000));
            }
            default -> {
                cmd.add("-p");
                cmd.add(prompt);
            }
        }
        return List.copyOf(cmd);
    }

    /**
     * An empty directory to point an agent at, derived from the reply file so it is predictable.
     *
     * <p>Created beside the temporary file this call already makes, rather than in the working
     * directory, so nothing an agent decides to write lands in somebody's checkout.
     */
    static Path workspaceFor(Path lastMessage) {
        return lastMessage.resolveSibling(lastMessage.getFileName() + "-project");
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
            if (spec.engine() == Ai.Engine.JUNIE) {
                Files.createDirectories(workspaceFor(lastMessage));
            }
            Path binary = resolve();
            List<String> cmd = new ArrayList<>(commandFor(prompt, lastMessage));
            // The resolved file, not the bare name: on Windows ProcessBuilder will not find
            // "claude" when the file is "claude.cmd".
            cmd.set(0, binary == null ? spec.binary() : binary.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();

            // Both pipes drained at once, on their own threads.
            //
            // This read stdout to the end and only then started on stderr, which is a deadlock
            // waiting for a talkative tool: the child blocks writing to a full stderr buffer, the
            // parent blocks reading a stdout that will never close, and neither moves again. It
            // survived because the tools here are quiet on stderr, which is not a guarantee.
            java.util.concurrent.ExecutorService pipes = java.util.concurrent.Executors.newFixedThreadPool(2);
            // stdout is read in chunks rather than in one call, so the bytes arriving can be
            // counted while they arrive. A spinner says "something is happening"; a byte count
            // that climbs says "the answer is being written", which is the difference between
            // waiting and wondering.
            java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.Future<String> stdout = pipes.submit(() -> {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[8192];
                try (java.io.InputStream in = p.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        received.addAndGet(n);
                    }
                }
                return sb.toString();
            });
            java.util.concurrent.Future<String> stderr =
                    pipes.submit(() -> new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            pipes.shutdown();

            // And something on screen while it thinks.
            //
            // The command printed every fact it had, said it was handing the diff to `claude`, and
            // then showed nothing at all for as long as the model took -- minutes, on a
            // twenty-two file change. A terminal that has printed a promise and gone quiet is
            // indistinguishable from one that has hung, and the reader has no way to tell whether
            // to wait or to press ctrl-c.
            String out;
            String err;
            try (com.osscli.ui.Live live =
                    com.osscli.ui.Live.start("asking " + spec.binary() + " — " + prompt.length() + " characters")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
                boolean finished = false;
                while (System.nanoTime() < deadline) {
                    if (p.waitFor(500, TimeUnit.MILLISECONDS)) {
                        finished = true;
                        break;
                    }
                    // Live carries the elapsed time and its own quip; this is what it is doing.
                    long got = received.get();
                    live.step(
                            got == 0
                                    ? spec.binary() + " is reading the diff"
                                    : spec.binary() + " is answering — " + (got / 1024) + " KB so far");
                }
                if (!finished) {
                    p.destroyForcibly();
                    live.fail("no answer within " + timeoutSeconds + "s");
                    throw new ApiFailure.Permanent(0, spec.binary() + " did not answer within " + timeoutSeconds + "s");
                }
                out = stdout.get();
                err = stderr.get();
                live.done(spec.binary() + " answered");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException(spec.binary() + " could not be read: " + e.getMessage(), e);
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
