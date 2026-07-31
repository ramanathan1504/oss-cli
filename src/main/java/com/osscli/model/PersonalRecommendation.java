package com.osscli.model;

import com.osscli.analyzer.Severity;

public record PersonalRecommendation(Issue issue, double personalScore, double similarity, Severity baseSeverity) {}
