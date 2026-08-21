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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Every command can print its own usage.
 *
 * <p>Four could not. {@code ext add}, {@code ext list}, {@code ext remove} and {@code ext refresh}
 * were the only commands in the tree declared without {@code mixinStandardHelpOptions}, so
 * {@code oss ext add --help} answered <em>"Missing required parameter: '&lt;path&gt;'"</em> and
 * {@code oss ext list --help} answered <em>"Unknown option: '--help'"</em>. Both exit 2. The parent
 * {@code oss ext --help} worked, which is why it survived: the shape that is checked by hand is the
 * one people check.
 *
 * <p>Walked from the real command tree rather than from a list of names. A list would have to be
 * updated by the person adding a command, and the whole failure here was a command added without
 * remembering something.
 *
 * <p>Usage is the one thing every command owes regardless of configuration — no token, no model, no
 * database. A command that cannot print it is broken for everybody, including the reader trying to
 * find out how to use it.
 */
class EveryCommandAnswersHelpTest {

    /**
     * The engine prefixes, excluded by name and on purpose.
     *
     * <p>{@code oss claude --help} refuses deliberately: these are passthroughs, and
     * {@code --help} never asks a model anything, so answering it here would imply the engine was
     * involved in producing usage that it had nothing to do with. It says so and points at
     * {@code oss --help}. Listed here rather than skipped by a rule, so that a command which stops
     * answering {@code --help} for any OTHER reason still fails this test.
     */
    private static final Set<String> PASSTHROUGH = Set.of("llm", "claude", "gemini", "codex");

    private static List<String> everyCommandPath() {
        List<String> paths = new ArrayList<>();
        collect(Main.commandLine(), "", paths);
        return paths;
    }

    private static void collect(CommandLine parent, String prefix, List<String> out) {
        // A subcommand map is keyed by alias as well as by name, so `memory` and `kb` are the same
        // command twice. Deduplicated on the canonical name -- running it twice would pass twice.
        Set<CommandLine> seen = new LinkedHashSet<>(parent.getSubcommands().values());
        for (CommandLine sub : seen) {
            String name = sub.getCommandSpec().name();
            String path = prefix.isEmpty() ? name : prefix + " " + name;
            out.add(path);
            collect(sub, path, out);
        }
    }

    @Test
    @DisplayName("every command answers --help with exit 0 and its own usage")
    void everyCommandPrintsItsUsage() {
        List<String> paths = everyCommandPath();
        assertTrue(paths.size() > 30, "the tree should have been walked, found " + paths.size());

        List<String> broken = new ArrayList<>();
        for (String path : paths) {
            if (PASSTHROUGH.contains(path)) {
                continue;
            }
            String[] args = (path + " --help").split(" ");
            Cli.Result r = Cli.run(args);
            if (!r.ok()) {
                broken.add(path + " -> exit " + r.exitCode() + ": "
                        + r.all().lines().findFirst().orElse("(no output)"));
                continue;
            }
            // Exit 0 alone is not enough. `oss ext add --help` could exit 0 having printed the
            // ROOT usage, which tells the reader nothing about `ext add` -- so the usage has to
            // name the command that was asked about.
            String leaf = path.substring(path.lastIndexOf(' ') + 1);
            if (!r.says("Usage: oss " + path) && !r.says(leaf)) {
                broken.add(path + " -> exit 0 but printed somebody else's usage");
            }
        }
        assertEquals(List.of(), broken, "commands that cannot print their own usage");
    }

    @Test
    @DisplayName("the passthroughs refuse --help, and say where to get it")
    void passthroughsRefuseAndRedirect() {
        // The exclusion above is only honest if the excluded behaviour is itself asserted.
        // Otherwise "excluded by name" becomes a hole that a real regression can hide in.
        for (String prefix : PASSTHROUGH) {
            Cli.Result r = Cli.run(prefix, "--help");
            assertEquals(2, r.exitCode(), prefix + " should refuse --help");
            assertTrue(r.says("oss --help"), prefix + " must say where usage actually comes from: " + r.all());
        }
    }
}
