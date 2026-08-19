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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The model that ships in the box, running in this process.
 *
 * <p>No daemon, no key, no network, nothing to install afterwards. It is small on purpose -- 135M
 * parameters, quantised to int8, 131 MB -- because the constraint that matters is the machine it
 * has to run on: an 8 GB laptop with a browser and an IDE already open. A larger model would write
 * better sentences on a workstation and take that laptop down, and a tool that takes the laptop
 * down is not a smaller version of a useful tool.
 *
 * <p><b>What it is for.</b> Bounded work with a shape: a one-line summary of text that has already
 * been retrieved, a label from a fixed set, a title. It is not for judgement, and nothing here
 * pretends otherwise -- {@code oss llm} and the cloud prefixes exist for that, and every caller
 * says which rung answered. A 135M model asked for a code review produces fluent, confident and
 * wrong, which is worse than silence.
 *
 * <h2>Why it decodes the way it does</h2>
 *
 * <p>The prompt is fed in <b>chunks</b> rather than in one pass. The graph returns logits for every
 * position it is given -- 49152 floats each -- so a 1000-token prompt in one pass asks for 200 MB
 * of output buffer on a machine chosen for having none to spare. In 64-token slices it asks for 12,
 * the key/value cache carries the context forward exactly as it would have, and only the last
 * slice's final position is ever read.
 *
 * <p>Threads are capped for the same reason the async logger no longer spins: this runs on battery.
 */
public final class BuiltinGenerator implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(BuiltinGenerator.class);

    /** Positions per prefill pass. Trades a few milliseconds for not allocating a logits wall. */
    private static final int PREFILL_CHUNK = 64;

    /** ChatML, which is what this model was instruction-tuned on. */
    private static final String IM_START = "<|im_start|>";

    private static final String IM_END = "<|im_end|>";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BpeTokenizer tokenizer;
    private final int eosId;

    /**
     * The model's shape, read from the graph rather than written down.
     *
     * <p>These were constants copied out of a config.json: three numbers that have to agree with a
     * file nobody looks at again. Wrong ones do not fail -- the cache is the wrong shape, the model
     * attends to noise, and the answer is fluent nonsense. The session already knows all three, so
     * it is asked, and swapping the bundled model for a different one becomes a change of file
     * rather than a change of source.
     */
    private final int layers;

    private final int kvHeads;
    private final int headDim;

    private BuiltinGenerator(OrtEnvironment env, OrtSession session, BpeTokenizer tokenizer) throws OrtException {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.eosId = tokenizer.specialId(IM_END);

        int found = 0;
        for (String name : session.getInputNames()) {
            if (name.startsWith("past_key_values.") && name.endsWith(".key")) {
                found++;
            }
        }
        this.layers = found;
        long[] shape = ((ai.onnxruntime.TensorInfo)
                        session.getInputInfo().get("past_key_values.0.key").getInfo())
                .getShape();
        // [batch, kv_heads, past_sequence, head_dim] -- the two fixed dimensions are the ones worth
        // reading; the others are -1 because they vary per call.
        this.kvHeads = (int) shape[1];
        this.headDim = (int) shape[3];
        LOGGER.debug("built-in model: {} layers, {} kv heads, head dim {}", layers, kvHeads, headDim);
    }

    /**
     * Load the model, or say why not.
     *
     * <p>The refusal is checked before the session is created rather than after: once ONNX Runtime
     * has begun mapping weights on a machine without the memory, the damage is already done.
     */
    public static BuiltinGenerator open() throws IOException {
        String refusal = BuiltinModel.refusal().orElse(null);
        if (refusal != null) {
            throw new IOException(refusal);
        }
        Path weights = BuiltinModel.weights().orElseThrow(() -> new IOException("the built-in model is not present"));
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            // Two threads, not every core. This is a laptop tool: the difference between two
            // threads and eight is a few seconds on a short generation and the fans either way.
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            OrtSession session = env.createSession(weights.toString(), options);
            return new BuiltinGenerator(env, session, BpeTokenizer.forModel(weights));
        } catch (OrtException e) {
            throw new IOException("the built-in model would not load: " + e.getMessage(), e);
        }
    }

    /**
     * Pick one of a fixed set of answers.
     *
     * <p>The important property is what it <em>cannot</em> do. Free generation from a model this
     * size produces fluent, confident and wrong -- asked what a diff changes, it answered "add a
     * new exception type" for a change that adds no type, and "I've reviewed the changelogs" for a
     * change that touches no changelog. Neither reads as a small model struggling; both read as the
     * tool lying.
     *
     * <p>So the model is never asked to write here. The prompt is run once and the returned
     * distribution is consulted for the first token of each permitted answer; the highest wins.
     * The result is always one of {@code options}, chosen deterministically, and the worst failure
     * available is the wrong label rather than an invented fact. That is a size of claim a 135M
     * model can carry.
     *
     * @return the chosen option, or empty when the options cannot be told apart by their first token
     */
    public java.util.Optional<String> choose(String system, String question, List<String> options) throws IOException {
        // Scored after a primed "Kind:", so the options carry the space that follows it. Byte-level
        // BPE makes " bug" and "bug" different tokens, and which is right depends on what precedes.
        Map<Integer, String> byFirstToken = new LinkedHashMap<>();
        for (String option : options) {
            int[] ids = tokenizer.encode(" " + option);
            if (ids.length == 0 || byFirstToken.putIfAbsent(ids[0], option) != null) {
                return java.util.Optional.empty();
            }
        }

        float[] answered = scoreAt(system, question, options);
        // The same question with nothing to answer it. Whatever the model says here is what it
        // believes before reading anything -- its prior -- and the prior is enormous: " bug" scored
        // 2 to 8 points above every other label in every context tested, swamping the one-point
        // spread that actually came from the input. Ranking raw logits therefore measures which
        // word is commonest, and returns the same answer for every pull request. Subtracting the
        // blank run leaves only what the content moved, which is the thing being asked about.
        float[] prior = scoreAt(system, "", options);

        String best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        int i = 0;
        for (Map.Entry<Integer, String> e : byFirstToken.entrySet()) {
            float calibrated = answered[i] - prior[i];
            if (calibrated > bestScore) {
                bestScore = calibrated;
                best = e.getValue();
            }
            i++;
        }
        return java.util.Optional.ofNullable(best);
    }

    /** The logit of each option's first token, given this question. */
    private float[] scoreAt(String system, String question, List<String> options) throws IOException {
        StringBuilder prompt = new StringBuilder();
        prompt.append(IM_START).append("system\n").append(system).append(IM_END).append('\n');
        prompt.append(IM_START).append("user\n").append(question).append('\n');
        prompt.append("Answer with exactly one of: ")
                .append(String.join(", ", options))
                .append('.');
        prompt.append(IM_END).append('\n');
        // The assistant's turn is opened AND started. Left at "assistant\n" the model does what it
        // was trained to do, which is begin a sentence: the next token is "The", and every label is
        // then scored in a position where none of them belongs.
        prompt.append(IM_START).append("assistant\nKind:");

        int[] promptIds = tokenizer.encode(prompt.toString());
        Map<String, OnnxTensor> past = emptyPast();
        try {
            float[] logits = null;
            for (int at = 0; at < promptIds.length; at += PREFILL_CHUNK) {
                int end = Math.min(at + PREFILL_CHUNK, promptIds.length);
                logits = step(java.util.Arrays.copyOfRange(promptIds, at, end), at, past);
            }
            float[] out = new float[options.size()];
            for (int i = 0; i < options.size(); i++) {
                out[i] = logits[tokenizer.encode(" " + options.get(i))[0]];
            }
            return out;
        } catch (OrtException e) {
            throw new IOException("the built-in model failed while choosing: " + e.getMessage(), e);
        } finally {
            closeAll(past);
        }
    }

    /** One instruction, one answer. The system line is what keeps a small model on the rails. */
    public String answer(String system, String user, int maxNewTokens) throws IOException {
        StringBuilder prompt = new StringBuilder();
        if (system != null && !system.isBlank()) {
            prompt.append(IM_START)
                    .append("system\n")
                    .append(system)
                    .append(IM_END)
                    .append('\n');
        }
        prompt.append(IM_START).append("user\n").append(user).append(IM_END).append('\n');
        prompt.append(IM_START).append("assistant\n");
        return generate(prompt.toString(), maxNewTokens);
    }

    /**
     * Greedy decoding, with a repetition penalty.
     *
     * <p>Greedy rather than sampled because every use here has a right answer rather than a
     * preferred style, and a temperature that makes prose livelier also makes a label wrong. The
     * penalty is the one concession: small models loop, and a loop costs the whole budget.
     */
    public String generate(String prompt, int maxNewTokens) throws IOException {
        int[] promptIds = tokenizer.encode(prompt);
        List<Integer> generated = new ArrayList<>();
        Map<String, OnnxTensor> past = emptyPast();
        long start = System.nanoTime();

        try {
            float[] lastLogits = null;
            for (int at = 0; at < promptIds.length; at += PREFILL_CHUNK) {
                int end = Math.min(at + PREFILL_CHUNK, promptIds.length);
                int[] slice = java.util.Arrays.copyOfRange(promptIds, at, end);
                lastLogits = step(slice, at, past);
            }

            for (int i = 0; i < maxNewTokens; i++) {
                int next = pick(lastLogits, promptIds, generated);
                if (next == eosId) {
                    break;
                }
                generated.add(next);
                lastLogits = step(new int[] {next}, promptIds.length + generated.size() - 1, past);
            }
        } catch (OrtException e) {
            throw new IOException("the built-in model failed mid-answer: " + e.getMessage(), e);
        } finally {
            closeAll(past);
        }

        int[] ids = new int[generated.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = generated.get(i);
        }
        LOGGER.debug(
                "built-in model: {} prompt tokens, {} generated, {} ms",
                promptIds.length,
                ids.length,
                (System.nanoTime() - start) / 1_000_000);
        return tokenizer.decode(ids, true).trim();
    }

    /**
     * One forward pass. Returns the logits of the final position and rolls the cache forward.
     *
     * <p>{@code pastLength} is how much context the cache already holds; the attention mask has to
     * cover that as well as the new tokens, or the model attends to nothing it has already read.
     */
    private float[] step(int[] tokens, int pastLength, Map<String, OnnxTensor> past) throws OrtException {
        Map<String, OnnxTensor> inputs = new HashMap<>(past);
        long[][] ids = new long[1][tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            ids[0][i] = tokens[i];
        }
        long[][] mask = new long[1][pastLength + tokens.length];
        java.util.Arrays.fill(mask[0], 1L);

        // Where these tokens sit in the whole sequence. The rotary embedding takes it as an input
        // rather than deriving it, so a decode step that passes 0 every time places every generated
        // token at the start of the text and the answer degenerates within a sentence.
        long[][] positions = new long[1][tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            positions[0][i] = pastLength + i;
        }

        OnnxTensor idTensor = OnnxTensor.createTensor(env, ids);
        OnnxTensor maskTensor = OnnxTensor.createTensor(env, mask);
        OnnxTensor positionTensor = OnnxTensor.createTensor(env, positions);
        inputs.put("input_ids", idTensor);
        inputs.put("attention_mask", maskTensor);
        inputs.put("position_ids", positionTensor);

        try (OrtSession.Result out = session.run(inputs)) {
            // Read through the buffer rather than getValue(). The logits of one 64-token slice are
            // three million floats; materialising them as a Java float[][][] allocates twelve
            // megabytes per pass to look at the last row of it.
            OnnxTensor logitTensor = (OnnxTensor) out.get("logits").orElseThrow();
            long[] shape = logitTensor.getInfo().getShape();
            int vocab = (int) shape[2];
            int rows = (int) shape[1];
            FloatBuffer logits = logitTensor.getFloatBuffer();
            float[] last = new float[vocab];
            logits.position((rows - 1) * vocab);
            logits.get(last);

            // The cache the model just produced replaces the one it was given. Copied out because
            // the Result owns its tensors and frees them when it closes.
            Map<String, OnnxTensor> next = new LinkedHashMap<>();
            for (int l = 0; l < layers; l++) {
                next.put("past_key_values." + l + ".key", copyOf(out, "present." + l + ".key"));
                next.put("past_key_values." + l + ".value", copyOf(out, "present." + l + ".value"));
            }
            closeAll(past);
            past.clear();
            past.putAll(next);
            return last;
        } finally {
            idTensor.close();
            maskTensor.close();
            positionTensor.close();
        }
    }

    /**
     * Take one cache tensor out of a result that is about to be freed.
     *
     * <p>Copied through its buffer for the same reason the logits are read through theirs, and
     * because the {@code Result} owns what it returns: keeping a reference past the try-with is a
     * use-after-free, and the JVM does not save you from one that happened in native code.
     */
    private OnnxTensor copyOf(OrtSession.Result out, String name) throws OrtException {
        OnnxTensor source = (OnnxTensor) out.get(name).orElseThrow();
        long[] shape = source.getInfo().getShape();
        FloatBuffer from = source.getFloatBuffer();
        FloatBuffer copy = FloatBuffer.allocate(from.remaining());
        copy.put(from);
        copy.rewind();
        return OnnxTensor.createTensor(env, copy, shape);
    }

    /** An empty cache: the same shapes with no positions in them, which is what a first pass wants. */
    private Map<String, OnnxTensor> emptyPast() throws IOException {
        Map<String, OnnxTensor> past = new LinkedHashMap<>();
        try {
            for (int l = 0; l < layers; l++) {
                past.put("past_key_values." + l + ".key", zeros());
                past.put("past_key_values." + l + ".value", zeros());
            }
        } catch (OrtException e) {
            throw new IOException("could not prepare the model's cache: " + e.getMessage(), e);
        }
        return past;
    }

    /**
     * A cache with the right shape and no positions in it.
     *
     * <p>Built from a buffer and an explicit shape because a Java array cannot express this: the
     * runtime reads the dimensions off the array, and {@code new float[1][3][0][64]} is rejected
     * as having a zero dimension. The shape is the whole content of an empty cache.
     */
    private OnnxTensor zeros() throws OrtException {
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[] {1, kvHeads, 0, headDim});
    }

    /**
     * The next token: the highest scoring one, after penalising what has already been said.
     *
     * <p>1.1 is the model's own configured repetition penalty. Applied to the prompt as well as the
     * output, which matters here: asked to summarise, a small model will otherwise echo its input
     * back word for word and call it a summary.
     */
    private int pick(float[] logits, int[] promptIds, List<Integer> generated) {
        boolean[] seen = new boolean[logits.length];
        for (int id : promptIds) {
            if (id < seen.length) {
                seen[id] = true;
            }
        }
        for (int id : generated) {
            if (id < seen.length) {
                seen[id] = true;
            }
        }
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            float score = logits[i];
            if (seen[i]) {
                score = score > 0 ? score / 1.1f : score * 1.1f;
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static void closeAll(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor t : tensors.values()) {
            t.close();
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            LOGGER.debug("closing the built-in model: {}", e.getMessage());
        }
    }
}
