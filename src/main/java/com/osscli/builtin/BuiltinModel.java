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
package com.osscli.builtin;

import com.osscli.AppPaths;
import com.osscli.llm.MachineMemory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Where a local model is, and whether this machine can afford to load it.
 *
 * <p><b>No model is bundled, and that is a measured decision rather than an omission.</b> The plan
 * was to ship one inside the install so that naming no engine still got you a generated sentence.
 * A model that fits an 8 GB laptop and a download people will accept means 135M to 360M
 * parameters, so those were built and measured against five real pull requests from this project's
 * own backlog:
 *
 * <table border="1">
 *   <caption>What the candidates actually did</caption>
 *   <tr><th>asked for</th><th>result</th></tr>
 *   <tr><td>one-sentence summary from the title</td><td>invented: "add a new exception type" for a change that adds none</td></tr>
 *   <tr><td>one-sentence summary from the diff</td><td>invented: "reviewed the changelogs" for a change touching none</td></tr>
 *   <tr><td>pick one label of six, raw scores</td><td>1 of 5, and the same label every time</td></tr>
 *   <tr><td>pick one label, prior subtracted</td><td>2 of 5 at 135M, 1 of 5 at 360M</td></tr>
 *   <tr><td>pick one label, four examples given</td><td>2 of 5, a different two</td></tr>
 * </table>
 *
 * <p>Three lines of keyword matching -- {@code docs:} means documentation, {@code Bump} means a
 * dependency, {@code .github/} means CI -- score five of five on the same set and cannot invent
 * anything. Shipping 131 MB to every install to do worse than that, in a tool whose whole argument
 * is that you can tell where an answer came from, would be the one thing this codebase keeps
 * refusing to do.
 *
 * <p>So what ships is the <b>capability</b>: an ONNX decoder that runs in this process with no
 * daemon, no key and no network, pointed at whatever model you give it through
 * {@code OSS_BUILTIN_MODEL}. On a machine with room for a 3B model that is a genuinely useful
 * thing to have; on an 8 GB laptop the honest default is the deterministic path and
 * {@code oss llm}. If a small model appears that passes the table above, this becomes a one-line
 * change -- the loader reads its shape from the graph rather than from constants.
 *
 * <p><b>Presence is not permission.</b> Loading is refused when the machine cannot spare the
 * memory. That is not caution for its own sake: an 8 GB laptop with a browser and an IDE open has
 * a few hundred megabytes of real headroom, and a runtime that takes more does not fail -- it
 * swaps, and the machine stops responding for minutes in a way that cannot be read as an error or
 * cancelled like a command. Refusing is the only answer that cannot be wrong in the dangerous
 * direction.
 */
public final class BuiltinModel {

    /** The name looked for beside the jar, for anyone who drops a model in rather than naming one. */
    public static final String FILE = "model.onnx";

    /**
     * What loading actually costs, measured rather than guessed.
     *
     * <p>For the 131 MB model this was built against: weights, plus ONNX Runtime's arenas, plus the
     * key/value cache that grows with the conversation, measured at just under 250 MB resident for
     * a short generation. The floor sits above that with room for the rest of the program. A larger
     * model needs more, and the guard is deliberately not clever about it: it is a floor, not an
     * estimate, and being refused is cheaper than being right about a machine that then swaps.
     */
    public static final long NEEDS_BYTES = 350L * 1024 * 1024;

    private BuiltinModel() {}

    /**
     * The weights, wherever this build keeps them.
     *
     * <p>Four places, in the order that answers fastest for the person asking. The environment
     * variable is first because it is the only one that can be changed for a single run, which is
     * what a test needs and what somebody trying a different model wants.
     */
    public static Optional<Path> weights() {
        String override = System.getenv("OSS_BUILTIN_MODEL");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        for (Path candidate : candidates()) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<Path> candidates() {
        Path beside = besideTheJar();
        return java.util.Arrays.asList(
                // The shipped layout: <install>/lib/oss.jar and <install>/models/<file>.
                beside == null ? null : beside.getParent().resolve("models").resolve(FILE),
                // Running from a checkout, where target/ stands in for the install.
                Paths.get("target", "models", FILE),
                // A copy the user placed themselves.
                AppPaths.BASE_DIR.resolve("models").resolve(FILE));
    }

    /** The directory holding the running jar, or null when this is not running from one. */
    private static Path besideTheJar() {
        try {
            Path self = Paths.get(BuiltinModel.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Files.isRegularFile(self) ? self.getParent() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** True when the weights are on disk. Says nothing about whether they can be loaded. */
    public static boolean isPresent() {
        return weights().isPresent();
    }

    /** Why the built-in model cannot answer right now, or empty when it can. */
    public static Optional<String> refusal() {
        if (!isPresent()) {
            return Optional.of("no local model is configured — set OSS_BUILTIN_MODEL to an ONNX "
                    + "decoder, or name an engine: oss llm <command>");
        }
        MachineMemory memory = MachineMemory.read();
        if (memory.known() && memory.usableBytes() < NEEDS_BYTES) {
            return Optional.of("not enough free memory to load it safely — "
                    + MachineMemory.human(memory.availableBytes()) + " free, and at most half of that is offered to a "
                    + "model, against the " + MachineMemory.human(NEEDS_BYTES) + " this one needs. "
                    + "Close something, or name an engine: oss llm <command>");
        }
        return Optional.empty();
    }
}
