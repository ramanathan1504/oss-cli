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
package com.osscli.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.Main;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * That the shortlist stays a shortlist, and stays complete.
 *
 * <p>Two ways a grouped menu rots, and both have already happened to the flat one it replaced. A
 * command gets added and nobody puts it in a group, so it is invisible in the only list most people
 * read. Or a command gets hidden and its name stays in the group, pointing at nothing.
 *
 * <p>Neither is caught by reading. Both are caught here.
 */
class CommandGroupsTest {

    /** What {@code oss --help} shows, minus the engine prefixes, which are not verbs. */
    private static List<String> visible() {
        CommandLine cli = Main.commandLine();
        List<String> names = new ArrayList<>();
        for (CommandLine sub : cli.getSubcommands().values()) {
            CommandLine.Model.CommandSpec spec = sub.getCommandSpec();
            if (spec.usageMessage().hidden()) {
                continue;
            }
            if (com.osscli.llm.Ai.prefixes().contains(spec.name())) {
                continue;
            }
            if (!names.contains(spec.name())) {
                names.add(spec.name());
            }
        }
        return names;
    }

    @Test
    @DisplayName("every command the short help shows is in exactly one group")
    void nothingFallsOffTheEnd() {
        List<String> grouped = CommandGroups.all();
        List<String> missing = new ArrayList<>(visible());
        missing.removeAll(grouped);
        assertTrue(
                missing.isEmpty(),
                "these appear in oss --help but belong to no group, so the grouped list cannot show "
                        + "them — decide where they go: " + missing);
    }

    @Test
    @DisplayName("no group names a command that is hidden or gone")
    void noGhosts() {
        List<String> visible = visible();
        List<String> ghosts = new ArrayList<>();
        for (String named : CommandGroups.all()) {
            if (!visible.contains(named)) {
                ghosts.add(named);
            }
        }
        assertTrue(ghosts.isEmpty(), "grouped, but not shown by oss --help: " + ghosts);
    }

    @Test
    @DisplayName("a command is in one group only")
    void noneIsInTwoPlaces() {
        List<String> all = CommandGroups.all();
        assertEquals(new TreeSet<>(all).size(), all.size(), "a command listed twice reads as two different things");
    }

    @Test
    @DisplayName("the shortlist stays short enough to hold in your head")
    void fifteenIsTheBudget() {
        assertTrue(
                CommandGroups.all().size() <= 15,
                "the whole point of the groups is that the list is memorable; it is now "
                        + CommandGroups.all().size() + ". Hide one, or fold it into another.");
        assertTrue(
                CommandGroups.GROUPS.size() <= 8,
                "more than eight headings is a second list to remember rather than a way into the first");
    }
}
