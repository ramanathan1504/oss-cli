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

import com.osscli.serve.Askable;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Let the loop ask oss the things oss already answers.
 *
 * <p>Found by measuring rather than by review. Asked "which of my recorded reviews are waiting on
 * me", the loop searched the issue index three times, found only titles containing the word
 * "review", and concluded — carefully, and wrongly — that the machine holds no such data. It took
 * 55 seconds to be wrong about something {@code oss hub} answers correctly in eight, because
 * {@code recall} searches synced issues and the review ledger is not one.
 *
 * <p>That is not a missing index. It is the loop being blind to the tool it lives inside: the
 * ledger, the follow-ups, the backlog and the picker are all answers oss already computes, and none
 * of them were reachable.
 *
 * <p><b>The list is {@link Askable}'s, not a new one.</b> That table exists because the local board
 * had the same need and the same constraint — everything on it reads, nothing on it writes, and a
 * test fails the build if a writing command is ever added. Reusing it means the page and the loop
 * can never disagree about what is safe to run unattended, and it means this tool inherits that
 * test rather than needing its own.
 */
public final class AskOss implements Tool {

    private final BiFunction<List<String>, Integer, String> run;

    /**
     * @param run how to execute an oss argv and capture what it printed; injected so the tool is
     *     testable without starting a process
     */
    public AskOss(BiFunction<List<String>, Integer, String> run) {
        this.run = run;
    }

    @Override
    public String name() {
        return "oss";
    }

    @Override
    public String usage() {
        return "oss — question: <" + String.join("|", keys()) + ">   [arg: <value>]   ask oss itself";
    }

    @Override
    public boolean writes() {
        // Every entry on Askable's table reads. That is enforced by a test in the serve package,
        // which is the reason this tool can say false without auditing the list itself.
        return false;
    }

    @Override
    public String run(Action action, Workspace workspace) {
        String key = action.argument("question").strip().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) {
            return "error: oss needs question: — one of " + String.join(", ", keys());
        }
        Askable.Question question = Askable.byKey(key);
        if (question == null) {
            // The table, with what each one answers: a model that asked for the wrong key learns
            // the vocabulary rather than that it failed.
            return "error: \"" + key + "\" is not something oss can be asked. Available:\n" + catalogue();
        }
        List<String> argv = new java.util.ArrayList<>(question.argv());
        String arg = action.argument("arg");
        if (question.needsArgument() && arg.isEmpty()) {
            return "error: " + key + " needs arg: — " + question.asks();
        }
        if (!arg.isEmpty()) {
            argv.add(arg);
        }
        String output = run.apply(argv, question.timeoutSeconds());
        return output == null || output.isBlank() ? question.empty() : output;
    }

    private static List<String> keys() {
        return Askable.all().stream().map(Askable.Question::key).toList();
    }

    private static String catalogue() {
        return Askable.all().stream()
                .map(q -> "  " + q.key() + " — " + q.asks())
                .collect(Collectors.joining("\n"));
    }
}
