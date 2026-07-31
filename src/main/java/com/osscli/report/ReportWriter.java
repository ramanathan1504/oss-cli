package com.osscli.report;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public interface ReportWriter {
    void write(Path outputPath, String repository) throws IOException, SQLException;
}
