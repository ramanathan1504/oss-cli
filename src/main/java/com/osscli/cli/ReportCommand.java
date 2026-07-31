package com.osscli.cli;

import com.osscli.AppPaths;
import com.osscli.report.MarkdownReportWriter;
import com.osscli.storage.SqliteStorage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "report", description = "Generate a consolidated weekly health report")
public class ReportCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ReportCommand.class);
    private static final DateTimeFormatter FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Option(
            names = {"-r", "--repo"},
            description = "The target GitHub repository to analyze (owner/name)")
    private String repository;

    @Option(
            names = {"--me"},
            description = "Generate a highly personalized weekly health report tailored specifically to your skillset")
    private boolean me;

    @Override
    public Integer call() throws Exception {

        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.trim().isEmpty()) {
                LOGGER.error(
                        "No target repository specified. Please use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }
        String timestamp = FILE_NAME_FORMATTER.format(LocalDateTime.now());
        Path reportPath;
        MarkdownReportWriter writer = new MarkdownReportWriter();

        if (me) {
            // Load personalized configurations from SQLite
            String username = SqliteStorage.loadConfig("github.username");
            if (username == null || username.trim().isEmpty()) {
                LOGGER.error("No GitHub username configured. Please run 'setup' first.");
                return 1;
            }

            // Dynamic personal file output (e.g., reports/<username>-health-report-<timestamp>.md)
            reportPath = AppPaths.REPORTS_DIR.resolve(username + "-report-" + timestamp + ".md");
            writer.writePersonalReport(reportPath, repository);
        } else {
            // Standard Repository Report
            String[] parts = repository.split("/");
            String repoName = parts.length == 2 ? parts[1] : "issues";
            reportPath = AppPaths.REPORTS_DIR.resolve(repoName + "-issues-report-" + timestamp + ".md");
            writer.write(reportPath, repository);
        }

        LOGGER.info("Weekly Health Report successfully generated offline at:");
        LOGGER.info("  {}", reportPath.toAbsolutePath());
        LOGGER.info("");

        return 0;
    }
}
