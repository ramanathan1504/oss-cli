package com.osscli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAnalysisResult(long issueNumber, String severity, double confidence, String reason) {}
