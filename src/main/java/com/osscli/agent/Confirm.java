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

import java.util.Locale;

/**
 * Asking a person, at a terminal, before something is changed.
 *
 * <p>An interface rather than a call to {@code System.console()} because the alternative is an edit
 * tool that can only be tested by letting it edit something. Every test here hands it a yes or a no
 * and checks what happened to the file.
 *
 * <p>{@link #atTerminal()} refuses when there is no terminal at all. A pipe cannot confirm anything,
 * and treating an absent answer as a yes is how a run that was supposed to show its work ends up
 * having rewritten twelve files in a CI job nobody was watching.
 */
public interface Confirm {

    /** True when the person said yes, and only then. */
    boolean ask(String question);

    /** Reads y/n from the terminal, and refuses when there is not one. */
    static Confirm atTerminal() {
        return question -> {
            if (System.console() == null) {
                // Said out loud rather than silently declined: a script that expected this to work
                // needs to know the reason is the missing terminal, not the edit.
                System.err.println("  refused: nothing to confirm at — this is not a terminal.");
                return false;
            }
            try {
                // Read from the console object whose existence was just established, rather than
                // wrapping System.in beside it. Two ways of reading the same terminal is one more
                // than there should be, and the wrapped stream is the one that misbehaves when
                // something upstream has already touched it.
                String answer = System.console().readLine("%s [y/N] ", question);
                // Only an explicit yes. Enter, EOF, ctrl-d and anything else all mean no, because
                // the safe reading of "I do not know" is "do not change my files".
                return answer != null && answer.strip().toLowerCase(Locale.ROOT).matches("y|yes");
            } catch (Exception e) {
                return false;
            }
        };
    }

    /** Always no. What a run gets when edits were never enabled. */
    static Confirm never() {
        return question -> false;
    }
}
