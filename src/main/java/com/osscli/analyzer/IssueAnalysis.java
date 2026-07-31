package com.osscli.analyzer;

import com.osscli.model.Issue;

public record IssueAnalysis(Issue issue, Severity severity, int score, String reason) {}
