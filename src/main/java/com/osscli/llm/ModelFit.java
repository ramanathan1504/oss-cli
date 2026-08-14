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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Whether a local model will run here, or take the machine down with it.
 *
 * <p>Ollama does not refuse a model that does not fit. It loads it, the operating system swaps, and
 * the whole machine stops responding for minutes — a 7B model on an 8 GB laptop with a browser open
 * measured ten. That is worse than an error: an error can be read, and a frozen laptop cannot even
 * be cancelled.
 *
 * <p>So the size is checked first, against what is actually free, keeping
 * {@link MachineMemory#reserveBytes() a reserve} so the rest of the desktop stays usable. Fitting
 * the model in and making everything else unusable is the same freeze from where the user sits.
 *
 * <p>When it does not fit, the answer names the largest installed model that would. "Too big" is a
 * complaint; "too big, use this one" is an instruction.
 */
public final class ModelFit {

    private static final Logger LOGGER = LogManager.getLogger(ModelFit.class);

    /**
     * Weights are not the whole cost.
     *
     * <p>The KV cache, the context window and the runtime itself all sit alongside them, and they
     * grow with the prompt. Fifteen per cent is deliberately modest: the reserve above is what
     * really protects the machine, and an over-large multiplier here would refuse models that run
     * perfectly well.
     */
    private static final double OVERHEAD = 1.15;

    private ModelFit() {}

    /**
     * The verdict.
     *
     * @param known whether a judgement could be made at all — false means proceed, unchecked
     * @param fits whether the model can run without crowding the machine
     * @param model the model asked about
     * @param needBytes what it is expected to want, weights plus overhead
     * @param memory what the machine has
     * @param alternative the largest installed model that would fit, or null if none would
     */
    public record Verdict(
            boolean known, boolean fits, String model, long needBytes, MachineMemory memory, String alternative) {

        /** True only when we know it does not fit. Unknown is never a refusal. */
        public boolean shouldRefuse() {
            return known && !fits;
        }

        /** The explanation, as lines, ready to print. Empty when there is nothing to say. */
        public List<String> explain() {
            List<String> out = new ArrayList<>();
            if (!known || fits) {
                return out;
            }
            out.add("'" + model + "' needs about " + MachineMemory.human(needBytes) + " and this machine has "
                    + MachineMemory.human(memory.availableBytes()) + " free of "
                    + MachineMemory.human(memory.totalBytes()) + ".");
            out.add("Loading it would swap, and swapping locks the machine up for minutes rather than failing.");
            out.add("  At most half the free memory is used, so " + MachineMemory.human(memory.usableBytes())
                    + " is available to a model and " + MachineMemory.human(memory.reserveBytes())
                    + " is left for everything else you are running.");
            if (alternative != null) {
                out.add("  '" + alternative + "' is installed and fits. Set it with: oss setup");
            } else {
                out.add("  No installed model fits right now. Close something, or pull a smaller one:");
                out.add("    ollama pull qwen2.5:0.5b");
            }
            return out;
        }
    }

    /**
     * Judges the named model against this machine.
     *
     * <p>Never throws, and answers {@code known=false} whenever anything at all could not be
     * determined — the daemon being down, the model not being listed, the memory not being
     * readable. A check that cannot be made must not become a refusal.
     */
    public static Verdict check(OllamaClient client, String model) {
        MachineMemory memory = MachineMemory.read();
        if (!memory.known()) {
            return new Verdict(false, true, model, 0, memory, null);
        }

        List<Installed> installed = installed(client);
        long size = installed.stream()
                .filter(m -> m.name.equals(model) || m.name.startsWith(model + ":"))
                .mapToLong(m -> m.bytes)
                .findFirst()
                .orElse(0L);
        if (size <= 0) {
            // Not installed, or the daemon did not answer. Either way this is not the place to
            // report it -- isModelAvailable() already does, and saying it twice differently is
            // worse than saying it once.
            return new Verdict(false, true, model, 0, memory, null);
        }

        long need = (long) (size * OVERHEAD);
        boolean fits = need <= memory.usableBytes();
        LOGGER.debug(
                "{} needs ~{}, usable {} -> {}",
                model,
                MachineMemory.human(need),
                MachineMemory.human(memory.usableBytes()),
                fits ? "fits" : "does not fit");

        return new Verdict(true, fits, model, need, memory, fits ? null : largestThatFits(installed, memory));
    }

    /** The biggest installed model that would still fit, because the best advice is the least downgrade. */
    private static String largestThatFits(List<Installed> installed, MachineMemory memory) {
        return installed.stream()
                // Embedding models cannot answer a question, so offering one as a replacement for a
                // guidance model would be advice that does not work.
                .filter(m -> !m.name.startsWith("all-minilm"))
                .filter(m -> (long) (m.bytes * OVERHEAD) <= memory.usableBytes())
                .max(Comparator.comparingLong(m -> m.bytes))
                .map(m -> m.name)
                .orElse(null);
    }

    private record Installed(String name, long bytes) {}

    /** Names and sizes from the daemon's own tag list. Empty when it cannot be read. */
    private static List<Installed> installed(OllamaClient client) {
        List<Installed> out = new ArrayList<>();
        try {
            String body = client.tags();
            if (body == null || body.isBlank()) {
                return out;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            for (com.fasterxml.jackson.databind.JsonNode m : root.path("models")) {
                String name = m.path("name").asText(m.path("model").asText(""));
                long size = m.path("size").asLong(0);
                if (!name.isBlank() && size > 0) {
                    out.add(new Installed(name, size));
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not list installed models: {}", e.getMessage());
        }
        return out;
    }
}
