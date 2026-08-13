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
package com.osscli.model;

/**
 * One thing an issue says about another: a mention, a claim to fix, or a commit.
 *
 * <p>Lives here rather than beside the parser because storage has to name it too, and pointing the
 * storage package at the retrieval package to borrow a record would put the two in a cycle over
 * nothing.
 *
 * @param toRepository the referenced repository, always set for an issue reference
 * @param toNumber the referenced issue or pull request, 0 for a commit
 * @param toSha the referenced commit, null for an issue reference
 */
public record IssueReference(Kind kind, String toRepository, long toNumber, String toSha) {

    /** What kind of statement it is. */
    public enum Kind {
        /** Mentioned in passing. */
        MENTIONS,
        /** Claims to resolve it. Worth more, because the author said so rather than a ranking inferring it. */
        CLOSES,
        /** A commit, by SHA. */
        COMMIT;

        public static Kind of(String raw) {
            for (Kind k : values()) {
                if (k.name().equalsIgnoreCase(raw)) {
                    return k;
                }
            }
            return MENTIONS;
        }
    }

    /** Stable identity, so the same edge written twice stays one row. */
    public String key() {
        return toSha != null ? "sha:" + toSha : toRepository + "#" + toNumber;
    }
}
