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
