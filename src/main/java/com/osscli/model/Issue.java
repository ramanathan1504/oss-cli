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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Issue(
        long number,
        String title,
        String body,
        String state,
        int comments,
        String created_at,
        String updated_at,
        PullRequestMarker pull_request,
        List<Label> labels,
        User user,
        String author_association,
        String html_url // Added to track the source URL
        ) {
    public boolean isPullRequest() {
        return pull_request != null;
    }

    public boolean hasLabel(String labelName) {
        return labels != null && labels.stream().anyMatch(label -> label.name().equalsIgnoreCase(labelName));
    }

    public boolean isOrgMember() {
        if (author_association == null) {
            return false;
        }
        String upper = author_association.toUpperCase();
        return "MEMBER".equals(upper) || "OWNER".equals(upper) || "COLLABORATOR".equals(upper);
    }

    /**
     * The {@code owner/name} this issue belongs to, read from its own URL, or null when the URL does
     * not say.
     *
     * <p>Both of these returns used to be the literal string {@code apache/logging-log4j2} — the
     * repository this tool happened to be written against. That is not a default, it is a guess with
     * one project's name on it, and it did more than look wrong on somebody else's machine:
     * {@code sync} splits this value and fetches the pull request's changed files from it, then
     * stores them under it. A pull request whose URL could not be parsed had another project's files
     * looked up and written into the footprint as though they were the user's own.
     *
     * <p>Null is the honest answer, and the caller skips what it cannot place.
     */
    public String getRepositoryOwnerAndName() {
        if (html_url == null) {
            return null;
        }
        // html_url looks like: https://github.com/owner/name/pull/number
        String prefix = "https://github.com/";
        if (html_url.startsWith(prefix)) {
            String sub = html_url.substring(prefix.length());
            String[] parts = sub.split("/");
            if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return parts[0] + "/" + parts[1];
            }
        }
        return null;
    }
}
