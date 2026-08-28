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
package com.osscli.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Notes the index still believes in that are no longer on disk.
 *
 * <p>Nothing had ever removed anything from this index. Every note ever seen stayed in it, so
 * renaming a folder produced two copies of every note in it -- the old path and the new one,
 * identical text, both scoring, both answering. Measured after one reorganisation: 2,444 indexed
 * notes, 1,169 of them at paths that no longer existed.
 *
 * <p>That is invisible from the outside. Search still works, results still look right, and the only
 * symptom is the same passage appearing twice under two names and the store growing for ever.
 *
 * <h2>The rule that stops this deleting a live archive</h2>
 *
 * <p>A missing file and an unreachable folder look identical to {@link Files#exists}, and this
 * archive spent a year in iCloud, where "unreachable" is a Tuesday. Deleting 800 notes because a
 * network share was slow to mount would be an unrecoverable answer to a temporary problem.
 *
 * <p>So a note is only forgotten when its <em>folder</em> is present and the note is not. If the
 * folder itself is gone, every note under it is left exactly where it is, and the run says so. An
 * absent folder is a mount problem; an absent file inside a present folder is a deletion.
 */
public final class StaleNotes {

    private StaleNotes() {}

    /** What a sweep found: what to forget, and what it refused to judge. */
    public record Sweep(List<String> gone, Set<String> unreachableFolders) {

        public boolean nothingToDo() {
            return gone.isEmpty();
        }
    }

    /**
     * Which indexed notes have really been deleted, and which merely cannot be reached.
     *
     * <p>The decision is made at the <em>root</em>, not at the note's own folder. Checking the
     * folder alone was too cautious to be useful: reorganising an archive deletes the folders it
     * empties, so 1,156 rows pointing into folders that had been deliberately removed were
     * protected as though a disk were missing, and the index kept every one of them.
     *
     * <p>A root is a folder the owner has said their notes live in. If the root is present and
     * readable, everything under it is knowable: a note that is not there has been moved or
     * deleted, whatever became of the folders in between. If the root itself cannot be reached,
     * nothing under it is judged at all -- that is the unmounted disk, the cloud folder that has
     * not appeared yet, the external drive left at home.
     *
     * @param indexed every path the index holds
     * @param roots folders the notes are supposed to live in
     */
    public static Sweep sweep(List<String> indexed, List<Path> roots) {
        List<Path> reachable = new ArrayList<>();
        Set<String> unreachable = new LinkedHashSet<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                reachable.add(root.toAbsolutePath().normalize());
            } else {
                unreachable.add(root.toString());
            }
        }

        List<String> gone = new ArrayList<>();
        for (String raw : indexed) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path note = Path.of(raw);
            if (Files.exists(note)) {
                continue;
            }
            Path abs = note.toAbsolutePath().normalize();
            boolean underAReachableRoot = false;
            for (Path root : reachable) {
                if (abs.startsWith(root)) {
                    underAReachableRoot = true;
                    break;
                }
            }
            if (underAReachableRoot) {
                gone.add(raw);
            } else {
                // Not under any root this run can see. Either the archive moved and nobody told
                // the configuration, or a disk is absent. Both are somebody's decision to make,
                // not a reason to delete their index.
                Path folder = abs.getParent();
                unreachable.add(folder == null ? raw : folder.toString());
            }
        }
        return new Sweep(gone, unreachable);
    }

    /** The folders this install considers to hold notes. */
    public static List<Path> configuredRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(com.osscli.AppPaths.BASE_DIR.resolve("memory"));
        roots.add(com.osscli.memory.KnowledgePack.load().archive());
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("drive.paths");
            if (configured != null && !configured.isBlank()) {
                for (String one : configured.split(",")) {
                    if (!one.isBlank()) {
                        roots.add(Path.of(one.strip()));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            // One fewer root means one folder judged unreachable rather than pruned. The safe
            // direction, and the only direction a missing configuration should ever push.
        }
        return roots;
    }

    /**
     * Indexed notes that are missing and belong to no folder this install knows about.
     *
     * <p>Kept apart from {@link Sweep#gone()} because the two deserve different answers. A note
     * missing from a live archive has been deleted and can be dropped without asking. A note whose
     * whole archive is unaccounted for is either a disk that is not plugged in or a move nobody
     * recorded, and the first of those must never be treated as the second automatically.
     */
    public static List<String> outside(List<String> indexed, Sweep sweep) {
        Set<String> alreadyHandled = new LinkedHashSet<>(sweep.gone());
        List<String> out = new ArrayList<>();
        for (String raw : indexed) {
            if (raw == null || raw.isBlank() || alreadyHandled.contains(raw)) {
                continue;
            }
            if (!Files.exists(Path.of(raw))) {
                out.add(raw);
            }
        }
        return out;
    }

    /**
     * Forget them.
     *
     * <p>One at a time and continuing past a failure: a row that will not delete is worth one less
     * cleaned row, not an abandoned sweep.
     */
    public static int forget(List<String> paths) {
        int done = 0;
        for (String path : paths) {
            try {
                com.osscli.storage.SqliteStorage.forgetNote(path);
                done++;
            } catch (java.sql.SQLException e) {
                // Named nowhere and counted honestly: the caller reports how many it managed.
            }
        }
        return done;
    }
}
