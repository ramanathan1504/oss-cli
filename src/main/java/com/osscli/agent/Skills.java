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

import com.osscli.AppPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The skills this build ships, and the ones the reader wrote, in one list.
 *
 * <p>Same shape as everything else here: built in and on by default, and yours takes over. A file
 * in {@code ~/.oss-cli/skills} with the same name as one of ours replaces it entirely rather than
 * merging with it — merging two sets of instructions produces a third nobody wrote, and when the
 * answer comes out wrong there is no file to point at.
 *
 * <p>Built-ins live in the jar so they are there from a Homebrew install with nothing configured.
 * The directory is not created on anybody's behalf: {@code oss skill new} makes it when asked,
 * because a tool that scatters empty folders through a home directory has decided something for
 * somebody.
 */
public final class Skills {

    /** Where a reader's own skills live. */
    public static final Path DIR = AppPaths.BASE_DIR.resolve("skills");

    /** Shipped in the jar. Named explicitly because a jar cannot be listed like a directory. */
    static final List<String> BUILT_IN = List.of(
            "using-what-you-already-know.md",
            "choosing-where-to-answer-from.md",
            "reviewing-a-pull-request.md",
            "changing-code-safely.md");

    /**
     * How much of the skills may enter one prompt.
     *
     * <p>Instructions compete with the corpus for the same budget, and the corpus is the half that
     * cannot be regenerated. Four skills at three hundred words each would be most of a small
     * model's window before the question arrives.
     */
    static final int BUDGET = 6_000;

    private Skills() {}

    /** Everything available, the reader's replacing ours by name, in a stable order. */
    public static List<Skill> all() {
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (String file : BUILT_IN) {
            try (InputStream in = Skills.class.getResourceAsStream("/skills/" + file)) {
                if (in != null) {
                    Skill s = Skill.parse(file, new String(in.readAllBytes(), StandardCharsets.UTF_8), true);
                    byName.put(s.name(), s);
                }
            } catch (IOException e) {
                // A skill that cannot be read is one instruction missing, not a broken command.
            }
        }
        for (Skill mine : fromDisk()) {
            byName.put(mine.name(), mine);
        }
        return List.copyOf(byName.values());
    }

    /** The reader's own, or nothing when the directory has never been made. */
    static List<Skill> fromDisk() {
        List<Skill> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return out;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                try {
                    out.add(Skill.parse(p.getFileName().toString(), Files.readString(p), false));
                } catch (IOException e) {
                    // Reported by `oss skill`, which is where somebody is looking at the list.
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** The instructions that apply to this question, within the budget, or an empty string. */
    public static String forQuestion(String question) {
        return render(all(), question);
    }

    /** As above, over a given set — so the budget and the matching are testable without files. */
    static String render(List<Skill> skills, String question) {
        StringBuilder b = new StringBuilder();
        int spent = 0;
        List<String> dropped = new ArrayList<>();
        for (Skill s : skills) {
            if (!s.matches(question)) {
                continue;
            }
            if (spent + s.body().length() > BUDGET) {
                dropped.add(s.name());
                continue;
            }
            b.append("\n## ").append(s.name()).append('\n').append(s.body()).append('\n');
            spent += s.body().length();
        }
        if (b.length() == 0) {
            return "";
        }
        if (!dropped.isEmpty()) {
            // Said, because a skill silently left out is an instruction the reader believes is in
            // force. Same rule MemoryContext follows for passages it could not fit.
            b.append("\n(").append(String.join(", ", dropped)).append(" did not fit and were left out)\n");
        }
        return "How to do this well:\n" + b;
    }
}
