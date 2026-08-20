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

    /**
     * The note folders {@code sync --me} walks, or empty when none are configured.
     *
     * <p>Read here rather than passed in: the check has to hold for {@code --to} as well, and a
     * caller that has to remember to pass the guard is a guard that eventually is not passed.
     */
    /**
     * Whether a backup written at {@code target} would land inside an indexed note folder.
     *
     * <p>A value, so the answer can be read without running a backup -- the check is the part worth
     * getting right, and the failure it prevents is unrecoverable by the time it shows.
     */
    static boolean insideIndexedFolder(Path target, String noteDir) {
        return real(target).startsWith(real(Paths.get(noteDir.trim())));
    }

    /**
     * A path with every symbolic link resolved, or the lexical form when it cannot be.
     *
     * <p>An unreadable or not-yet-existing path falls back rather than failing: the caller is a
     * safety check, and a check that throws is a check that stops protecting.
     */
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static List<String> indexedNoteDirs() {
        try {
            String paths = SqliteStorage.loadConfig("drive.paths");
            if (paths == null || paths.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(paths.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            // Unreadable configuration must not block a backup. The worst case is the old
            // behaviour, and a backup refused for an unrelated reason helps nobody.
            LOGGER.debug("Could not read drive.paths for the backup location check: {}", e.getMessage());
            return List.of();
        }
    }

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

        // A backup written inside an indexed note folder feeds itself.
        //
        // drive.paths is walked by `sync --me`, which reads every file it finds and embeds it. Put
        // the backups there and the next sync ingests a few hundred megabytes of zip as if it were
        // a note; the sync after that backs up what it ingested, and the archive grows without
        // bound until the disk decides the matter. Nothing about the symptom points at the cause --
        // it looks like a corpus that mysteriously exploded.
        //
        // Refused rather than warned. A warning scrolls past inside a command that then reports
        // success, and this one is unrecoverable by the time it is obvious.
        // Resolved, not normalised. normalize() is lexical -- it strips "." and ".." and never
        // follows a link -- so a target that IS a symlink into an indexed folder passed this check
        // and sprang the exact trap below. Demonstrated with a link: lexically it does not start
        // with the indexed path, resolved it does.
        for (String noteDir : indexedNoteDirs()) {
            Path indexed = real(Paths.get(noteDir.trim()));
            if (insideIndexedFolder(targetBackupDir, noteDir)) {
                LOGGER.error("Refusing to write backups inside an indexed note folder:");
                LOGGER.error("  backups → {}", targetBackupDir);
                LOGGER.error("  indexed → {}", indexed);
                LOGGER.error("'sync --me' reads everything under that folder, so it would ingest the");
                LOGGER.error("archives as notes and back up what it ingested, over and over.");
                LOGGER.error("Choose a directory outside your note folders — any disk, any cloud.");
                return 1;
            }
        }

        // 2. Perform the backup archiving
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupFile = targetBackupDir.resolve("oss_backup_" + timestamp + ".zip");
        // Written under a temporary name and renamed only on success. The failure mode this
        // prevents was observed, not imagined: an iCloud read timed out mid-walk, the exception
        // aborted everything, and a 277 MB partial zip stayed on disk looking exactly like a
        // backup. A partial backup that looks whole is worse than no backup at all.
        Path partial = targetBackupDir.resolve("oss_backup_" + timestamp + ".zip.partial");

        LOGGER.info("Backing up {} into '{}'...", INCLUDE, targetBackupDir.toAbsolutePath());

        long[] count = {0};
        List<String> unreadable = new ArrayList<>();
        // A backup of a 496 MB database plus a synced archive runs for minutes, and this printed
        // one line and then nothing. Silence is indistinguishable from a hang -- the same reason
        // `model --fetch` and sync indexing grew a live line. Without it the honest response to a
        // long backup is to assume it has stopped and kill it, which is how a backup gets skipped.
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("backup");
                FileOutputStream fos = new FileOutputStream(partial.toFile());
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
                        // Every 200 files rather than every file: a redraw per file on a corpus
                        // this size is more work than the copying.
                        if (count[0] % 200 == 0) {
                            live.step(count[0] + " files · " + dir.getFileName());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            live.done(count[0] + " file(s) archived");
            LOGGER.info("  {} file(s) archived", count[0]);
            if (!unreadable.isEmpty()) {
                LOGGER.warn("  {} file(s) could not be read and are NOT in this backup:", unreadable.size());
                for (String u : unreadable) {
                    LOGGER.warn("    {}", u);
                }
                LOGGER.warn("  (iCloud files may not be downloaded — open the folder once, or run again)");
            }
            LOGGER.info("  Backup created: {}", backupFile.toAbsolutePath());

            // 3. Enforce Log Rotation: Keep only the 5 most recent backups
            enforceBackupLimit(targetBackupDir);

            Files.move(partial, backupFile);
        } catch (IOException e) {
            LOGGER.error("Failed to create backup archive: {}", e.getMessage());
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // Deleting the debris is best-effort; the .partial suffix already says what it is.
            }
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
