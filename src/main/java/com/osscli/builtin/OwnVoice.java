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

import com.osscli.model.PromptContextChunk;
import com.osscli.retrieval.NoteRetriever;
import java.util.ArrayList;
import java.util.List;

/**
 * Making a local model write the way <em>this</em> user writes.
 *
 * <p>The obvious reading of "it should learn my style" is fine-tuning, and that is not what this
 * is. Training a model on this machine is not available: ONNX Runtime's Java binding runs graphs,
 * it does not differentiate them, and the laptop this has to work on has a few hundred megabytes
 * of headroom rather than the several gigabytes a fine-tune of even a small model wants. Promising
 * training and shipping something else would be the worst of the options.
 *
 * <p>What is available is the thing that actually moves a model's register: showing it the user's
 * own writing and asking for more of the same. The archive already holds it -- reviews written by
 * hand, notes, harvested threads -- and the embedder already finds the passages closest to what is
 * being written now. So the demonstrations are not generic examples of good prose; they are the
 * three things this person wrote that are nearest to the question in front of them.
 *
 * <p>This is imitation of surface form, and it is honest about being that. Register, sentence
 * length, whether they open with a verb, whether they hedge -- a small model copies those from
 * examples reliably, which is the one thing it does better than it reasons. It does not make the
 * content correct, and nothing here claims it does.
 *
 * <p>It also improves as the archive grows, without anything being retrained: the more that has
 * been written and indexed, the closer the retrieved examples are to the next question. That is
 * the sense in which it learns.
 */
public final class OwnVoice {

    /**
     * How many of the user's own passages go in front of the model.
     *
     * <p>Three, because a model this size has a short context and every example spent on voice is
     * context not spent on the material being written about. Two is enough to establish a register
     * and four starts to crowd out the question.
     */
    private static final int EXAMPLES = 3;

    /** Characters kept per example. A whole review would fill the window on its own. */
    private static final int EXAMPLE_CHARS = 600;

    private OwnVoice() {}

    /**
     * A system instruction carrying the user's own writing as the pattern to follow.
     *
     * <p>Returns the plain instruction unchanged when the archive has nothing close. An empty
     * corpus is the normal state of a new install, and a prompt that says "write like these
     * examples" followed by no examples is worse than one that never mentioned them.
     *
     * @param instruction what the model is being asked to do
     * @param about the text the answer will be about, used to find the nearest passages
     */
    public static String inTheUsersVoice(String instruction, String about) {
        List<String> samples = samples(about);
        if (samples.isEmpty()) {
            return instruction;
        }
        StringBuilder out = new StringBuilder(instruction);
        out.append("\n\nWrite in the same voice as these passages, which the user wrote themselves. ")
                .append("Match their register and sentence length. Do not copy their content.\n");
        for (String sample : samples) {
            out.append("\n---\n").append(sample).append('\n');
        }
        return out.toString();
    }

    /** The user's own passages nearest to what is being written about, shortened to fit. */
    static List<String> samples(String about) {
        List<String> out = new ArrayList<>();
        if (about == null || about.isBlank()) {
            return out;
        }
        try {
            // The same retrieval `review` uses for prior work. Nothing new is indexed for this:
            // asking a second question of a corpus that already exists is the whole point of
            // having built it.
            for (PromptContextChunk chunk : NoteRetriever.retrieveFor(about, EXAMPLES, null)) {
                String content = chunk.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                out.add(content.length() > EXAMPLE_CHARS ? content.substring(0, EXAMPLE_CHARS) + "…" : content);
            }
        } catch (RuntimeException e) {
            // A voice is a nicety on top of an answer. An archive that cannot be read costs the
            // register, not the reply.
            return List.of();
        }
        return out;
    }
}
