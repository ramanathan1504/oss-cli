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

import com.osscli.storage.SqliteStorage;
import java.io.IOException;

/**
 * The one place an external engine is reached.
 *
 * <p>There were two: {@code ReviewCommand.sendToCloud} and {@code PromptCommand.sendToCloud}, each
 * a three-branch switch over the same three providers, with different defaults for the same model
 * settings. {@link ApiFailure} exists in this package because three copies of a decision drifted
 * apart and grew the same bug independently; adding the command-line transport to both switches
 * would have been how that happens again.
 *
 * <p>The transport is read here rather than decided here: {@link Ai#routeFor} owns that choice, so
 * one rule answers it for every caller instead of each command growing its own. {@code --cli}
 * still forces the tool, a key is still preferred when there is one, and the tool is reached
 * unasked only when there is no key at all -- a rung, not an account switch. Whichever rung
 * replies is printed before it replies, so "whose model saw my code, and whose account paid" is
 * still answered on screen rather than in a bill.
 */
public final class Cloud {

    private Cloud() {}

    /** How long a command-line tool may take before it is treated as not coming back. */
    private static final long CLI_TIMEOUT_SECONDS = 900;

    public static String generateText(Ai.Engine engine, String prompt) throws IOException, InterruptedException {
        Ai.Route route = Ai.routeFor(engine);
        if (route == Ai.Route.CLI) {
            CliClient.Spec spec = CliClient.specFor(engine);
            if (spec == null) {
                throw new ApiFailure.Permanent(0, engine.label() + " has no command-line tool");
            }
            if (!Ai.viaCli()) {
                announce(engine, spec);
            }
            return new CliClient(spec, CLI_TIMEOUT_SECONDS).generateText(prompt);
        }
        if (route == Ai.Route.NONE && engine.isExternal()) {
            // Both fixes, named. Refusing with only the key half is how `chat` and `guide` each
            // shipped refusing a user who had the other half installed all along.
            CliClient.Spec spec = CliClient.specFor(engine);
            throw new ApiFailure.Permanent(
                    0,
                    engine.label() + " has neither a key nor its command-line tool — set the key, or install "
                            + (spec == null ? "the provider's tool" : spec.binary()) + " and sign in");
        }
        return switch (engine) {
            case CLAUDE -> new ClaudeClient(model("claude.model", "claude-sonnet-5")).generateText(prompt);
            case OPENAI -> new OpenAiClient(model("openai.model", "gpt-4o")).generateText(prompt);
            case GEMINI -> new GeminiClient(model("gemini.model", "gemini-2.0-flash")).generateText(prompt);
            default -> throw new ApiFailure.Permanent(0, engine.label() + " is not an external engine");
        };
    }

    /** The provider names the review verdict prints, mapped to the engines they name. */
    public static Ai.Engine engineNamed(String provider) {
        return switch (provider) {
            case "claude" -> Ai.Engine.CLAUDE;
            case "openai" -> Ai.Engine.OPENAI;
            default -> Ai.Engine.GEMINI;
        };
    }

    /**
     * Says which rung answered, before it answers.
     *
     * <p>An engine reached through a subscription instead of a key is a different account and a
     * harness that can read files, so it is not a detail to discover afterwards in a bill. Printed
     * to stderr because it is not part of the answer: a piped {@code oss claude guide > notes.md}
     * keeps a clean file and the reader still sees the line.
     *
     * <p>Not a warning. Nothing is wrong -- this is the ladder working, and a tool that shouts
     * when it succeeds teaches people to ignore it when it does not.
     */
    private static void announce(Ai.Engine engine, CliClient.Spec spec) {
        System.err.println("  no " + engine.label() + " key — answering through the " + spec.binary()
                + " command-line tool, on the subscription it is signed in to");
    }

    private static String model(String key, String fallback) {
        try {
            String configured = SqliteStorage.loadConfig(key);
            return configured == null || configured.isBlank() ? fallback : configured;
        } catch (Exception e) {
            // An unreadable config is not a reason to refuse the call: the documented default is
            // what an unset value means anyway.
            return fallback;
        }
    }
}
