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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-level BPE, the tokenizer the built-in model was trained with.
 *
 * <p>Written out rather than pulled in: the JVM has no byte-level BPE in its standard library, and
 * the libraries that do have one arrive with a native component per platform, which is the opposite
 * of what a self-contained install needs. It is about two hundred lines, and every one of them is
 * decided by the model's own {@code tokenizer.json} rather than invented here.
 *
 * <p>Three stages, in the order the file declares them:
 *
 * <ol>
 *   <li><b>Added tokens</b> are matched first and whole. {@code <|im_start|>} is one id; letting BPE
 *       see it would spell it out in six pieces and the model would not recognise its own chat
 *       markers.
 *   <li><b>Pre-tokenisation</b> splits the remaining text: digits individually, then the GPT-2
 *       pattern that keeps a leading space attached to the word after it. That space is why
 *       {@code " the"} and {@code "the"} are different tokens, and why a tokenizer that drops it
 *       produces text the model has never seen.
 *   <li><b>Byte level and merges.</b> Every byte becomes a printable character through a fixed
 *       table, so any input is representable and none of it is lost; then the merge list is applied
 *       lowest rank first until nothing merges.
 * </ol>
 *
 * <p>Decoding runs the table backwards. It is exact: {@code decode(encode(s))} returns {@code s}
 * for any string, which is the property {@code BpeTokenizerTest} pins, because a tokenizer that is
 * subtly wrong does not fail -- it produces text that reads like a model having a bad day.
 */
public final class BpeTokenizer {

    /** Where the vocabulary lives in the jar. Two megabytes, and the only copy. */
    private static final String RESOURCE = "/models/smollm2-tokenizer.json";

    /**
     * GPT-2's pre-tokenisation pattern.
     *
     * <p>Ported from the ByteLevel pre-tokenizer the model declares. The leading {@code ?} spaces
     * are deliberate: a space belongs to the token that follows it.
     */
    private static final Pattern PIECES =
            Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");

    /** Digits are split one by one before anything else, so "2024" is four tokens, not one. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d");

    private final Map<String, Integer> vocab;
    private final String[] idToToken;
    private final Map<String, Integer> merges;
    private final Map<String, Integer> addedTokens;
    private final Pattern addedPattern;
    private final char[] byteToChar = new char[256];
    private final Map<Character, Integer> charToByte = new HashMap<>();

    private static BpeTokenizer shared;

    /** The one instance of the built-in vocabulary. Parsing two megabytes twice gains nothing. */
    public static synchronized BpeTokenizer shared() throws IOException {
        if (shared == null) {
            shared = load();
        }
        return shared;
    }

    /**
     * The tokenizer belonging to a particular model.
     *
     * <p>A vocabulary is not interchangeable: the ids are the model's own, and reading a model with
     * the wrong one produces confident nonsense rather than an error -- the same failure this class
     * is otherwise careful about. The one carried here is SmolLM2's, so anything else has to bring
     * its own, and the convention is the one every model repository already follows: a
     * {@code tokenizer.json} beside the weights.
     *
     * <p>Falling back to the built-in vocabulary when none is found is deliberate rather than
     * lenient: models in that family are the common case, and a missing file is then a working
     * default rather than a refusal.
     */
    public static BpeTokenizer forModel(java.nio.file.Path weights) throws IOException {
        java.nio.file.Path beside = weights.resolveSibling("tokenizer.json");
        if (java.nio.file.Files.isRegularFile(beside)) {
            try (InputStream in = java.nio.file.Files.newInputStream(beside)) {
                return new BpeTokenizer(new ObjectMapper().readTree(in));
            }
        }
        return shared();
    }

    private static BpeTokenizer load() throws IOException {
        try (InputStream in = BpeTokenizer.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("the tokenizer is missing from this build: " + RESOURCE);
            }
            return new BpeTokenizer(new ObjectMapper().readTree(in));
        }
    }

    private BpeTokenizer(JsonNode root) {
        this.vocab = new HashMap<>();
        JsonNode vocabNode = root.path("model").path("vocab");
        vocabNode
                .fields()
                .forEachRemaining(e -> vocab.put(e.getKey(), e.getValue().asInt()));

        this.idToToken = new String[vocab.size() + 64];
        for (Map.Entry<String, Integer> e : vocab.entrySet()) {
            if (e.getValue() < idToToken.length) {
                idToToken[e.getValue()] = e.getKey();
            }
        }

        // Rank is position in the list: the earlier a pair appears, the sooner it merges.
        this.merges = new HashMap<>();
        JsonNode mergeNode = root.path("model").path("merges");
        for (int i = 0; i < mergeNode.size(); i++) {
            JsonNode m = mergeNode.get(i);
            String pair = m.isArray() ? m.get(0).asText() + " " + m.get(1).asText() : m.asText();
            merges.put(pair, i);
        }

        // Longest first, so <|im_start|> is not shadowed by a shorter token that prefixes it.
        this.addedTokens = new LinkedHashMap<>();
        List<String> contents = new ArrayList<>();
        for (JsonNode a : root.path("added_tokens")) {
            String content = a.path("content").asText();
            int id = a.path("id").asInt();
            addedTokens.put(content, id);
            contents.add(content);
            if (id < idToToken.length) {
                idToToken[id] = content;
            }
        }
        contents.sort((x, y) -> y.length() - x.length());
        StringBuilder alternation = new StringBuilder();
        for (String c : contents) {
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(c));
        }
        this.addedPattern = alternation.length() == 0 ? null : Pattern.compile(alternation.toString());

        buildByteTable();
    }

    /**
     * The fixed byte-to-character table every byte-level BPE shares.
     *
     * <p>Printable ASCII and two Latin-1 ranges stand for themselves; everything else -- control
     * characters, the space, and the top of the byte range -- is lifted into a private area above
     * U+0100. The point is that arbitrary bytes survive as ordinary text through a vocabulary that
     * only knows characters.
     */
    private void buildByteTable() {
        boolean[] direct = new boolean[256];
        for (int b = '!'; b <= '~'; b++) {
            direct[b] = true;
        }
        for (int b = 0xA1; b <= 0xAC; b++) {
            direct[b] = true;
        }
        for (int b = 0xAE; b <= 0xFF; b++) {
            direct[b] = true;
        }
        int next = 0;
        for (int b = 0; b < 256; b++) {
            char c = direct[b] ? (char) b : (char) (256 + next++);
            byteToChar[b] = c;
            charToByte.put(c, b);
        }
    }

    /** Text to token ids, the way the model was trained to read it. */
    public int[] encode(String text) {
        List<Integer> out = new ArrayList<>();
        if (addedPattern == null) {
            encodeOrdinary(text, out);
        } else {
            Matcher m = addedPattern.matcher(text);
            int at = 0;
            while (m.find()) {
                if (m.start() > at) {
                    encodeOrdinary(text.substring(at, m.start()), out);
                }
                out.add(addedTokens.get(m.group()));
                at = m.end();
            }
            if (at < text.length()) {
                encodeOrdinary(text.substring(at), out);
            }
        }
        int[] ids = new int[out.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = out.get(i);
        }
        return ids;
    }

    private void encodeOrdinary(String text, List<Integer> out) {
        Matcher m = PIECES.matcher(text);
        while (m.find()) {
            for (String piece : splitDigits(m.group())) {
                for (String token : bpe(toByteLevel(piece))) {
                    Integer id = vocab.get(token);
                    if (id != null) {
                        out.add(id);
                    }
                    // A token absent from the vocabulary cannot happen with a byte-level table:
                    // every single character is itself a token. Dropping rather than throwing keeps
                    // a malformed vocabulary from taking down a command that had other work to do.
                }
            }
        }
    }

    /** The Digits pre-tokenizer: each digit stands alone, everything else passes through. */
    private static List<String> splitDigits(String piece) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (DIGIT_RUN.matcher(String.valueOf(c)).matches()) {
                if (run.length() > 0) {
                    out.add(run.toString());
                    run.setLength(0);
                }
                out.add(String.valueOf(c));
            } else {
                run.append(c);
            }
        }
        if (run.length() > 0) {
            out.add(run.toString());
        }
        return out;
    }

    private String toByteLevel(String piece) {
        byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append(byteToChar[b & 0xFF]);
        }
        return sb.toString();
    }

    /**
     * Merge the lowest-ranked adjacent pair until none is left.
     *
     * <p>The straightforward implementation of the algorithm rather than a fast one: pieces are
     * short, and a prompt is tokenised once per call.
     */
    private List<String> bpe(String word) {
        List<String> parts = new ArrayList<>(word.length());
        for (int i = 0; i < word.length(); i++) {
            parts.add(String.valueOf(word.charAt(i)));
        }
        while (parts.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestAt = -1;
            for (int i = 0; i < parts.size() - 1; i++) {
                Integer rank = merges.get(parts.get(i) + " " + parts.get(i + 1));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestAt = i;
                }
            }
            if (bestAt < 0) {
                break;
            }
            parts.set(bestAt, parts.get(bestAt) + parts.get(bestAt + 1));
            parts.remove(bestAt + 1);
        }
        return parts;
    }

    /** Token ids back to the text they stand for. */
    public String decode(int[] ids, boolean skipSpecial) {
        StringBuilder level = new StringBuilder();
        for (int id : ids) {
            String token = id >= 0 && id < idToToken.length ? idToToken[id] : null;
            if (token == null) {
                continue;
            }
            if (skipSpecial && addedTokens.containsKey(token)) {
                continue;
            }
            level.append(token);
        }
        byte[] bytes = new byte[level.length()];
        int n = 0;
        for (int i = 0; i < level.length(); i++) {
            Integer b = charToByte.get(level.charAt(i));
            if (b != null) {
                bytes[n++] = (byte) (int) b;
            }
        }
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    /** The id of a named special token, e.g. {@code <|im_end|>}. */
    public int specialId(String content) {
        Integer id = addedTokens.get(content);
        if (id == null) {
            throw new IllegalArgumentException("no such special token: " + content);
        }
        return id;
    }

    public int vocabSize() {
        return vocab.size();
    }
}
