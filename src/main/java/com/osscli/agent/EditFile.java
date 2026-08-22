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
package com.osscli.agent;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The first thing this tool has ever had that can damage somebody's work.
 *
 * <p>Everything else oss does is additive: it syncs, indexes, files notes, and prints. This writes
 * to a file the user did not name, chosen by a model. So it is built the other way round from the
 * rest — the question is not "how do we make this capable" but "what has to be true before a byte
 * moves".
 *
 * <p><b>Three gates, and each catches a different failure.</b> The tool must be enabled for the run
 * at all, which is a decision made once, in advance, by a person typing a flag. Every individual
 * edit is shown as a diff and confirmed. And a run with no terminal cannot confirm anything, so it
 * cannot write — a pipe treated as consent is how twelve files get rewritten in a job nobody was
 * watching.
 *
 * <p><b>The match must be unique.</b> A {@code find} that appears twice is refused rather than
 * applied to the first one: a model that has read part of a file and is working from memory will
 * hand over a fragment that occurs more than once, and "the first occurrence" is a rule nobody
 * would choose if they were asked. Zero matches is refused for the same reason — the model is
 * editing a file it has misremembered, and the honest answer is to say so and let it read again.
 *
 * <p><b>The diff is generated from the bytes, never from the model.</b> A model describing its own
 * edit is the one account of that edit which cannot be checked against it.
 */
public final class EditFile implements Tool {

    private final Confirm confirm;
    private final PrintStream out;

    public EditFile(Confirm confirm, PrintStream out) {
        this.confirm = confirm;
        this.out = out;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String usage() {
        return "edit — path: <file>   find: <exact text>   replace: <new text>   (shown as a diff, then confirmed)";
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String run(Action action, Workspace workspace) {
        String requested = action.argument("path");
        String find = action.argument("find");
        String replace = action.argument("replace");
        if (requested.isEmpty() || find.isEmpty()) {
            return "error: edit needs path: and find: (replace: may be empty to delete the text)";
        }
        Path resolved = workspace.resolve(requested).orElse(null);
        if (resolved == null) {
            return "error: " + requested + " is outside this project, which is " + workspace.root();
        }
        if (!Files.isRegularFile(resolved)) {
            return "error: " + workspace.display(resolved) + " is not a file";
        }

        try {
            String original = Files.readString(resolved);
            int occurrences = count(original, find);
            if (occurrences == 0) {
                return "error: that exact text is not in " + workspace.display(resolved)
                        + ". Read it again — it may have changed, or the fragment may be from memory.";
            }
            if (occurrences > 1) {
                return "error: that text appears " + occurrences + " times in " + workspace.display(resolved)
                        + ". Give a longer fragment that appears once; editing the first one is not a rule you chose.";
            }

            String updated = original.replace(find, replace);
            String diff = Diff.of(workspace.display(resolved), lines(original), lines(updated));
            out.println();
            out.print(diff);
            out.println();
            if (!confirm.ask("  apply this change to " + workspace.display(resolved) + "?")) {
                // An observation, not a stop: the model can propose something else, and the user
                // gets a loop that carries on rather than one that ends on a "no".
                return "declined: the change was shown and not applied. Nothing was written.";
            }

            write(resolved, updated);
            return "applied to " + workspace.display(resolved);
        } catch (Exception e) {
            return "error: could not edit " + workspace.display(resolved) + " — " + e.getMessage();
        }
    }

    /**
     * Write via a temporary file in the same directory, then move.
     *
     * <p>So an interruption leaves either the old file or the new one, never half of either.
     * Truncating and writing in place is the version that loses somebody's work when the machine
     * sleeps, and the same directory matters because a move across filesystems is a copy.
     */
    private static void write(Path target, String content) throws java.io.IOException {
        Path temp =
                Files.createTempFile(target.getParent(), target.getFileName().toString(), ".oss-edit");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some filesystems cannot promise it. Still better than truncating in place.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }
}
