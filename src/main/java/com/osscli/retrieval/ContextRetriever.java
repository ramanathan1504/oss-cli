package com.osscli.retrieval;

import com.osscli.model.ChatMemory;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.JiraBridgeLink;
import com.osscli.model.Label;
import com.osscli.model.PrMemory;
import com.osscli.model.PromptContextChunk;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ContextRetriever {

    private static final int TOKEN_BUDGET = 6000;

    /**
     * Floor for treating a passage as related at all. Configurable via {@code
     * retrieval.similarity_threshold}, because the right value depends on the corpus.
     *
     * <p>Measured on a real 16,667-passage corpus rather than guessed. Across 133k issue-passage
     * pairs the noise distribution ran p50 0.163, p90 0.384, p99 0.542, while verified correct
     * matches scored 0.599 to 0.942. The previous 0.35 therefore sat at the 90th percentile of
     * NOISE and admitted two to four thousand passages per issue. 0.50 sits above the 98th
     * percentile of noise and below every verified true positive.
     *
     * <p>It also matters for issues the corpus does not cover: those peaked at 0.398 and 0.408, so
     * a 0.50 floor contributes nothing for them rather than a handful of weak passages. Feeding
     * nothing lets the model escalate honestly; feeding noise invites a confident wrong answer.
     *
     * <p>To retune for another corpus, compare the score distribution of random issue-passage pairs
     * against known-correct ones and pick a value that separates them.
     */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.50;

    private static final Pattern STACK_TRACE_PATTERN =
            Pattern.compile("(?m)(^\\s*at [\\w$.]+\\([\\w$.]+:\\d+\\).*$|^.*Exception.*$)", Pattern.MULTILINE);

    /**
     * Assembles the context for one issue.
     *
     * <p>Candidates are gathered from every source, then ranked by relevance ACROSS sources before
     * the token budget is applied. Collection order must not decide what survives: the retriever
     * previously filled the budget section by section, so a weakly-related PR memory scoring 0.35
     * displaced a chat memory scoring 0.55 purely because PR memories were gathered first. On a
     * real issue that starved the entire notes corpus -- 125 matching chat memories, none included
     * -- and the model answered from whatever loosely-related material had reached it.
     *
     * <p>The target issue is the exception: it is the subject, not a candidate, so it is always
     * included and charged against the budget first.
     */
    public static List<PromptContextChunk> retrieve(long issueNumber, String repository) throws Exception {
        List<PromptContextChunk> mandatory = new ArrayList<>();
        List<PromptContextChunk> candidates = new ArrayList<>();
        final double SIMILARITY_THRESHOLD = similarityThreshold();

        // ── 1. The issue itself ──────────────────────────────────────────────
        List<Issue> issues = SqliteStorage.loadIssues(repository);
        Issue target = issues.stream()
                .filter(i -> i.number() == issueNumber)
                .findFirst()
                .orElse(null);
        if (target == null) {
            List<Issue> prs = SqliteStorage.loadPullRequests(repository);
            target = prs.stream()
                    .filter(p -> p.number() == issueNumber)
                    .findFirst()
                    .orElse(null);
        }

        if (target != null) {
            String labelStr = target.labels() == null
                    ? ""
                    : target.labels().stream()
                            .map(Label::name)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
            String issueContent = String.format(
                    "Title: %s\nState: %s\nLabels: %s\nCreated: %s\n\nBody:\n%s",
                    target.title(), target.state(), labelStr, target.created_at(), target.body());
            int tokens = estimateTokens(issueContent);
            mandatory.add(new PromptContextChunk("issue", "#" + issueNumber, issueContent, 1.0, tokens, true));

            // ── 2. Stack trace extracted from issue body ─────────────────────
            if (target.body() != null) {
                String stackTrace = extractStackTrace(target.body());
                if (!stackTrace.isEmpty()) {
                    int stTokens = estimateTokens(stackTrace);
                    candidates.add(
                            new PromptContextChunk("stack_trace", "#" + issueNumber, stackTrace, 1.0, stTokens, false));
                }
            }

            // ── 3. Related issues by label overlap ───────────────────────────
            if (target.labels() != null && !target.labels().isEmpty()) {
                List<String> targetLabels =
                        target.labels().stream().map(Label::name).toList();
                int relatedAdded = 0;
                for (Issue other : issues) {
                    if (other.number() == issueNumber || other.labels() == null) continue;
                    boolean sharesLabel =
                            other.labels().stream().map(Label::name).anyMatch(targetLabels::contains);
                    if (sharesLabel && relatedAdded < 5) {
                        String relContent = String.format("#%d [%s] %s", other.number(), other.state(), other.title());
                        int relTokens = estimateTokens(relContent);
                        candidates.add(new PromptContextChunk(
                                "related_issue", "#" + other.number(), relContent, 0.7, relTokens, false));
                        relatedAdded++;
                    }
                }
            }
        }

        // ── 4. Load vector for similarity lookups ────────────────────────────
        List<IssueEmbedding> embeddings = SqliteStorage.loadEmbeddings(repository);
        double[] targetVector = embeddings.stream()
                .filter(e -> e.issueNumber() == issueNumber)
                .map(IssueEmbedding::vector)
                .findFirst()
                .orElse(null);

        if (targetVector != null) {
            // ── 5. Personal PR memories (similar past fixes) ─────────────────
            for (PrMemory prMem : SqliteStorage.loadAllPersonalPrMemories()) {
                if (prMem.vector() == null) continue;
                double sim = cosineSimilarity(targetVector, prMem.vector());
                if (sim >= SIMILARITY_THRESHOLD) {
                    String content =
                            String.format("Files Changed: %s\nStory: %s", prMem.filesChanged(), prMem.generatedStory());
                    int tokens = estimateTokens(content);
                    candidates.add(new PromptContextChunk(
                            "pr_memory",
                            "PR #" + prMem.prNumber() + " (" + prMem.repository() + ")",
                            content,
                            sim,
                            tokens,
                            false));
                }
            }

            // ── 6. Personal chat memories, matched at passage level ──────────
            //
            // Scoring passages rather than whole notes is what makes the middle of a
            // long note findable. Only the best-matching passage per note is kept:
            // without that, one long note whose passages all score similarly would
            // crowd every other source out of the budget.
            //
            // Falls back to note-level vectors when no passages exist yet, so a
            // database that has not been re-synced since chunking arrived still works.
            List<SqliteStorage.ChatChunk> chunkVectors = SqliteStorage.loadPersonalChatChunkVectors();
            if (!chunkVectors.isEmpty()) {
                Map<String, SqliteStorage.ChatChunk> bestPerFile = new HashMap<>();
                Map<String, Double> bestScore = new HashMap<>();
                for (SqliteStorage.ChatChunk ch : chunkVectors) {
                    double sim = cosineSimilarity(targetVector, ch.vector());
                    if (sim < SIMILARITY_THRESHOLD) continue;
                    Double prev = bestScore.get(ch.filePath());
                    if (prev == null || sim > prev) {
                        bestScore.put(ch.filePath(), sim);
                        bestPerFile.put(ch.filePath(), ch);
                    }
                }
                // Deduplicate by CONTENT, not just by note. The corpus holds genuinely
                // duplicated notes -- the same conversation saved under several names --
                // so best-per-note still yielded the identical paragraph five times over,
                // measured at ~1,100 tokens of a 6,000 budget spent re-reading one answer.
                Set<String> seenPassages = new HashSet<>();
                for (Map.Entry<String, SqliteStorage.ChatChunk> e : bestPerFile.entrySet()) {
                    SqliteStorage.ChatChunk ch = e.getValue();
                    String passage = SqliteStorage.loadPersonalChatChunkContent(ch.filePath(), ch.chunkIndex());
                    if (passage == null || passage.isBlank()) continue;
                    if (!seenPassages.add(passage.strip())) continue;
                    String label = SqliteStorage.loadPersonalChatFileName(ch.filePath());
                    if (label == null) label = ch.filePath();
                    candidates.add(new PromptContextChunk(
                            "chat_memory",
                            label + " (passage " + (ch.chunkIndex() + 1) + ")",
                            passage,
                            bestScore.get(ch.filePath()),
                            estimateTokens(passage),
                            false));
                }
            } else {
                for (ChatMemory chatMem : SqliteStorage.loadAllPersonalChatMemories()) {
                    if (chatMem.vector() == null) continue;
                    double sim = cosineSimilarity(targetVector, chatMem.vector());
                    if (sim >= SIMILARITY_THRESHOLD) {
                        String content = chatMem.content().length() > 800
                                ? chatMem.content().substring(0, 800) + "..."
                                : chatMem.content();
                        int tokens = estimateTokens(content);
                        candidates.add(
                                new PromptContextChunk("chat_memory", chatMem.fileName(), content, sim, tokens, false));
                    }
                }
            }
        }

        // ── 7. JIRA bridge mentions ───────────────────────────────────────────
        for (JiraBridgeLink jira : SqliteStorage.loadJiraBridges(repository)) {
            if (jira.localNumber() == issueNumber) {
                String content = "JIRA Key: " + jira.jiraKey() + " (linked to " + jira.externalRepo() + " #"
                        + jira.externalNumber() + ")";
                int tokens = estimateTokens(content);
                candidates.add(new PromptContextChunk("jira", jira.jiraKey(), content, 0.8, tokens, false));
            }
        }

        // ── 8. Cross-repo inbound links ───────────────────────────────────────
        for (String link : SqliteStorage.loadInboundLinks(repository)) {
            String content = "Referenced by: " + link;
            int tokens = estimateTokens(content);
            candidates.add(new PromptContextChunk("cross_repo", link, content, 0.6, tokens, false));
        }

        return rankAndFill(mandatory, candidates);
    }

    /**
     * Ranks every candidate by relevance across all sources, then fills the token budget in that
     * order. Mandatory chunks are charged first and always survive.
     *
     * <p>The sort is stable, so chunks of equal relevance keep the order they were gathered in --
     * collection order remains the tie-break, it just no longer overrides relevance. A chunk too
     * large for the remaining budget is skipped rather than terminating the fill, so smaller
     * high-value chunks behind it still get in.
     */
    private static List<PromptContextChunk> rankAndFill(
            List<PromptContextChunk> mandatory, List<PromptContextChunk> candidates) {
        List<PromptContextChunk> out = new ArrayList<>();
        int usedTokens = 0;

        for (PromptContextChunk c : mandatory) {
            out.add(c);
            usedTokens += c.tokenCount();
        }

        List<PromptContextChunk> ranked = new ArrayList<>(candidates);
        ranked.sort(
                Comparator.comparingDouble(PromptContextChunk::relevanceScore).reversed());

        for (PromptContextChunk c : ranked) {
            boolean fits = usedTokens + c.tokenCount() <= TOKEN_BUDGET;
            if (fits) {
                usedTokens += c.tokenCount();
            }
            out.add(new PromptContextChunk(
                    c.sourceType(), c.sourceRef(), c.content(), c.relevanceScore(), c.tokenCount(), fits));
        }
        return out;
    }

    private static double similarityThreshold() {
        try {
            String configured = SqliteStorage.loadConfig("retrieval.similarity_threshold");
            if (configured != null && !configured.isBlank()) {
                double v = Double.parseDouble(configured.trim());
                if (v > 0.0 && v < 1.0) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // fall through to the measured default
        }
        return DEFAULT_SIMILARITY_THRESHOLD;
    }

    public static int totalTokens(List<PromptContextChunk> chunks) {
        return chunks.stream()
                .filter(PromptContextChunk::included)
                .mapToInt(PromptContextChunk::tokenCount)
                .sum();
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    private static String extractStackTrace(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = STACK_TRACE_PATTERN.matcher(body);
        int count = 0;
        while (m.find() && count < 20) {
            sb.append(m.group().strip()).append("\n");
            count++;
        }
        return sb.toString().strip();
    }

    private static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
