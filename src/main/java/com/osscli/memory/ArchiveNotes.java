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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reading an archive that may not be on this machine.
 *
 * <p>{@code oss memory map} hung for over two minutes and printed nothing. It was not the matching
 * — 106 terms across 56 MB is seconds of work. It was the reading: all 816 notes live in iCloud
 * Drive and had been evicted, so every {@code readString} was a download. {@code brctl status}
 * confirmed it mid-run: an active downloader sitting at 0.0%.
 *
 * <p>{@code import} learned this already and says so — <em>"638 files could not be read at all,
 * this folder streams from the cloud"</em> — but the lesson lived in that one command. So it lives
 * here now, where everything that walks the archive can share it.
 *
 * <p><b>A deadline, not a detector.</b> {@code stat} can tell an evicted file apart — it reports the
 * full size against zero allocated blocks, measured at {@code size=83629 blocks=0} on a note that
 * had never been downloaded — but Java's file attributes do not carry the block count on any
 * platform, and shelling out to {@code stat} for every note trades one portability problem for
 * another. So each read gets two seconds, and five abandoned in a row ends the walk: eviction is
 * per folder in practice, so the fifth timeout says exactly what the eight-hundredth would, ten
 * minutes sooner. Worst case is ten seconds and an honest sentence, against two minutes of silence.
 */
public final class ArchiveNotes {

    /** How long any single read may take before it is abandoned. */
    private static final long READ_DEADLINE_SECONDS = 2;

    /**
     * How long the whole walk may take.
     *
     * <p>The per-file deadline alone did not help, which was worth measuring rather than assuming:
     * an evicted note downloads in about a second, comfortably inside two, so nothing ever timed
     * out and 816 of them still took over two minutes. The limit that matters is on the walk, not
     * on the file — and a measurement of part of an archive is a fair answer as long as it says so.
     */
    private static final long WALK_BUDGET_SECONDS = 15;

    private ArchiveNotes() {}

    /** What a walk of the archive found, including what it could not read and why. */
    public record Walk(List<Note> notes, int found, int unreadable, boolean ranOutOfTime) {

        /** True when this measured part of the archive rather than all of it. */
        public boolean partial() {
            return notes.size() < found;
        }

        /** The sentence to print, or empty when the whole archive was read. */
        public String warning() {
            if (!partial()) {
                return "";
            }
            String head = "  measured " + notes.size() + " of " + found + " notes";
            if (ranOutOfTime) {
                return head + " — the rest are still in the cloud.\n"
                        + "  This archive streams, so reading it is a download per note, and the walk\n"
                        + "  stopped after " + WALK_BUDGET_SECONDS + "s rather than sit there. What is above\n"
                        + "  is real and incomplete, in that order. To measure all of it: open the folder\n"
                        + "  and use 'Download Now', or keep a copy somewhere ordinary and point kb.json there.";
            }
            return head + " — " + unreadable + " could not be read at all.";
        }
    }

    /** One note that could actually be read. */
    public record Note(Path path, String lowercaseText) {}

    /**
     * Every readable {@code .md} under {@code archive}, lowercased once.
     *
     * <p>Lowercased here rather than by each caller because both callers do it, on the same bytes,
     * and 56 MB is enough for that to be worth not doing twice.
     */
    public static Walk walk(Path archive) throws IOException {
        List<Note> notes = new ArrayList<>();
        int unreadable = 0;
        boolean ranOutOfTime = false;

        if (!Files.isDirectory(archive)) {
            return new Walk(notes, 0, 0, false);
        }

        List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.walk(archive)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();
        }

        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "oss-archive-read");
            // Daemon: a read abandoned on a deadline is still blocked in the kernel, and a
            // non-daemon thread stuck there would keep the whole CLI alive after it had answered.
            t.setDaemon(true);
            return t;
        });
        long deadline = System.nanoTime() + WALK_BUDGET_SECONDS * 1_000_000_000L;
        try {
            for (Path note : files) {
                if (System.nanoTime() > deadline) {
                    ranOutOfTime = true;
                    break;
                }
                String text = readWithDeadline(reader, note);
                if (text == null) {
                    unreadable++;
                    continue;
                }
                notes.add(new Note(note, text.toLowerCase(Locale.ROOT)));
            }
        } finally {
            reader.shutdownNow();
        }
        return new Walk(notes, files.size(), unreadable, ranOutOfTime);
    }

    /** The file's text, or null when it did not arrive in time. */
    private static String readWithDeadline(ExecutorService on, Path file) {
        Future<String> pending = on.submit(() -> Files.readString(file, StandardCharsets.UTF_8));
        try {
            return pending.get(READ_DEADLINE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            // Not text, or gone since the walk listed it. Counted, not announced one file at a time.
            return null;
        }
    }
}
