package com.osscli.model;

public record JiraBridgeLink(long localNumber, String externalRepo, long externalNumber, String jiraKey) {}
