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
 * One thing said in a chat, stored the moment it is said.
 *
 * @param seq position in the conversation, from 1; unique within a session so a retried write cannot
 *     duplicate a turn
 */
public record ChatTurn(long id, long sessionId, int seq, Role role, String content, String createdAt) {

    /**
     * Who said it.
     *
     * <p>{@link #LOCAL} and {@link #CLOUD} are kept apart rather than collapsed into one "assistant"
     * because the difference is the whole point of this tool: one answer never left the machine and
     * the other was sent to somebody's API. A transcript that cannot tell you which is which cannot
     * answer the only question worth asking about it later.
     */
    public enum Role {
        USER("you"),
        LOCAL("local"),
        CLOUD("cloud");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Unknown values become {@link #LOCAL} rather than throwing: a readable transcript beats a crash. */
        public static Role of(String raw) {
            if (raw == null) {
                return LOCAL;
            }
            for (Role r : values()) {
                if (r.name().equalsIgnoreCase(raw.trim())) {
                    return r;
                }
            }
            return LOCAL;
        }
    }
}
