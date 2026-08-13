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
 * Everything known about one pull request at one commit.
 *
 * <p>Identified by {@code headSha} as well as number, because a pull request's content is rewritten by every push. Two
 * evidence sets for the same number are different objects, not different versions of one.
 *
 * <p>JSON columns hold the API responses as received. Keeping the raw form means a later reviewer, model or profile can
 * ask a question of this evidence that nobody thought to extract when it was fetched.
 */
public record PrEvidence(
        String repository,
        long prNumber,
        String headSha,
        String title,
        String author,
        String state,
        String baseRef,
        String body,
        String commitsJson,
        String filesJson,
        String diff,
        String reviewsJson,
        String commentsJson,
        String checksJson,
        int additions,
        int deletions,
        int changedFiles) {}
