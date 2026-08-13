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
package com.osscli;

/**
 * Fallback values used when configuration has not been set yet.
 *
 * <p>These exist in one place because they used to exist in five. The guidance model was written
 * as a literal in the schema seed and again in four commands, so changing it meant finding all of
 * them and the copies drifted apart in the meantime.
 *
 * <p>Nothing personal belongs here. A seeded default ships to every machine that installs this, so
 * a value that made sense on the author's laptop becomes a value a stranger inherits without ever
 * choosing it. The GitHub username used to be seeded that way -- every fresh database on every
 * machine came preloaded with one person's account, and {@code sync --me} would have harvested
 * their contribution history onto somebody else's laptop. Identity is now left blank and asked for
 * during {@code setup}.
 */
public final class Defaults {

    /**
     * A small, instruction-tuned model chosen so a first run has a chance of working unattended:
     * roughly 2 GB, widely pulled, and NOT a reasoning model -- reasoning models return their
     * output in a separate field, which reads here as an empty answer and escalates every time.
     *
     * <p>It is a starting point, not a recommendation. The right model depends on the machine and
     * the work; {@code setup} asks, and {@code doctor} reports what is actually installed.
     */
    public static final String GUIDANCE_MODEL = "llama3.2:3b";

    /** Small enough to run anywhere, only ever used for cheap high-volume classification. */
    public static final String TRIAGE_MODEL = "qwen2.5:0.5b";

    /**
     * The built-in embedder, recorded beside every vector it produces.
     *
     * <p>Unlike the models above this is not a configurable default -- it is the identity of the
     * embedder that ships inside the tool, kept here because {@code storage} must name it to keep
     * one model's vectors from being compared against another's, and reaching into {@code
     * retrieval} for it would point the two packages at each other.
     *
     * <p>Embeddings matter more than the chat model: no corpus fits in a context window, so
     * retrieval quality sets the ceiling and a larger chat model cannot recover text the embedder
     * never read. all-MiniLM-L6-v2 is chosen for what this tool is allowed to ask of a machine --
     * Apache-2.0, 22 MB quantised, and six layers, which is what keeps indexing thousands of issues
     * viable on a laptop CPU. Deeper 384-dimension models retrieve better and cost roughly double
     * per document to index.
     *
     * <p>The {@code -onnx} suffix distinguishes these vectors from ones a daemon produced from the
     * same weights under the name {@code all-minilm}. Both are 384 dimensions, so nothing would
     * catch the mix by shape; they are quantised and pooled differently and are not comparable.
     */
    public static final String EMBEDDING_MODEL = "all-MiniLM-L6-v2-onnx";

    public static final String OLLAMA_URL = "http://localhost:11434";

    private Defaults() {}
}
