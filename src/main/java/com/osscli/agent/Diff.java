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

import java.util.List;

/**
 * What is about to change, in the shape everybody already reads.
 *
 * <p>The person confirming has one second and one screen to decide, so this shows the changed lines
 * with a little context and nothing else — not the file, not a summary of the file, and never a
 * description of the change in the model's own words. A model describing its own edit is the one
 * account of it that cannot be checked against the edit.
 */
public final class Diff {

    /** Lines of context either side. Enough to place a change, few enough to stay on one screen. */
    static final int CONTEXT = 3;

    private Diff() {}

    /**
     * A minimal unified view of one replacement.
     *
     * @param before the whole file, as lines
     * @param after the whole file after the change, as lines
     */
    public static String of(String path, List<String> before, List<String> after) {
        int firstChange = 0;
        while (firstChange < before.size()
                && firstChange < after.size()
                && before.get(firstChange).equals(after.get(firstChange))) {
            firstChange++;
        }
        int lastBefore = before.size() - 1;
        int lastAfter = after.size() - 1;
        while (lastBefore >= firstChange
                && lastAfter >= firstChange
                && before.get(lastBefore).equals(after.get(lastAfter))) {
            lastBefore--;
            lastAfter--;
        }

        StringBuilder b = new StringBuilder();
        b.append("--- ").append(path).append('\n');
        int from = Math.max(0, firstChange - CONTEXT);
        for (int i = from; i < firstChange; i++) {
            b.append("    ").append(i + 1).append("  ").append(before.get(i)).append('\n');
        }
        for (int i = firstChange; i <= lastBefore; i++) {
            b.append("  - ").append(i + 1).append("  ").append(before.get(i)).append('\n');
        }
        for (int i = firstChange; i <= lastAfter; i++) {
            b.append("  + ").append(i + 1).append("  ").append(after.get(i)).append('\n');
        }
        int to = Math.min(before.size(), lastBefore + 1 + CONTEXT);
        for (int i = lastBefore + 1; i < to; i++) {
            b.append("    ").append(i + 1).append("  ").append(before.get(i)).append('\n');
        }
        return b.toString();
    }
}
