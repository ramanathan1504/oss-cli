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
package com.osscli.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One thing a model asked to have done, read out of the text it produced.
 *
 * <p><b>Text, not a provider's tool API.</b> Anthropic, OpenAI and Google each expose tool calling
 * differently, Ollama's support depends on the model pulled, and the built-in model has none at
 * all. Building the loop on any of them would mean the agent exists on {@code oss claude} and
 * silently does not on the rungs below it — which is the gating failure this repository has already
 * paid for twice, once when {@code chat} refused without a Gemini key and again when it refused
 * without Ollama.
 *
 * <p>So the contract is a fenced block, which every rung can produce and a human can read in the
 * transcript:
 *
 * <pre>
 * ```oss
 * tool: read_file
 * path: src/main/java/com/osscli/Main.java
 * ```
 * </pre>
 *
 * <p>Deliberately line-oriented rather than JSON. A small local model emits malformed JSON often
 * enough that the loop would spend its turns apologising, and a missing quote should not cost a
 * step. Keys are single words; the value is the rest of the line, trimmed.
 *
 * <p>Nothing here executes anything. Parsing is separated from running so the protocol can be
 * tested against the output of models that are not installed, and so a malformed block is a
 * rejected string rather than a half-performed action.
 */
public record Action(String tool, Map<String, String> arguments, String raw) {

    /** The fence a model must use. Anything else in the reply is prose, and is ignored. */
    private static final Pattern BLOCK = Pattern.compile("```oss\\s*\\n(.*?)```", Pattern.DOTALL);

    /**
     * The first action in a reply, if there is one.
     *
     * <p>First, not all of them: one action per turn keeps the transcript readable and means a
     * model that emits five speculative steps has four of them re-decided against real output
     * rather than performed blind.
     */
    public static Optional<Action> firstIn(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Matcher m = BLOCK.matcher(reply);
        if (!m.find()) {
            return Optional.empty();
        }
        String body = m.group(1);
        Map<String, String> args = new LinkedHashMap<>();
        String tool = null;
        for (String line : body.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = value(trimmed.substring(colon + 1));
            if ("tool".equals(key)) {
                tool = value.toLowerCase(Locale.ROOT);
            } else {
                args.put(key, value);
            }
        }
        // A block naming no tool is not an action. Returning one with a null name would push the
        // decision into the loop, which would have to invent an error message for it.
        return tool == null || tool.isEmpty() ? Optional.empty() : Optional.of(new Action(tool, args, body.strip()));
    }

    /**
     * The value of one line, with a way to say "exactly this, spaces included".
     *
     * <p>Stripping is right for nearly everything: a path with a trailing space is a typo, not an
     * instruction. It is wrong for the one argument where whitespace carries meaning — the text an
     * edit is matching, where the leading spaces ARE the indentation being matched. A quoted value
     * is taken verbatim between the quotes.
     *
     * <p>Found by a test for deleting text: {@code find: " DELETE ME"} silently lost its leading
     * space and the edit left two spaces behind, which is the class of change nobody reviews
     * carefully because the diff looks almost right.
     */
    private static String value(String raw) {
        String trimmed = raw.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** An argument, or the empty string — a missing one is the tool's business to complain about. */
    public String argument(String name) {
        return arguments.getOrDefault(name, "");
    }
}
