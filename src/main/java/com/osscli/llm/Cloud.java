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
 * <p>The transport is read here rather than decided here. {@code --cli} is the only thing that
 * turns it on, so the answer to "whose model saw my code, and whose account paid" stays the line
 * that was typed.
 */
public final class Cloud {

    private Cloud() {}

    /** How long a command-line tool may take before it is treated as not coming back. */
    private static final long CLI_TIMEOUT_SECONDS = 900;

    public static String generateText(Ai.Engine engine, String prompt) throws IOException, InterruptedException {
        if (Ai.viaCli()) {
            CliClient.Spec spec = CliClient.specFor(engine);
            if (spec == null) {
                throw new ApiFailure.Permanent(0, engine.label() + " has no command-line tool");
            }
            return new CliClient(spec, CLI_TIMEOUT_SECONDS).generateText(prompt);
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
