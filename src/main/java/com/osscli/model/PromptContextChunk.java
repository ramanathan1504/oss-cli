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

public record PromptContextChunk(
        String sourceType, // 'issue', 'pr_memory', 'chat_memory', 'jira', 'cross_repo', 'stack_trace'
        String sourceRef, // e.g. '#1234', 'PR #56', 'chatgpt_export.json'
        String content,
        double relevanceScore,
        int tokenCount,
        boolean included // false = dropped due to token budget
        ) {}
