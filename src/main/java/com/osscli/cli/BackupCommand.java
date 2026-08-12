/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "backup",
        mixinStandardHelpOptions = true,
        description =
                "Export your entire AI memory and database into a portable archive with auto-rotation (keeps last 5)")
public class BackupCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(BackupCommand.class);
    private static final int MAX_BACKUPS = 5;

    /**
     * What a backup must contain, and what it must not.
     *
     * <p>Everything here either cannot be re-derived or costs real work to rebuild. Left out: the
     * model, which re-downloads; logs, which are noise; and old backups, because a backup
     * containing backups grows until it stops being written at all.
     *
     * <p>Vectors ARE included even though they could be recomputed. They are a few kilobytes each
     * and keyed by content hash, so restoring them means the first search after a restore is
     * instant instead of re-embedding a whole corpus — and a restore that leaves you waiting is one
     * people interrupt.
     *
     * <p>The one that matters most is {@code reviews}. A synced issue can be synced again; a review
     * write-up and the head SHA it was written at exist nowhere else in the world.
     */
    private static final List<String> INCLUDE = List.of("data", "reviews", "memory", "vectors");

    @Option(
            names = "--to",
            description =
                    "Write it here instead — point at a synced folder (iCloud, Dropbox, Drive) for off-machine copies")
    Path to;

    @Option(
            names = "--include",
            description = "Also back up this directory. Repeatable. Use it for an archive an extension owns")
    List<Path> include = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        Path base = AppPaths.BASE_DIR;

        List<Path> sources = new ArrayList<>();
        for (String name : INCLUDE) {
            Path d = base.resolve(name);
            if (Files.isDirectory(d)) {
                sources.add(d);
            }
        }
        // An attached archive is somebody else's directory, so it is included when it can be
        // found and never guessed at. KB_ARCHIVE is what the archive extension itself reads, so
        // honouring it means the two agree without this having to know anything about DEVONthink.
        // Any attached extension that declares where its data lives gets backed up with
        // everything else. Declared, never guessed: an archive lives wherever its owner put it,
        // and guessing is how a backup quietly misses the thing it existed to protect.
        for (com.osscli.ext.Extension ext : com.osscli.ext.ExtensionRegistry.all()) {
            Path a = ext.archivePath();
            if (a != null && Files.isDirectory(a)) {
                LOGGER.info("  including {}'s archive: {}", ext.getName(), a);
                sources.add(a);
            } else if (a != null) {
                LOGGER.warn("  {} declares an archive at {} — not found, skipped", ext.getName(), a);
            }
        }
        String kb = System.getenv("KB_ARCHIVE");
        if (kb != null && !kb.isBlank() && Files.isDirectory(Path.of(kb))) {
            sources.add(Path.of(kb));
        }
        for (Path extra : include) {
            if (Files.isDirectory(extra)) {
                sources.add(extra);
            } else {
                LOGGER.warn("  skipped --include {} (not a directory)", extra);
            }
        }

        if (sources.isEmpty()) {
            LOGGER.error("Nothing to back up yet under {}.", base);
            return 1;
        }

        // 1. Resolve target backup directory: the flag, then configuration, then the default.
        Path targetBackupDir;
        if (to != null) {
            targetBackupDir = to;
        } else {
            String backupPathStr = SqliteStorage.loadConfig("backup.path");
            targetBackupDir = (backupPathStr == null || backupPathStr.trim().isEmpty())
                    ? AppPaths.BACKUPS_DIR
                    : Paths.get(backupPathStr);
        }
        if (!Files.exists(targetBackupDir)) {
            Files.createDirectories(targetBackupDir);
        }

        // 2. Perform the backup archiving
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupFile = targetBackupDir.resolve("oss_backup_" + timestamp + ".zip");

        LOGGER.info("Backing up {} into '{}'...", INCLUDE, targetBackupDir.toAbsolutePath());

        long[] count = {0};
        try (FileOutputStream fos = new FileOutputStream(backupFile.toFile());
                ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (Path dir : sources) {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // Paths are relative to BASE_DIR, not to each source, so a restore knows
                        // which directory every entry came out of.
                        // Inside BASE_DIR, keep the path relative to it so a restore knows where
                        // each file belongs. Outside it, prefix with external/<name>/ rather than
                        // an absolute path -- an absolute path in a zip restores onto whatever that
                        // path happens to be on the machine unpacking it.
                        String entry = file.startsWith(base)
                                ? base.relativize(file).toString()
                                : "external/" + dir.getFileName() + "/" + dir.relativize(file);
                        zos.putNextEntry(new ZipEntry(entry));
                        try (FileInputStream fis = new FileInputStream(file.toFile())) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                        zos.closeEntry();
                        count[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            LOGGER.info("  {} file(s) archived", count[0]);
            LOGGER.info("  Backup created: {}", backupFile.toAbsolutePath());

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
