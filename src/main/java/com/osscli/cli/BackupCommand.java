package com.osscli.cli;

import com.osscli.AppPaths;
import com.osscli.storage.SqliteStorage;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;

@Command(
        name = "backup",
        description =
                "Export your entire AI memory and database into a portable archive with auto-rotation (keeps last 5)")
public class BackupCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(BackupCommand.class);
    private static final int MAX_BACKUPS = 5;

    @Override
    public Integer call() throws Exception {
        Path dataDir = AppPaths.DATA_DIR;
        if (!Files.exists(dataDir)) {
            LOGGER.error("No 'data' directory found. There is nothing to backup.");
            return 1;
        }

        // 1. Resolve target backup directory from configuration
        String backupPathStr = SqliteStorage.loadConfig("backup.path");
        Path targetBackupDir;
        if (backupPathStr == null || backupPathStr.trim().isEmpty()) {
            targetBackupDir = AppPaths.BACKUPS_DIR; // Use global backups folder
        } else {
            targetBackupDir = Paths.get(backupPathStr);
        }

        if (!Files.exists(targetBackupDir)) {
            Files.createDirectories(targetBackupDir);
        }

        // 2. Perform the backup archiving
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupFile = targetBackupDir.resolve("sa_brain_backup_" + timestamp + ".zip");

        LOGGER.info("Starting automated backup of your local AI Memory into '{}'...", targetBackupDir.toAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(backupFile.toFile());
                ZipOutputStream zos = new ZipOutputStream(fos)) {

            Files.walkFileTree(dataDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".db") || fileName.endsWith(".txt")) {
                        zos.putNextEntry(new ZipEntry(dataDir.relativize(file).toString()));
                        try (FileInputStream fis = new FileInputStream(file.toFile())) {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            LOGGER.info("  ✔ Backup successfully created: {}", backupFile.getFileName());

            // 3. Enforce Log Rotation: Keep only the 5 most recent backups
            enforceBackupLimit(targetBackupDir);

        } catch (IOException e) {
            LOGGER.error("Failed to create backup archive: {}", e.getMessage());
            return 1;
        }

        return 0;
    }

    private void enforceBackupLimit(Path backupDir) {
        try (java.util.stream.Stream<Path> stream = Files.list(backupDir)) {
            List<Path> backups = stream.filter(p -> p.getFileName().toString().startsWith("sa_brain_backup_")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())) // Sort oldest first
                    .collect(Collectors.toList());

            int removedCount = 0;
            // While we have more than MAX_BACKUPS, delete the oldest (index 0)
            while (backups.size() > MAX_BACKUPS) {
                Path oldestBackup = backups.remove(0);
                Files.deleteIfExists(oldestBackup);
                LOGGER.info("  ↳ Auto-rotation removed oldest backup: {}", oldestBackup.getFileName());
                removedCount++;
            }

            if (removedCount > 0) {
                LOGGER.info("  ✔ Backup rotation complete. Maintained the most recent {} archives.", MAX_BACKUPS);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to enforce backup rotation limit: {}", e.getMessage());
        }
    }
}
