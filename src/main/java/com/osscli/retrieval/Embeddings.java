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
package com.osscli.retrieval;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * The one embedder, shared by everything that turns text into a vector.
 *
 * <p>There used to be two. Issues were embedded by a model served over HTTP by a separate daemon,
 * while notes and reviews were embedded in-process by {@link LocalEmbedder} — the same
 * all-MiniLM-L6-v2, arriving two different ways, writing into two stores that never shared a
 * vector. Running {@code search} and {@code pick} back to back asked two embedders the same
 * question. Worse, half the tool needed a daemon installed and running to do arithmetic that
 * fits in 22 MB and this process.
 *
 * <p>So there is one, and it is the in-process one. That removes a multi-gigabyte install and a
 * background service from every path that indexes or searches, which is the whole claim: a token
 * is the requirement, and everything else is a layer you may add.
 *
 * <p><b>Presence, never acquisition.</b> Nothing here downloads. {@link #ifPresent} returns null
 * when the weights are absent and every caller falls back to term search, because a command that
 * quietly pulls 22 MB the first time it runs is a command people stop trusting. {@code oss model
 * --fetch} is the one place that asks, and {@link #fetch} is what it calls.
 */
public final class Embeddings {

    /** Recorded beside every vector this produces. Lives in {@link com.osscli.Defaults} so storage can name it too. */
    public static final String MODEL = com.osscli.Defaults.EMBEDDING_MODEL;

    /** 384, from the model itself. Stated so a mismatch is caught rather than averaged. */
    public static final int DIMENSIONS = LocalEmbedder.DIMENSIONS;

    /** Said the same way everywhere, so the fix does not depend on which command you happened to run. */
    public static final String ABSENT_HINT = "oss model --fetch  adds search by meaning (about 22 MB, once)";

    private static LocalEmbedder shared;

    /**
     * Set when loading has already failed once.
     *
     * <p>Without it a caller that embeds in a loop retries a broken model file on every item, paying
     * the failure repeatedly and printing the same warning each time. A model that would not load a
     * moment ago will not load now.
     */
    private static boolean loadFailed;

    private Embeddings() {}

    /** True once the weights are on disk, so a caller can choose its path before doing any work. */
    public static boolean isReady() {
        return LocalEmbedder.isDownloaded();
    }

    /**
     * The shared embedder, or {@code null} when the model has not been fetched.
     *
     * <p>Loaded once per process and kept. Indexing a large repository is thousands of calls, and
     * building an inference session per call costs more than the inference does.
     *
     * @param onProgress told about a slow load; never told about a download, because there is none
     */
    public static synchronized LocalEmbedder ifPresent(Consumer<String> onProgress) {
        if (shared != null) {
            return shared;
        }
        if (loadFailed || !LocalEmbedder.isDownloaded()) {
            return null;
        }
        try {
            shared = LocalEmbedder.load(onProgress);
            closeAtExit();
        } catch (Exception e) {
            // Present but unloadable is a real state -- a truncated file, a mismatched runtime --
            // and it must degrade to term search rather than fail whatever command asked.
            loadFailed = true;
            onProgress.accept("model present but would not load; using term search");
            return null;
        }
        return shared;
    }

    /**
     * Fetch the weights if absent, then load. Only {@code oss model --fetch} calls this.
     *
     * @param onProgress told what is happening, because a silent 22 MB download looks like a hang
     */
    public static synchronized LocalEmbedder fetch(Consumer<String> onProgress) throws IOException {
        if (shared == null) {
            shared = LocalEmbedder.load(onProgress);
            closeAtExit();
        }
        return shared;
    }

    /**
     * One text to one unit-length vector, or {@code null} when there is no model.
     *
     * <p>Null rather than an exception: every caller of this has a term-search floor to fall back
     * to, and an absent optional layer is not an error.
     */
    public static double[] embed(String text, Consumer<String> onProgress) throws IOException {
        LocalEmbedder e = ifPresent(onProgress);
        return e == null ? null : e.embed(text);
    }

    /** The session owns native memory, and a CLI that exits without releasing it logs a warning on the way out. */
    private static void closeAtExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (Embeddings.class) {
                if (shared != null) {
                    shared.close();
                    shared = null;
                }
            }
        }));
    }
}
