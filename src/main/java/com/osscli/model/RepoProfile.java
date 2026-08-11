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
 * What a repository is, derived from what it actually contains.
 *
 * <p>Everything here comes from pattern-matching real files, never from a table of known projects. A repository nobody
 * has profiled before is handled the same way as a familiar one -- which is the only way this can serve a user who
 * registers something the author has never seen.
 */
public record RepoProfile(
        String repository,
        String primaryLanguage,
        String buildSystem,
        String targetVersion,
        String minVersion,
        String conventionsJson,
        String docsJson,
        String summary) {}
