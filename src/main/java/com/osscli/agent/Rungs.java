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

import com.osscli.llm.Ai;
import com.osscli.llm.Cloud;
import com.osscli.llm.OllamaClient;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which rung answers the loop's turns, decided once instead of per turn.
 *
 * <p>The loop takes a function from prompt to text. This builds that function out of the ladder the
 * rest of the tool already uses: the local daemon when it is there, an external engine when one was
 * named and can be reached. Chosen once and held, because a loop that re-decided every turn could
 * read a file on one engine and reason about it on another — and the answer to "whose model saw my
 * code" would be a list.
 *
 * <p>The built-in model is deliberately not a rung here, and this is the one place the ladder stops
 * short. It ranks and retrieves; it does not write sentences, and a loop needs something that can
 * decide what to look at next. Rather than pretend, {@link #forThisMachine} returns empty and the
 * caller says so plainly — the alternative is a command that appears to work and produces nothing,
 * which is the failure mode this repository keeps writing tests against.
 */
public final class Rungs {

    private Rungs() {}

    /** What answered, so the user can be told before it does. */
    public record Chosen(String label, Function<String, String> ask) {}

    /**
     * The best rung available, or empty when nothing on this machine can write a sentence.
     *
     * @param model the local model name to try
     */
    public static Optional<Chosen> forThisMachine(String model) {
        // Named engines first: naming one is the user saying they are willing to pay for it, and
        // ignoring that in favour of a local daemon would make the prefix a lie.
        for (Ai.Engine engine : Ai.escalationPath()) {
            return Optional.of(new Chosen(engine.label(), prompt -> {
                try {
                    return Cloud.generateText(engine, prompt);
                } catch (Exception e) {
                    // One turn's failure, handed back as text: the loop treats it as an
                    // observation and can still answer from what it already has.
                    return "error: " + engine.label() + " could not answer — " + e.getMessage();
                }
            }));
        }
        OllamaClient local = new OllamaClient(model);
        // Both questions, not one: a daemon that is running without the model pulled answers the
        // first and fails the second, and a loop discovering that on turn three has already spent
        // the user's time.
        if (local.isServerReachable() && local.isModelAvailable()) {
            return Optional.of(new Chosen("local " + model, prompt -> {
                try {
                    return local.generateText(prompt);
                } catch (Exception e) {
                    return "error: the local model could not answer — " + e.getMessage();
                }
            }));
        }
        return Optional.empty();
    }
}
