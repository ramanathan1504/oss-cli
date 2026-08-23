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

import com.osscli.agent.Skill;
import com.osscli.agent.Skills;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * What {@code oss ask} has been told about how to work, and how to change it.
 *
 * <p>Listed rather than hidden, for the reason the extension list is: an instruction the reader
 * cannot see is one they cannot correct when an answer comes out wrong. Every skill here is a
 * markdown file, four of them shipped in this build and any number of theirs beside them.
 *
 * <p>{@code oss skill new <name>} writes a starter with the front matter filled in — the same idea
 * as {@code oss run init} writing a pack, and for the same reason: the format is five lines and a
 * blank page is still a wall.
 */
@Command(
        name = "skill",
        mixinStandardHelpOptions = true,
        description = "What oss ask has been told about how to work — yours override the built-in ones",
        subcommands = {SkillCommand.New.class, SkillCommand.Show.class})
public class SkillCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("  BUILT IN, ON BY DEFAULT");
        int mine = 0;
        for (Skill s : Skills.all()) {
            if (!s.builtIn()) {
                mine++;
                continue;
            }
            row(s);
        }
        if (mine > 0) {
            System.out.println();
            System.out.println("  YOURS  (" + Skills.DIR + ")");
            for (Skill s : Skills.all()) {
                if (!s.builtIn()) {
                    row(s);
                }
            }
        }
        System.out.println();
        System.out.println("  A file of yours with the same name replaces one of ours entirely.");
        System.out.println("  oss skill new <name>     start one, with the front matter filled in");
        System.out.println("  oss skill show <name>    read the one that is in force");
        return 0;
    }

    private static void row(Skill s) {
        System.out.printf("    %-32s %s%n", s.name(), s.summary().isBlank() ? "" : s.summary());
        System.out.printf("    %-32s when: %s%n", "", String.join(", ", s.when()));
    }

    /** Print the one actually in force, whichever it is. */
    @Command(name = "show", mixinStandardHelpOptions = true, description = "Print a skill as oss ask sees it")
    static class Show implements Callable<Integer> {

        @Parameters(index = "0", description = "Skill name")
        String name;

        @Override
        public Integer call() {
            for (Skill s : Skills.all()) {
                if (s.name().equalsIgnoreCase(name)) {
                    System.out.println("  " + s.name() + (s.builtIn() ? "  (built in)" : "  (yours)"));
                    System.out.println("  when: " + String.join(", ", s.when()));
                    System.out.println();
                    System.out.println(s.body());
                    return 0;
                }
            }
            System.err.println("error  no skill called \"" + name + "\" — oss skill");
            return 1;
        }
    }

    /** Write a starter, and never over the top of something already there. */
    @Command(name = "new", mixinStandardHelpOptions = true, description = "Start a skill of your own")
    static class New implements Callable<Integer> {

        @Parameters(index = "0", description = "Skill name, e.g. reviewing-a-pull-request")
        String name;

        @Override
        public Integer call() throws Exception {
            String file = name.replaceAll("[^A-Za-z0-9-]", "-").toLowerCase(java.util.Locale.ROOT) + ".md";
            Path target = Skills.DIR.resolve(file);
            if (Files.exists(target)) {
                // Never silently: this is somebody's own writing, and the whole point of the
                // directory is that what is in it is theirs.
                System.err.println("error  " + target + " already exists");
                System.err.println("       oss skill show " + name + "   to read it");
                return 1;
            }
            Files.createDirectories(Skills.DIR);
            boolean overriding = Skills.all().stream().anyMatch(s -> s.name().equalsIgnoreCase(name));
            Files.writeString(target, """
                    ---
                    name: %s
                    when: always
                    summary: one line, shown in `oss skill`
                    ---
                    Write what oss ask should do for this kind of work.

                    `when` is a comma-separated list of words. The skill is included when the
                    question mentions one of them, or on every question when it says `always`.

                    Say what to establish and in what order, what to check before answering, and
                    what to refuse. Instructions that describe how to think travel better than ones
                    that describe what to say.
                    """.formatted(name));
            System.out.println("  wrote " + target);
            if (overriding) {
                System.out.println("  this replaces the built-in skill of the same name, entirely.");
            }
            System.out.println("  oss skill              see it listed");
            return 0;
        }
    }
}
