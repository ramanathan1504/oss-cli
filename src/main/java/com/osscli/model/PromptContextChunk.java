package com.osscli.model;

public record PromptContextChunk(
        String sourceType, // 'issue', 'pr_memory', 'chat_memory', 'jira', 'cross_repo', 'stack_trace'
        String sourceRef, // e.g. '#1234', 'PR #56', 'chatgpt_export.json'
        String content,
        double relevanceScore,
        int tokenCount,
        boolean included // false = dropped due to token budget
        ) {}
