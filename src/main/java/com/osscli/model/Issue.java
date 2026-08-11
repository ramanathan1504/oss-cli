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

    // Helper to dynamically extract the "owner/repo" from the html_url
    public String getRepositoryOwnerAndName() {
        if (html_url == null) {
            return "apache/logging-log4j2"; // Fallback default
        }
        // html_url looks like: https://github.com/owner/name/pull/number
        String prefix = "https://github.com/";
        if (html_url.startsWith(prefix)) {
            String sub = html_url.substring(prefix.length());
            String[] parts = sub.split("/");
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        return "apache/logging-log4j2";
    }
}
