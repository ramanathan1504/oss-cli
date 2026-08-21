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
package com.osscli.release;

import com.osscli.RootCommand;
import com.osscli.storage.DatabaseManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import picocli.CommandLine;

/**
 * Everything a user of a release can depend on, in one comparable object.
 *
 * <p>The version number of this project used to be a judgement call made once per release, by
 * whoever was running the script, from memory of what had gone in. That is the sort of decision that
 * is right until the release where it matters.
 *
 * <p>Two things make up the surface, and they are not both Java:
 *
 * <ul>
 *   <li><b>The commands and their flags</b>, read out of picocli's own model rather than listed by
 *       hand — a hand-written list is a second copy of the truth, and this repository has paid for
 *       second copies before.
 *   <li><b>The schema version</b>, because the compatibility that actually breaks for a user is not
 *       an API signature. Nothing imports {@code com.osscli.*}; extensions attach by path. What
 *       breaks is an older binary meeting a store a newer one has migrated.
 * </ul>
 *
 * <p>Test scope on purpose: this is a release tool and has no business in the shipped jar.
 */
public final class Surface {

    private final int schemaVersion;

    /** Command name to its sorted flag names, including every alias, so dropping {@code -c} counts. */
    private final Map<String, TreeSet<String>> commands;

    private Surface(int schemaVersion, Map<String, TreeSet<String>> commands) {
        this.schemaVersion = schemaVersion;
        this.commands = commands;
    }

    /** Reads the surface out of the live command tree and the live schema version. */
    public static Surface current() {
        Map<String, TreeSet<String>> commands = new TreeMap<>();
        collect(new CommandLine(new RootCommand()), "", commands);

        // The built-in memory's verbs are a promise too, and picocli cannot see them: they arrive
        // as passthrough parameters, so `oss memory digest` is invisible to the walk above while
        // being exactly as scriptable as any flag. Removing one would have broken somebody's daily
        // job with the guard reporting no change at all. Recorded as a pseudo-command so the
        // existing rule -- an entry that disappears is a major -- covers them with no new machinery.
        // Named within the character set fromJson reads. Angle brackets round-tripped to nothing:
        // the entry was written, then silently dropped on the way back in, so the guard compared a
        // surface that had the verbs against one that never did.
        commands.put("memory builtin-verbs", new TreeSet<>(com.osscli.memory.BuiltinMemory.VERBS));
        commands.put("run builtin-verbs", new TreeSet<>(com.osscli.runner.BuiltinRunner.VERBS));
        return new Surface(DatabaseManager.currentSchemaVersion(), commands);
    }

    /**
     * Walks subcommands depth-first so nested verbs are recorded under their full path.
     *
     * <p>{@code oss ext list} is a different promise from {@code oss ext}, and flattening them would
     * let a nested command disappear without the guard noticing.
     */
    private static void collect(CommandLine cli, String prefix, Map<String, TreeSet<String>> into) {
        for (Map.Entry<String, CommandLine> entry : cli.getSubcommands().entrySet()) {
            CommandLine sub = entry.getValue();
            String path = prefix.isEmpty() ? entry.getKey() : prefix + " " + entry.getKey();

            TreeSet<String> flags = new TreeSet<>();
            for (CommandLine.Model.OptionSpec option : sub.getCommandSpec().options()) {
                // Every alias, not just the longest: removing `-c` while keeping `--continue`
                // breaks a script exactly as thoroughly as removing both.
                flags.addAll(List.of(option.names()));
            }
            into.put(path, flags);
            collect(sub, path, into);
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Map<String, TreeSet<String>> commands() {
        return commands;
    }

    // ==========================================
    // Serialisation
    // ==========================================

    /**
     * Writes the surface as sorted, indented JSON.
     *
     * <p>Hand-rolled rather than reached for through Jackson, because the output has to be stable
     * enough to diff in a pull request. Sorted keys and one flag per line mean a review shows
     * "removed --resume", not a reflowed blob.
     */
    public String toJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n");
        b.append("  \"commands\": {\n");

        List<String> names = new ArrayList<>(commands.keySet());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            b.append("    \"").append(name).append("\": [");
            List<String> flags = new ArrayList<>(commands.get(name));
            for (int f = 0; f < flags.size(); f++) {
                b.append('"').append(flags.get(f)).append('"');
                if (f < flags.size() - 1) {
                    b.append(", ");
                }
            }
            b.append(']');
            if (i < names.size() - 1) {
                b.append(',');
            }
            b.append('\n');
        }

        b.append("  }\n");
        b.append("}\n");
        return b.toString();
    }

    /**
     * Parses what {@link #toJson()} wrote.
     *
     * <p>Deliberately narrow: it reads this one shape and nothing else. A general JSON parser here
     * would be a dependency added to a test tool for no gain, and this file is produced by the
     * method above rather than by anybody's hand.
     */
    public static Surface fromJson(String json) {
        int schema = 0;
        Map<String, TreeSet<String>> commands = new LinkedHashMap<>();

        java.util.regex.Matcher schemaMatch = java.util.regex.Pattern.compile("\"schemaVersion\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (schemaMatch.find()) {
            schema = Integer.parseInt(schemaMatch.group(1));
        }

        java.util.regex.Matcher rows = java.util.regex.Pattern.compile(
                        "\"([a-z0-9 -]+)\"\\s*:\\s*\\[([^\\]]*)\\]", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(json);
        while (rows.find()) {
            TreeSet<String> flags = new TreeSet<>();
            for (String raw : rows.group(2).split(",")) {
                String flag = raw.trim().replaceAll("^\"|\"$", "");
                if (!flag.isEmpty()) {
                    flags.add(flag);
                }
            }
            commands.put(rows.group(1), flags);
        }

        Map<String, TreeSet<String>> sorted = new TreeMap<>(Comparator.naturalOrder());
        sorted.putAll(commands);
        return new Surface(schema, sorted);
    }

    // ==========================================
    // Comparison
    // ==========================================

    /** How large a version bump moving from {@code previous} to this surface requires. */
    public Bump requiredBump(Surface previous) {
        for (Map.Entry<String, TreeSet<String>> was : previous.commands.entrySet()) {
            TreeSet<String> now = commands.get(was.getKey());
            if (now == null) {
                return Bump.MAJOR;
            }
            for (String flag : was.getValue()) {
                if (!now.contains(flag)) {
                    return Bump.MAJOR;
                }
            }
        }

        if (schemaVersion > previous.schemaVersion) {
            return Bump.MINOR;
        }
        for (Map.Entry<String, TreeSet<String>> now : commands.entrySet()) {
            TreeSet<String> was = previous.commands.get(now.getKey());
            if (was == null || !was.containsAll(now.getValue())) {
                return Bump.MINOR;
            }
        }

        // A schema version that went *backwards* is not a smaller change, it is a mistake. Saying
        // "patch" here would let a release ship a store older builds have already migrated past.
        if (schemaVersion < previous.schemaVersion) {
            return Bump.MAJOR;
        }
        return Bump.PATCH;
    }

    /** The human-readable reason, for the message the guard prints when it refuses. */
    public List<String> differences(Surface previous) {
        List<String> out = new ArrayList<>();
        for (String name : previous.commands.keySet()) {
            if (!commands.containsKey(name)) {
                out.add("removed command: " + name);
            }
        }
        for (Map.Entry<String, TreeSet<String>> was : previous.commands.entrySet()) {
            TreeSet<String> now = commands.get(was.getKey());
            if (now != null) {
                for (String flag : was.getValue()) {
                    if (!now.contains(flag)) {
                        out.add("removed flag: " + was.getKey() + " " + flag);
                    }
                }
            }
        }
        for (String name : commands.keySet()) {
            if (!previous.commands.containsKey(name)) {
                out.add("added command: " + name);
            }
        }
        for (Map.Entry<String, TreeSet<String>> now : commands.entrySet()) {
            TreeSet<String> was = previous.commands.get(now.getKey());
            if (was != null) {
                for (String flag : now.getValue()) {
                    if (!was.contains(flag)) {
                        out.add("added flag: " + now.getKey() + " " + flag);
                    }
                }
            }
        }
        if (schemaVersion != previous.schemaVersion) {
            out.add("schema version: " + previous.schemaVersion + " → " + schemaVersion);
        }
        return out;
    }

    /** The three sizes of change, smallest first. */
    public enum Bump {
        PATCH,
        MINOR,
        MAJOR;

        /** What moving from one version to another actually was. */
        public static Bump between(int[] from, int[] to) {
            if (to[0] != from[0]) {
                return MAJOR;
            }
            if (to[1] != from[1]) {
                return MINOR;
            }
            return PATCH;
        }
    }

    /** Splits {@code 1.11.0} into its three numbers. */
    public static int[] parseVersion(String version) {
        String[] parts = version.trim().split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a three-part version: " + version);
        }
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }
}
