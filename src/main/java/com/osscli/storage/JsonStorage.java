package com.osscli.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.osscli.model.AiAnalysisResult;
import com.osscli.model.Issue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class JsonStorage {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path HISTORY_DIR = Paths.get("history");

    private static final Path DATA_DIR = Paths.get("data");

    public static void saveIssues(List<Issue> issues) throws IOException {
        Files.createDirectories(DATA_DIR);
        Path filePath = DATA_DIR.resolve("issues.json");
        MAPPER.writeValue(filePath.toFile(), issues);
    }

    public static void savePullRequests(List<Issue> prs) throws IOException {
        Files.createDirectories(DATA_DIR);
        Path filePath = DATA_DIR.resolve("prs.json");
        MAPPER.writeValue(filePath.toFile(), prs);
    }

    public static List<Issue> loadIssues() throws IOException {
        Path filePath = DATA_DIR.resolve("issues.json");
        if (!Files.exists(filePath)) {
            return List.of();
        }
        return MAPPER.readValue(
                filePath.toFile(), MAPPER.getTypeFactory().constructCollectionType(List.class, Issue.class));
    }

    public static List<Issue> loadPullRequests() throws IOException {
        Path filePath = DATA_DIR.resolve("prs.json");
        if (!Files.exists(filePath)) {
            return List.of();
        }
        return MAPPER.readValue(
                filePath.toFile(), MAPPER.getTypeFactory().constructCollectionType(List.class, Issue.class));
    }

    public static void saveAiAnalysis(List<AiAnalysisResult> results) throws java.io.IOException {
        Files.createDirectories(DATA_DIR);
        Path filePath = DATA_DIR.resolve("ai-analysis.json");
        MAPPER.writeValue(filePath.toFile(), results);
    }

    public static List<AiAnalysisResult> loadAiAnalysis() throws java.io.IOException {
        Path filePath = DATA_DIR.resolve("ai-analysis.json");
        if (!Files.exists(filePath)) {
            return List.of();
        }
        return MAPPER.readValue(
                filePath.toFile(), MAPPER.getTypeFactory().constructCollectionType(List.class, AiAnalysisResult.class));
    }

    public static void saveEmbeddings(List<com.osscli.model.IssueEmbedding> results) throws java.io.IOException {
        Files.createDirectories(DATA_DIR);
        Path filePath = DATA_DIR.resolve("embeddings.json");
        MAPPER.writeValue(filePath.toFile(), results);
    }

    public static List<com.osscli.model.IssueEmbedding> loadEmbeddings() throws java.io.IOException {
        Path filePath = DATA_DIR.resolve("embeddings.json");
        if (!Files.exists(filePath)) {
            return List.of();
        }
        return MAPPER.readValue(
                filePath.toFile(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, com.osscli.model.IssueEmbedding.class));
    }

    public static void saveTrendSnapshot(com.osscli.model.TrendSnapshot snapshot) throws java.io.IOException {
        Files.createDirectories(HISTORY_DIR);
        Path filePath = HISTORY_DIR.resolve(snapshot.date() + ".json");
        MAPPER.writeValue(filePath.toFile(), snapshot);
    }

    public static List<com.osscli.model.TrendSnapshot> loadTrendSnapshots() throws java.io.IOException {
        if (!Files.exists(HISTORY_DIR)) {
            return List.of();
        }
        try (var stream = Files.list(HISTORY_DIR)) {
            List<Path> files =
                    stream.filter(path -> path.toString().endsWith(".json")).toList();

            List<com.osscli.model.TrendSnapshot> snapshots = new ArrayList<>();
            for (Path p : files) {
                try {
                    snapshots.add(MAPPER.readValue(p.toFile(), com.osscli.model.TrendSnapshot.class));
                } catch (Exception ignored) {
                    // Ignore malformed snapshot JSONs
                }
            }
            // Sort chronologically by date
            snapshots.sort(java.util.Comparator.comparing(com.osscli.model.TrendSnapshot::date));
            return snapshots;
        }
    }
}
