package com.osscli.model;

public record PrMemory(String repository, long prNumber, String filesChanged, String generatedStory, double[] vector) {}
