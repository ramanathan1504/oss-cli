package com.osscli.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.model.AiAnalysisResult;
import com.osscli.model.Issue;
import com.osscli.model.IssueEmbedding;
import com.osscli.model.Label;
import com.osscli.model.PrEvidence;
import com.osscli.model.PullRequestMarker;
import com.osscli.model.RepoIssue;
import com.osscli.model.TrendSnapshot;
import com.osscli.model.User;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqliteStorage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Regex patterns for Ecosystem Dependency Analysis
    private static final Pattern CROSS_REPO_PATTERN = Pattern.compile("([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)#(\\d+)");
    private static final Pattern INTERNAL_REF_PATTERN = Pattern.compile("\\b#(\\d+)\\b");
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("\\b([A-Z]+-\\d+)\\b");

    // ==========================================
    // 1. Issues & Pull Requests Operations
    // ==========================================

    public static void saveIssues(String repository, List<Issue> issues) throws SQLException {
        if (issues == null || issues.isEmpty()) {
            return;
        }

        String insertIssueSql =
                """
                INSERT OR REPLACE INTO issues (
                    repository, number, title, body, state, comments, created_at, updated_at, is_pull_request, author, author_association
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;

        String deleteLabelsSql = "DELETE FROM labels WHERE repository = ? AND issue_number = ?;";
        String insertLabelSql = "INSERT INTO labels (repository, issue_number, label_name) VALUES (?, ?, ?);";

        String deleteLinksSql = "DELETE FROM cross_repo_links WHERE source_repo = ? AND source_number = ?;";
        String insertLinkSql =
                """
                INSERT OR REPLACE INTO cross_repo_links (
                    source_repo, source_number, target_repo, target_number, link_type
                ) VALUES (?, ?, ?, ?, ?);
                """;

        String deleteJiraSql = "DELETE FROM jira_mentions WHERE repository = ? AND issue_number = ?;";
        String insertJiraSql =
                "INSERT OR REPLACE INTO jira_mentions (repository, issue_number, jira_key) VALUES (?, ?, ?);";

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement psIssue = conn.prepareStatement(insertIssueSql);
                    PreparedStatement psDelLabels = conn.prepareStatement(deleteLabelsSql);
                    PreparedStatement psLabel = conn.prepareStatement(insertLabelSql);
                    PreparedStatement psDelLinks = conn.prepareStatement(deleteLinksSql);
                    PreparedStatement psLink = conn.prepareStatement(insertLinkSql);
                    PreparedStatement psDelJira = conn.prepareStatement(deleteJiraSql);
                    PreparedStatement psJira = conn.prepareStatement(insertJiraSql)) {

                for (Issue issue : issues) {
                    // A. Save Issue
                    psIssue.setString(1, repository);
                    psIssue.setLong(2, issue.number());
                    psIssue.setString(3, issue.title());
                    psIssue.setString(4, issue.body());
                    psIssue.setString(5, issue.state());
                    psIssue.setInt(6, issue.comments());
                    psIssue.setString(7, issue.created_at());
                    psIssue.setString(8, issue.updated_at());
                    psIssue.setBoolean(9, issue.isPullRequest());
                    psIssue.setString(10, issue.user() != null ? issue.user().login() : null);
                    psIssue.setString(11, issue.author_association());
                    psIssue.addBatch();

                    // B. Rebuild Labels
                    psDelLabels.setString(1, repository);
                    psDelLabels.setLong(2, issue.number());
                    psDelLabels.addBatch();

                    if (issue.labels() != null) {
                        for (Label label : issue.labels()) {
                            if (label != null && label.name() != null) {
                                psLabel.setString(1, repository);
                                psLabel.setLong(2, issue.number());
                                psLabel.setString(3, label.name());
                                psLabel.addBatch();
                            }
                        }
                    }

                    // C. Auto-Extract Dependency Links
                    psDelLinks.setString(1, repository);
                    psDelLinks.setLong(2, issue.number());
                    psDelLinks.addBatch();

                    String textToScan = issue.title() + " " + (issue.body() == null ? "" : issue.body());

                    // Match external links (e.g. apache/kafka#1234)
                    Matcher crossMatcher = CROSS_REPO_PATTERN.matcher(textToScan);
                    while (crossMatcher.find()) {
                        String targetRepo = crossMatcher.group(1);
                        long targetNum = Long.parseLong(crossMatcher.group(2));

                        psLink.setString(1, repository);
                        psLink.setLong(2, issue.number());
                        psLink.setString(3, targetRepo);
                        psLink.setLong(4, targetNum);
                        psLink.setString(5, "EXPLICIT_REFERENCE");
                        psLink.addBatch();
                    }

                    // Match internal links (e.g. #4567 -> referring same repo)
                    Matcher internalMatcher = INTERNAL_REF_PATTERN.matcher(textToScan);
                    while (internalMatcher.find()) {
                        long targetNum = Long.parseLong(internalMatcher.group(1));
                        if (targetNum != issue.number()) {
                            psLink.setString(1, repository);
                            psLink.setLong(2, issue.number());
                            psLink.setString(3, repository);
                            psLink.setLong(4, targetNum);
                            psLink.setString(5, "EXPLICIT_REFERENCE");
                            psLink.addBatch();
                        }
                    }

                    // D. Rebuild JIRA Mentions
                    psDelJira.setString(1, repository);
                    psDelJira.setLong(2, issue.number());
                    psDelJira.addBatch();

                    Matcher jiraMatcher = JIRA_KEY_PATTERN.matcher(textToScan);
                    while (jiraMatcher.find()) {
                        String jiraKey = jiraMatcher.group(1);
                        psJira.setString(1, repository);
                        psJira.setLong(2, issue.number());
                        psJira.setString(3, jiraKey);
                        psJira.addBatch();
                    }
                }

                psIssue.executeBatch();
                psDelLabels.executeBatch();
                psLabel.executeBatch();
                psDelLinks.executeBatch();
                psLink.executeBatch();
                psDelJira.executeBatch();
                psJira.executeBatch();

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public static List<Issue> loadIssues(String repository) throws SQLException {
        return loadIssuesInternal(repository, false);
    }

    public static List<Issue> loadPullRequests(String repository) throws SQLException {
        return loadIssuesInternal(repository, true);
    }

    private static List<Issue> loadIssuesInternal(String repository, boolean isPullRequest) throws SQLException {
        String queryIssuesSql = "SELECT * FROM issues WHERE repository = ? AND is_pull_request = ?;";
        String queryLabelsSql = "SELECT issue_number, label_name FROM labels WHERE repository = ?;";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement psIssues = conn.prepareStatement(queryIssuesSql);
                PreparedStatement psLabels = conn.prepareStatement(queryLabelsSql)) {

            // A. Fetch labels map
            psLabels.setString(1, repository);
            Map<Long, List<Label>> labelsMap = new HashMap<>();
            try (ResultSet rsLabels = psLabels.executeQuery()) {
                while (rsLabels.next()) {
                    long issueNum = rsLabels.getLong("issue_number");
                    String labelName = rsLabels.getString("label_name");
                    labelsMap.computeIfAbsent(issueNum, k -> new ArrayList<>()).add(new Label(labelName));
                }
            }

            // B. Fetch issues
            psIssues.setString(1, repository);
            psIssues.setBoolean(2, isPullRequest);
            List<Issue> results = new ArrayList<>();
            try (ResultSet rs = psIssues.executeQuery()) {
                while (rs.next()) {
                    long num = rs.getLong("number");
                    List<Label> labelsList = labelsMap.getOrDefault(num, List.of());

                    Issue issue = new Issue(
                            num,
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getString("state"),
                            rs.getInt("comments"),
                            rs.getString("created_at"),
                            rs.getString("updated_at"),
                            rs.getBoolean("is_pull_request") ? new PullRequestMarker() : null,
                            labelsList,
                            new User(rs.getString("author")),
                            rs.getString("author_association"),
                            repository // Pass repository string as html_url context
                            );
                    results.add(issue);
                }
            }
            return results;
        }
    }

    // ==========================================
    // 2. AI Analysis Operations
    // ==========================================

    public static void saveAiAnalysis(String repository, List<AiAnalysisResult> results) throws SQLException {
        if (results == null || results.isEmpty()) {
            return;
        }

        String sql =
                "INSERT OR REPLACE INTO ai_analysis (repository, issue_number, severity, confidence, reason) VALUES (?, ?, ?, ?, ?);";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (AiAnalysisResult r : results) {
                ps.setString(1, repository);
                ps.setLong(2, r.issueNumber());
                ps.setString(3, r.severity());
                ps.setDouble(4, r.confidence());
                ps.setString(5, r.reason());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        }
    }

    public static List<AiAnalysisResult> loadAiAnalysis(String repository) throws SQLException {
        String sql = "SELECT * FROM ai_analysis WHERE repository = ?;";
        List<AiAnalysisResult> results = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new AiAnalysisResult(
                            rs.getLong("issue_number"),
                            rs.getString("severity"),
                            rs.getDouble("confidence"),
                            rs.getString("reason")));
                }
            }
        }
        return results;
    }

    // ==========================================
    // 3. Vector Embeddings Operations
    // ==========================================

    public static void saveEmbeddings(String repository, List<IssueEmbedding> results)
            throws SQLException, IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        saveEmbeddings(repository, results, null);
    }

    /**
     * @param embeddingModel the model that produced these vectors; recorded so a later model
     *     swap is detectable instead of silently mixing incomparable coordinate spaces.
     */
    public static void saveEmbeddings(String repository, List<IssueEmbedding> results, String embeddingModel)
            throws SQLException, IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        String sql =
                "INSERT OR REPLACE INTO embeddings (repository, issue_number, vector, embedding_model, embedding_dim) VALUES (?, ?, ?, ?, ?);";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (IssueEmbedding emb : results) {
                ps.setString(1, repository);
                ps.setLong(2, emb.issueNumber());
                String jsonVector = MAPPER.writeValueAsString(emb.vector());
                ps.setString(3, jsonVector);
                ps.setString(4, embeddingModel);
                ps.setInt(5, emb.vector() == null ? 0 : emb.vector().length);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        }
    }

    /**
     * Issue numbers in {@code repository} that already carry a vector from {@code embeddingModel}.
     *
     * <p>Returns numbers rather than vectors because the caller only needs to know what is missing, and a repository
     * with thousands of issues would otherwise pull thousands of full vectors off disk to answer a set-membership
     * question.
     *
     * <p>Rows written by a different model are reported as absent. Vectors from two models occupy unrelated coordinate
     * spaces, so keeping the stale ones would mean ranking distances that cannot be compared. Rows predating the
     * {@code embedding_model} column (written as NULL) are treated the same way -- unknown provenance is not a match.
     */
    public static Set<Long> loadEmbeddedIssueNumbers(String repository, String embeddingModel) throws SQLException {
        String sql = "SELECT issue_number FROM embeddings WHERE repository = ? AND embedding_model = ?;";
        Set<Long> numbers = new HashSet<>();

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setString(2, embeddingModel);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    numbers.add(rs.getLong("issue_number"));
                }
            }
        }
        return numbers;
    }

    // ==========================================
    // Repository profile
    // ==========================================

    public static void saveRepoProfile(com.osscli.model.RepoProfile p) throws SQLException {
        String sql = "INSERT OR REPLACE INTO repo_profile (repository, primary_language, build_system, "
                + "target_version, min_version, conventions_json, docs_json, summary, built_at) "
                + "VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.repository());
            ps.setString(2, p.primaryLanguage());
            ps.setString(3, p.buildSystem());
            ps.setString(4, p.targetVersion());
            ps.setString(5, p.minVersion());
            ps.setString(6, p.conventionsJson());
            ps.setString(7, p.docsJson());
            ps.setString(8, p.summary());
            ps.executeUpdate();
        }
    }

    public static com.osscli.model.RepoProfile loadRepoProfile(String repository) throws SQLException {
        String sql = "SELECT * FROM repo_profile WHERE repository = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new com.osscli.model.RepoProfile(
                        rs.getString("repository"),
                        rs.getString("primary_language"),
                        rs.getString("build_system"),
                        rs.getString("target_version"),
                        rs.getString("min_version"),
                        rs.getString("conventions_json"),
                        rs.getString("docs_json"),
                        rs.getString("summary"));
            }
        }
    }

    // ==========================================
    // Pull request evidence cache
    // ==========================================

    public static void savePrEvidence(PrEvidence e) throws SQLException {
        String sql = "INSERT OR REPLACE INTO pr_cache (repository, pr_number, head_sha, title, author, state, "
                + "base_ref, body, commits_json, files_json, diff, reviews_json, comments_json, checks_json, "
                + "additions, deletions, changed_files, fetched_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP);";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.repository());
            ps.setLong(2, e.prNumber());
            ps.setString(3, e.headSha());
            ps.setString(4, e.title());
            ps.setString(5, e.author());
            ps.setString(6, e.state());
            ps.setString(7, e.baseRef());
            ps.setString(8, e.body());
            ps.setString(9, e.commitsJson());
            ps.setString(10, e.filesJson());
            ps.setString(11, e.diff());
            ps.setString(12, e.reviewsJson());
            ps.setString(13, e.commentsJson());
            ps.setString(14, e.checksJson());
            ps.setInt(15, e.additions());
            ps.setInt(16, e.deletions());
            ps.setInt(17, e.changedFiles());
            ps.executeUpdate();
        }
    }

    /** Cached evidence for this exact commit, or null. A different head SHA is a miss, never a stale hit. */
    public static PrEvidence loadPrEvidence(String repository, long prNumber, String headSha) throws SQLException {
        String sql = "SELECT * FROM pr_cache WHERE repository = ? AND pr_number = ? AND head_sha = ?;";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setLong(2, prNumber);
            ps.setString(3, headSha);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PrEvidence(
                        rs.getString("repository"),
                        rs.getLong("pr_number"),
                        rs.getString("head_sha"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("state"),
                        rs.getString("base_ref"),
                        rs.getString("body"),
                        rs.getString("commits_json"),
                        rs.getString("files_json"),
                        rs.getString("diff"),
                        rs.getString("reviews_json"),
                        rs.getString("comments_json"),
                        rs.getString("checks_json"),
                        rs.getInt("additions"),
                        rs.getInt("deletions"),
                        rs.getInt("changed_files"));
            }
        }
    }

    public static List<IssueEmbedding> loadEmbeddings(String repository) throws SQLException, IOException {
        String sql = "SELECT * FROM embeddings WHERE repository = ?;";
        List<IssueEmbedding> results = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long num = rs.getLong("issue_number");
                    String jsonVector = rs.getString("vector");
                    double[] vector = MAPPER.readValue(jsonVector, double[].class);
                    results.add(new IssueEmbedding(repository, num, vector));
                }
            }
        }
        return results;
    }

    // ==========================================
    // 4. Historical Snapshots & Sync State Operations
    // ==========================================

    public static void saveTrendSnapshot(String repository, TrendSnapshot snapshot) throws SQLException {
        if (snapshot == null) {
            return;
        }

        String sql =
                """
                INSERT OR REPLACE INTO snapshots (
                    repository, date, critical_issues, high_priority, stale_prs, duplicate_clusters
                ) VALUES (?, ?, ?, ?, ?, ?);
                """;

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setString(2, snapshot.date());
            ps.setInt(3, snapshot.criticalIssues());
            ps.setInt(4, snapshot.highPriority());
            ps.setInt(5, snapshot.stalePrs());
            ps.setInt(6, snapshot.duplicateClusters());
            ps.executeUpdate();
        }
    }

    public static List<TrendSnapshot> loadTrendSnapshots(String repository) throws SQLException {
        String sql = "SELECT * FROM snapshots WHERE repository = ? ORDER BY date ASC;";
        List<TrendSnapshot> results = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new TrendSnapshot(
                            rs.getString("date"),
                            rs.getInt("critical_issues"),
                            rs.getInt("high_priority"),
                            rs.getInt("stale_prs"),
                            rs.getInt("duplicate_clusters")));
                }
            }
        }
        return results;
    }

    public static String loadLastSyncedAt(String repository) throws SQLException {
        String sql = "SELECT last_synced_at FROM monitored_repositories WHERE repository = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("last_synced_at");
                }
            }
        }
        return null;
    }

    public static void updateLastSyncedAt(String repository, String timestamp) throws SQLException {
        String sql = "UPDATE monitored_repositories SET last_synced_at = ? WHERE repository = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, timestamp);
            ps.setString(2, repository);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // 5. Ecosystem & Downstream Links Operations
    // ==========================================

    public static List<com.osscli.model.JiraBridgeLink> loadJiraBridges(String repository) throws SQLException {
        String sql =
                """
                SELECT a.issue_number AS local_number, b.repository AS external_repo, b.issue_number AS external_number, a.jira_key
                FROM jira_mentions a
                JOIN jira_mentions b ON a.jira_key = b.jira_key
                WHERE a.repository = ?
                  AND b.repository != ?
                  AND a.jira_key NOT IN (
                      'UTF-8', 'UTF-16', 'JDK-17', 'JDK-21', 'JDK-8', 'JDK-11',
                      'SHA-256', 'SHA-1', 'LICENSE-2', 'ISO-8859'
                  );
                """;
        List<com.osscli.model.JiraBridgeLink> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setString(2, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.osscli.model.JiraBridgeLink(
                            rs.getLong("local_number"),
                            rs.getString("external_repo"),
                            rs.getLong("external_number"),
                            rs.getString("jira_key")));
                }
            }
        }
        return results;
    }

    public static List<String> loadInboundLinks(String repository) throws SQLException {
        String sql =
                """
                SELECT source_repo, source_number, target_number
                FROM cross_repo_links
                WHERE target_repo = ? AND source_repo != ?;
                """;
        List<String> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setString(2, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(String.format(
                            "**%s#%d** references our Issue **#%d**",
                            rs.getString("source_repo"), rs.getLong("source_number"), rs.getLong("target_number")));
                }
            }
        }
        return results;
    }

    public static List<String> loadOutboundLinks(String repository) throws SQLException {
        String sql =
                """
                SELECT source_number, target_repo, target_number
                FROM cross_repo_links
                WHERE source_repo = ? AND target_repo != ?;
                """;
        List<String> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setString(2, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(String.format(
                            "Our Issue **#%d** references **%s#%d**",
                            rs.getLong("source_number"), rs.getString("target_repo"), rs.getLong("target_number")));
                }
            }
        }
        return results;
    }

    // ==========================================
    // 6. Monitored Repositories Operations
    // ==========================================

    public static List<String> loadMonitoredRepositories() throws SQLException {
        String sql = "SELECT repository FROM monitored_repositories WHERE enabled = 1;";
        List<String> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                results.add(rs.getString("repository"));
            }
        }
        return results;
    }

    public static void saveMonitoredRepository(String repository, boolean enabled) throws SQLException {
        String sql = "INSERT OR REPLACE INTO monitored_repositories (repository, enabled) VALUES (?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setBoolean(2, enabled);
            ps.executeUpdate();
        }
    }

    public static void deleteMonitoredRepository(String repository) throws SQLException {
        String sql = "DELETE FROM monitored_repositories WHERE repository = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // 7. Global Multi-Repo Operations
    // ==========================================

    public static List<IssueEmbedding> loadAllEmbeddings() throws SQLException, IOException {
        String sql = "SELECT * FROM embeddings;";
        List<IssueEmbedding> results = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String repo = rs.getString("repository");
                long num = rs.getLong("issue_number");
                String jsonVector = rs.getString("vector");
                double[] vector = MAPPER.readValue(jsonVector, double[].class);
                results.add(new IssueEmbedding(repo, num, vector));
            }
        }
        return results;
    }

    public static List<RepoIssue> loadAllIssues() throws SQLException {
        return loadAllIssuesInternal(false);
    }

    public static List<RepoIssue> loadAllPullRequests() throws SQLException {
        return loadAllIssuesInternal(true);
    }

    private static List<RepoIssue> loadAllIssuesInternal(boolean isPullRequest) throws SQLException {
        String queryIssuesSql = "SELECT * FROM issues WHERE is_pull_request = ?;";
        String queryLabelsSql = "SELECT repository, issue_number, label_name FROM labels;";

        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement psIssues = conn.prepareStatement(queryIssuesSql);
                PreparedStatement psLabels = conn.prepareStatement(queryLabelsSql)) {

            // Fetch and group labels in memory by composite key (repository + "_" + issue_number)
            Map<String, List<Label>> labelsMap = new HashMap<>();
            try (ResultSet rsLabels = psLabels.executeQuery()) {
                while (rsLabels.next()) {
                    String repo = rsLabels.getString("repository");
                    long issueNum = rsLabels.getLong("issue_number");
                    String labelName = rsLabels.getString("label_name");
                    labelsMap
                            .computeIfAbsent(repo + "_" + issueNum, k -> new ArrayList<>())
                            .add(new Label(labelName));
                }
            }

            psIssues.setBoolean(1, isPullRequest);
            List<RepoIssue> results = new ArrayList<>();
            try (ResultSet rs = psIssues.executeQuery()) {
                while (rs.next()) {
                    long num = rs.getLong("number");
                    String repo = rs.getString("repository");
                    List<Label> labelsList = labelsMap.getOrDefault(repo + "_" + num, List.of());

                    // Reconstruct Issue passing the repository column as the 12th parameter (html_url)
                    Issue issue = new Issue(
                            num,
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getString("state"),
                            rs.getInt("comments"),
                            rs.getString("created_at"),
                            rs.getString("updated_at"),
                            rs.getBoolean("is_pull_request") ? new PullRequestMarker() : null,
                            labelsList,
                            new User(rs.getString("author")),
                            rs.getString("author_association"),
                            repo // Pass repository string as html_url context
                            );
                    results.add(new RepoIssue(repo, issue));
                }
            }
            return results;
        }
    }

    // ==========================================
    // 8. System Configuration Operations
    // ==========================================

    public static String loadConfig(String key) throws SQLException {
        String sql = "SELECT value FROM system_config WHERE key = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        }
        return null;
    }

    public static void saveConfig(String key, String value) throws SQLException {
        String sql = "INSERT OR REPLACE INTO system_config (key, value) VALUES (?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ==========================================
    // 9. Personal Code Footprint Operations
    // ==========================================

    public static void savePersonalCodeFootprint(String repository, long issueNumber, List<String> filePaths)
            throws SQLException {
        String deleteSql = "DELETE FROM personal_code_footprint WHERE repository = ? AND issue_number = ?;";
        String insertSql =
                "INSERT OR REPLACE INTO personal_code_footprint (repository, issue_number, file_path) VALUES (?, ?, ?);";

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psDel = conn.prepareStatement(deleteSql);
                    PreparedStatement psIns = conn.prepareStatement(insertSql)) {

                psDel.setString(1, repository);
                psDel.setLong(2, issueNumber);
                psDel.executeUpdate();

                for (String path : filePaths) {
                    psIns.setString(1, repository);
                    psIns.setLong(2, issueNumber);
                    psIns.setString(3, path);
                    psIns.addBatch();
                }

                psIns.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public static List<String> loadPersonalCodeFootprint(String repository) throws SQLException {
        String sql = "SELECT DISTINCT file_path FROM personal_code_footprint WHERE repository = ?;";
        List<String> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getString("file_path"));
                }
            }
        }
        return results;
    }
    // ==========================================
    // 10. Personal Developer Memory Operations
    // ==========================================

    public static boolean hasPersonalPrMemory(String repository, long prNumber) throws SQLException {
        String sql = "SELECT 1 FROM personal_pr_memory WHERE repository = ? AND pr_number = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setLong(2, prNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void savePersonalPrMemory(
            String repository, long prNumber, String filesChanged, String generatedStory, double[] vector)
            throws SQLException, IOException {
        savePersonalPrMemory(repository, prNumber, filesChanged, generatedStory, vector, null);
    }

    public static void savePersonalPrMemory(
            String repository,
            long prNumber,
            String filesChanged,
            String generatedStory,
            double[] vector,
            String embeddingModel)
            throws SQLException, IOException {
        String sql =
                "INSERT OR REPLACE INTO personal_pr_memory (repository, pr_number, files_changed, generated_story, vector, embedding_model, embedding_dim) VALUES (?, ?, ?, ?, ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repository);
            ps.setLong(2, prNumber);
            ps.setString(3, filesChanged);
            ps.setString(4, generatedStory);
            ps.setString(5, MAPPER.writeValueAsString(vector));
            ps.setString(6, embeddingModel);
            ps.setInt(7, vector == null ? 0 : vector.length);
            ps.executeUpdate();
        }
    }

    public static long loadPersonalChatLastModified(String filePath) throws SQLException {
        String sql = "SELECT last_modified FROM personal_chat_memory WHERE file_path = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("last_modified");
                }
            }
        }
        return -1;
    }

    public static void savePersonalChatMemory(
            String filePath, String fileName, long lastModified, String content, double[] vector, String embeddingModel)
            throws SQLException, IOException {
        String sql =
                "INSERT OR REPLACE INTO personal_chat_memory (file_path, file_name, last_modified, content, vector, embedding_model, embedding_dim) VALUES (?, ?, ?, ?, ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setString(2, fileName);
            ps.setLong(3, lastModified);
            ps.setString(4, content);
            ps.setString(5, MAPPER.writeValueAsString(vector));
            ps.setString(6, embeddingModel);
            ps.setInt(7, vector == null ? 0 : vector.length);
            ps.executeUpdate();
        }
    }

    /**
     * The embedding model that produced the stored vector for this file, or null when the row
     * predates provenance tracking. Null is treated as stale so the row is re-embedded.
     */
    public static String loadPersonalChatEmbeddingModel(String filePath) throws SQLException {
        String sql = "SELECT embedding_model FROM personal_chat_memory WHERE file_path = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("embedding_model") : null;
            }
        }
    }

    /** One embedded passage of a note: which note it came from, and where in it. */
    public record ChatChunk(String filePath, int chunkIndex, double[] vector) {}

    /** Replaces every stored passage for one note. Chunk counts change as notes are edited. */
    public static void savePersonalChatChunks(
            String filePath, List<String> passages, List<double[]> vectors, String embeddingModel)
            throws SQLException, IOException {
        String del = "DELETE FROM personal_chat_chunk WHERE file_path = ?;";
        String ins =
                "INSERT OR REPLACE INTO personal_chat_chunk (file_path, chunk_index, content, vector, embedding_model, embedding_dim) VALUES (?, ?, ?, ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psDel = conn.prepareStatement(del)) {
                psDel.setString(1, filePath);
                psDel.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                for (int i = 0; i < passages.size(); i++) {
                    double[] v = vectors.get(i);
                    ps.setString(1, filePath);
                    ps.setInt(2, i);
                    ps.setString(3, passages.get(i));
                    ps.setString(4, MAPPER.writeValueAsString(v));
                    ps.setString(5, embeddingModel);
                    ps.setInt(6, v == null ? 0 : v.length);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    /**
     * Loads passage vectors WITHOUT their text.
     *
     * <p>A chunked corpus holds tens of thousands of passages; pulling their content along with
     * the vectors would drag tens of megabytes of text through memory on every retrieval, when
     * only the handful of winning passages is ever read. Content is fetched afterwards, by key.
     */
    public static List<ChatChunk> loadPersonalChatChunkVectors() throws SQLException, IOException {
        List<ChatChunk> out = new ArrayList<>();
        String sql =
                "SELECT file_path, chunk_index, vector FROM personal_chat_chunk WHERE vector IS NOT NULL AND vector != '';";
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new ChatChunk(
                        rs.getString("file_path"),
                        rs.getInt("chunk_index"),
                        MAPPER.readValue(rs.getString("vector"), double[].class)));
            }
        }
        return out;
    }

    /** How many passages are stored for a note. Zero means it predates chunking and needs indexing. */
    public static int countPersonalChatChunks(String filePath) throws SQLException {
        String sql = "SELECT COUNT(*) FROM personal_chat_chunk WHERE file_path = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static String loadPersonalChatChunkContent(String filePath, int chunkIndex) throws SQLException {
        String sql = "SELECT content FROM personal_chat_chunk WHERE file_path = ? AND chunk_index = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setInt(2, chunkIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("content") : null;
            }
        }
    }

    public static String loadPersonalChatFileName(String filePath) throws SQLException {
        String sql = "SELECT file_name FROM personal_chat_memory WHERE file_path = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("file_name") : null;
            }
        }
    }

    /** Plain row count for a table, or 0 if it does not exist. Used by `doctor`. */
    public static int countRows(String table) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table + ";")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Counts stored vectors grouped by the model that produced them. Null key = unknown. */
    public static java.util.Map<String, Integer> countVectorsByModel(String table) throws SQLException {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        String sql = "SELECT COALESCE(embedding_model, '(unknown)') AS m, COUNT(*) AS n FROM " + table
                + " WHERE vector IS NOT NULL AND vector != '' GROUP BY m ORDER BY n DESC;";
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString("m"), rs.getInt("n"));
            }
        }
        return out;
    }

    public static String loadPersonalChatContent(String filePath) throws SQLException {
        String sql = "SELECT content FROM personal_chat_memory WHERE file_path = ?;";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
            }
        }
        return null;
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // ==========================================
    // 11. Personal Memory Retrieval Operations
    // ==========================================

    public static List<com.osscli.model.PrMemory> loadAllPersonalPrMemories() throws SQLException, IOException {
        String sql = "SELECT * FROM personal_pr_memory;";
        List<com.osscli.model.PrMemory> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                double[] vector = MAPPER.readValue(rs.getString("vector"), double[].class);
                results.add(new com.osscli.model.PrMemory(
                        rs.getString("repository"),
                        rs.getLong("pr_number"),
                        rs.getString("files_changed"),
                        rs.getString("generated_story"),
                        vector));
            }
        }
        return results;
    }

    public static List<com.osscli.model.ChatMemory> loadAllPersonalChatMemories() throws SQLException, IOException {
        String sql = "SELECT file_name, content, vector FROM personal_chat_memory;";
        List<com.osscli.model.ChatMemory> results = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                double[] vector = MAPPER.readValue(rs.getString("vector"), double[].class);
                results.add(
                        new com.osscli.model.ChatMemory(rs.getString("file_name"), rs.getString("content"), vector));
            }
        }
        return results;
    }

    public static Map<String, String> loadAllConfigs() throws SQLException {
        String sql = "SELECT key, value FROM system_config;";
        Map<String, String> configs = new HashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                configs.put(rs.getString("key"), rs.getString("value"));
            }
        }
        return configs;
    }

    public static long savePromptHistory(
            long issueNumber,
            String repository,
            boolean ollamaAnswered,
            String escalationReason,
            String ollamaResponse,
            String promptText,
            int tokenEstimate,
            double confidenceScore,
            String providerSent)
            throws SQLException {
        String sql =
                "INSERT INTO prompt_history (issue_number, repository, ollama_answered, escalation_reason, ollama_response, prompt_text, token_estimate, confidence_score, provider_sent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, issueNumber);
            ps.setString(2, repository);
            ps.setInt(3, ollamaAnswered ? 1 : 0);
            ps.setString(4, escalationReason);
            ps.setString(5, ollamaResponse);
            ps.setString(6, promptText);
            ps.setInt(7, tokenEstimate);
            ps.setDouble(8, confidenceScore);
            ps.setString(9, providerSent);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    public static void savePromptContextChunks(long promptId, List<com.osscli.model.PromptContextChunk> chunks)
            throws SQLException {
        String sql =
                "INSERT INTO prompt_context_chunks (prompt_id, source_type, source_ref, content, relevance_score, token_count, included) VALUES (?, ?, ?, ?, ?, ?, ?);";
        try (Connection conn = DatabaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (com.osscli.model.PromptContextChunk chunk : chunks) {
                ps.setLong(1, promptId);
                ps.setString(2, chunk.sourceType());
                ps.setString(3, chunk.sourceRef());
                ps.setString(4, chunk.content());
                ps.setDouble(5, chunk.relevanceScore());
                ps.setInt(6, chunk.tokenCount());
                ps.setInt(7, chunk.included() ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
