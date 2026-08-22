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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Read a file the model named, from inside the workspace and nowhere else.
 *
 * <p>Budgeted, and the budget is stated in the output. A 4,000-line file pasted whole is how the
 * context that was supposed to hold the question ends up holding one file — the same failure
 * {@code MemoryContext} exists to prevent, where an uncapped block produced a 19 MB prompt for a
 * 6,000-token model. Here the cap is lines, and what was left out is said in a sentence the model
 * can act on by asking for a different range.
 */
public final class ReadFile implements Tool {

    /** Enough to read a class, few enough that four of them still leave room for the question. */
    static final int MAX_LINES = 400;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String usage() {
        return "read_file — path: <file>   [from: <line>]   read part of a file in this project";
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public String run(Action action, Workspace workspace) {
        String requested = action.argument("path");
        if (requested.isEmpty()) {
            return "error: read_file needs a path";
        }
        Path resolved = workspace.resolve(requested).orElse(null);
        if (resolved == null) {
            // Named rather than described: the model chose this path and needs to know that the
            // rule is the workspace, not that "something went wrong".
            return "error: " + requested + " is outside this project, which is " + workspace.root();
        }
        if (!Files.isRegularFile(resolved)) {
            return "error: " + workspace.display(resolved) + " is not a file";
        }
        try {
            List<String> lines = Files.readAllLines(resolved);
            int from = Math.max(1, parse(action.argument("from")));
            int start = Math.min(from - 1, Math.max(0, lines.size() - 1));
            int end = Math.min(lines.size(), start + MAX_LINES);

            StringBuilder b = new StringBuilder();
            b.append(workspace.display(resolved))
                    .append("  lines ")
                    .append(start + 1)
                    .append('-')
                    .append(end)
                    .append(" of ")
                    .append(lines.size())
                    .append('\n');
            for (int i = start; i < end; i++) {
                b.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            }
            if (end < lines.size()) {
                // The remedy, not just the fact. A model told only "truncated" tends to apologise;
                // one told how to ask for the rest asks for the rest.
                b.append("… ")
                        .append(lines.size() - end)
                        .append(" more lines. Ask again with  from: ")
                        .append(end + 1)
                        .append('\n');
            }
            return b.toString();
        } catch (Exception e) {
            // Binary, unreadable, or gone between the check and the read. An answer, not a crash.
            return "error: could not read " + workspace.display(resolved) + " — " + e.getMessage();
        }
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (Exception e) {
            return 1;
        }
    }
}
