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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "restore",
        hidden = true,
        mixinStandardHelpOptions = true,
        description = "Import and restore your AI memory and database from a backup archive")
public class RestoreCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(RestoreCommand.class);

    @Parameters(index = "0", description = "The path to the backup .zip file")
    private String backupFilePath;

    @Option(names = "--force", description = "Replace an existing store without asking")
    boolean force;

    /**
     * Whether there is nothing here worth asking about.
     *
     * <p>A fresh machine is the case restore exists for, and stopping to ask there would put a
     * prompt in front of the one path that has nothing to lose.
     */
    static boolean mayReplaceWithoutAsking(long existingBytes) {
        return existingBytes == 0;
    }

    /**
     * Whether an existing store may be replaced.
     *
     * <p>True without asking when there is no store yet. Otherwise the size of what is about to be
     * overwritten is stated and the answer has to be typed, at a terminal -- and no terminal means
     * refuse, because cron, CI and a launchd agent all look like that and none of them can decide
     * this.
     */
    private boolean confirmReplacement() {
        Path db = AppPaths.BASE_DIR.resolve("data").resolve("issue_intelligence.db");
        long bytes = 0;
        try {
            bytes = Files.exists(db) ? Files.size(db) : 0;
        } catch (Exception e) {
            bytes = 0;
        }
        if (mayReplaceWithoutAsking(bytes)) {
            return true;
        }

        LOGGER.warn("This will overwrite the store that is already here:");
        LOGGER.warn("  database  {}  ({} MB)", db, bytes / (1024 * 1024));
        LOGGER.warn("  a restore writes over these files; there is no undo and no copy is kept.");

        java.io.Console console = System.console();
        if (console == null) {
            LOGGER.error("Refusing: no terminal to confirm at. Pass --force if you meant it.");
            return false;
        }
        String answer = console.readLine("Replace it? type the word yes: ");
        boolean ok = answer != null && answer.strip().equalsIgnoreCase("yes");
        if (!ok) {
            LOGGER.info("Left alone.");
        }
        return ok;
    }

    @Override
    public Integer call() throws Exception {
        Path zipPath = Paths.get(backupFilePath);
        if (!Files.exists(zipPath) || !backupFilePath.endsWith(".zip")) {
            LOGGER.error(
                    "Invalid backup file: {}. Please provide a valid .zip backup archive.", zipPath.toAbsolutePath());
            return 1;
        }

        // Restoring over a store that already has something in it is the one irreversible thing
        // this command does: the archive is written straight over the files, so an older backup
        // unpacked onto a newer corpus takes the newer one with it and leaves nothing to go back
        // to. On a fresh machine there is nothing to lose and nothing is asked -- which is the
        // case restore is usually run for.
        //
        // The rest of this repository already holds this line: the upstream guard stops to ask
        // before posting a single comment, and backup refuses outright rather than warning. Only
        // the command that can replace the whole corpus asked nothing at all.
        if (!force && !confirmReplacement()) {
            return 2;
        }

        // Restore relative to BASE_DIR, not to data/. A backup now carries reviews/ and memory/
        // as well, and unpacking those into data/ would put every review write-up somewhere
        // nothing looks for it -- a restore that appears to succeed and silently loses the one
        // thing that cannot be re-derived.
        Path base = AppPaths.BASE_DIR;
        if (!Files.exists(base)) {
            Files.createDirectories(base);
        }

        LOGGER.info("Restoring into '{}'...", base.toAbsolutePath());

        // 1. Buffer local configurations BEFORE overwriting the database
        Map<String, String> localConfigs = new HashMap<>();
        try {
            localConfigs = SqliteStorage.loadAllConfigs();
            if (!localConfigs.isEmpty()) {
                LOGGER.info("  ↳ Buffered {} local configurations to prevent overwrite.", localConfigs.size());
            }
        } catch (Exception ignored) {
            // DB might not exist yet if it's a fresh install on a new Mac
        }

        int skippedExternal = 0;

        // 2. Perform the Unzip Restoration
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String name = zipEntry.getName();
                if (name.startsWith("external/")) {
                    // Directories another tool owns. Restored beside the rest rather than back to
                    // wherever they came from: that path may not exist on this machine, may belong
                    // to a different account, and writing to it unasked is not this command's call.
                    skippedExternal++;
                    zipEntry = zis.getNextEntry();
                    continue;
                }

                File newFile = new File(base.toFile(), name);

                // Prevent Zip Slip vulnerability
                if (!newFile.getCanonicalPath().startsWith(base.toFile().getCanonicalPath())) {
                    throw new IOException("Security Error: Bad zip entry targeting outside the data directory.");
                }
                if (newFile.getParentFile() != null) {
                    newFile.getParentFile().mkdirs();
                }

                LOGGER.info("  restoring {}", name);
                try (FileOutputStream fos = new FileOutputStream(newFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            if (skippedExternal > 0) {
                LOGGER.warn(
                        "  {} file(s) from an external archive were NOT restored. Unzip them yourself:",
                        skippedExternal);
                LOGGER.warn("    unzip -j '{}' 'external/*' -d <where that archive lives>", zipPath);
            }

            // 3. Re-apply the buffered configurations into the restored database
            if (!localConfigs.isEmpty()) {
                LOGGER.info("  ↳ Re-applying your local system configurations...");
                for (Map.Entry<String, String> entry : localConfigs.entrySet()) {
                    SqliteStorage.saveConfig(entry.getKey(), entry.getValue());
                }
            }

            LOGGER.info("==================================================");
            LOGGER.info("✔ Restoration completed successfully!");
            LOGGER.info("  1. Your database, vectors, and memory are fully restored.");
            LOGGER.info("  2. Your local models and note folders were preserved.");
            LOGGER.info("==================================================");

        } catch (Exception e) {
            LOGGER.error("Failed to restore backup archive: {}", e.getMessage());
            return 1;
        }

        return 0;
    }
}
