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
package com.osscli.memory;

import com.osscli.AppPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which transcripts have already been filed, so an hourly job is cheap.
 *
 * <p>The numbers are the argument. 239 transcripts, 253 MB, the largest single file 51 MB, and this
 * is asked to run every hour on a laptop that is compiling and serving at the same time. Reading all
 * of it twenty-four times a day to discover that two files changed is not a background job, it is a
 * fan. Almost every hour, nothing has changed at all.
 *
 * <p>So each file is remembered by size and modification time. A transcript still being written to
 * has one or both change; a transcript from March has neither, and is never opened again.
 *
 * <p><b>Deliberately not a content hash.</b> A hash is the correct answer to "did this change" and
 * requires reading the file, which is the exact cost being avoided. Size and mtime can in principle
 * both be preserved across an edit; for append-only logs written by another program they never are,
 * and {@code oss memory sessions --all} exists for the day that assumption is wrong.
 */
public final class SessionLedger {

    /** Beside the other state, in the place logs and caches already live. */
    public static final Path FILE = AppPaths.BASE_DIR.resolve("sessions.ledger");

    private final Map<String, String> seen;

    private SessionLedger(Map<String, String> seen) {
        this.seen = seen;
    }

    /** What has been filed before, or an empty ledger on the first run or an unreadable one. */
    public static SessionLedger load() {
        Map<String, String> seen = new LinkedHashMap<>();
        if (Files.isRegularFile(FILE)) {
            try {
                for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        seen.put(line.substring(0, tab), line.substring(tab + 1));
                    }
                }
            } catch (IOException e) {
                // An unreadable ledger costs one expensive run, not the feature. Refusing to file
                // anything because the bookkeeping is damaged would be the bookkeeping deciding.
                seen.clear();
            }
        }
        return new SessionLedger(seen);
    }

    /** The size-and-time stamp of a file as it is right now, or empty when it cannot be read. */
    public static String stampOf(Path file) {
        try {
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return "";
        }
    }

    /** True when this transcript has grown, appeared, or been rewritten since the last run. */
    public boolean changed(Path file) {
        String now = stampOf(file);
        return now.isEmpty() || !now.equals(seen.get(file.toString()));
    }

    /** Record that this transcript was filed as it stands. */
    public void mark(Path file) {
        String now = stampOf(file);
        if (!now.isEmpty()) {
            seen.put(file.toString(), now);
        }
    }

    /** How many transcripts the ledger already knows about. */
    public int size() {
        return seen.size();
    }

    /**
     * Write the ledger back.
     *
     * <p>Written whole to a temporary file and moved into place, because the alternative -- append
     * as you go -- leaves a half-line behind when a run is interrupted, and a half-line is a stamp
     * that matches nothing, which silently refiles a transcript for ever.
     */
    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        StringBuilder sb = new StringBuilder();
        seen.forEach((path, stamp) -> sb.append(path).append('\t').append(stamp).append('\n'));
        Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Forget everything, so the next run reads every transcript again. */
    public static void forget() throws IOException {
        Files.deleteIfExists(FILE);
    }
}
