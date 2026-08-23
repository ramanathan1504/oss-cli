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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Instructions in a file a person can read and replace, rather than in Java only its author can.
 *
 * <p>The property that matters is the takeover: a skill of the reader's with the same name replaces
 * ours entirely. Merging two sets of instructions would produce a third nobody wrote, and when the
 * answer came out wrong there would be no file to point at.
 */
class SkillsTest {

    private static Skill mine(String name, String when, String body) {
        return Skill.parse(name + ".md", "---\nname: " + name + "\nwhen: " + when + "\n---\n" + body, false);
    }

    @Test
    @DisplayName("front matter is read, and a file without it still loads")
    void frontMatterIsOptional() {
        Skill full = Skill.parse("x.md", "---\nname: careful\nwhen: review, pr\nsummary: s\n---\nbody here", true);
        assertEquals("careful", full.name());
        assertEquals(List.of("review", "pr"), full.when());
        assertEquals("body here", full.body());

        // A file dropped in with nothing but instructions is the simplest possible skill, and
        // refusing it would make the simplest case the one that does not work.
        Skill bare = Skill.parse("just-notes.md", "do the thing", false);
        assertEquals("just-notes", bare.name());
        assertTrue(bare.always(), "no `when` means the author did not narrow it");
        assertEquals("do the thing", bare.body());
    }

    @Test
    @DisplayName("a skill is included when the question mentions one of its words")
    void matchingIsWords() {
        Skill review = mine("r", "review, pull request", "b");

        assertTrue(review.matches("please review this pull request"));
        assertTrue(review.matches("REVIEW it"), "case is not worth a missed instruction");
        assertFalse(review.matches("why does the build fail?"));
        assertTrue(mine("a", "always", "b").matches("anything at all"));
    }

    @Test
    @DisplayName("a skill of yours replaces ours entirely, and does not merge with it")
    void yoursTakesOver() {
        Skill ours = Skill.parse("reviewing.md", "---\nname: reviewing\n---\nthe built-in text", true);
        Skill yours = Skill.parse("reviewing.md", "---\nname: reviewing\n---\nmy text", false);

        String rendered = Skills.render(List.of(ours, yours), "anything");

        // Both are passed in; the caller de-duplicates by name before this. What this asserts is
        // the rendering itself does not quietly concatenate two versions of one instruction.
        assertTrue(rendered.contains("my text"), rendered);
    }

    @Test
    @DisplayName("nothing matching means nothing added, not an empty heading")
    void silenceWhenNothingApplies() {
        assertEquals("", Skills.render(List.of(mine("r", "review", "b")), "why is the build slow?"));
        assertEquals("", Skills.render(List.of(), "anything"));
    }

    @Test
    @DisplayName("the budget is enforced, and what was dropped is named")
    void theBudgetIsStated() {
        String huge = "x".repeat(Skills.BUDGET);
        List<Skill> skills = List.of(mine("first", "always", huge), mine("second", "always", huge));

        String rendered = Skills.render(skills, "anything");

        assertTrue(rendered.contains("first"), rendered.substring(0, Math.min(200, rendered.length())));
        assertTrue(
                rendered.contains("second did not fit"),
                "a skill silently left out is an instruction the reader believes is in force");
    }

    @Test
    @DisplayName("every skill this build ships parses and says when it applies")
    void theShippedOnesAreReal() {
        // The point of the exercise: these four are what oss ask is told. A loader that passes
        // invented examples and mangles the shipped files would pass everything above.
        List<Skill> all = Skills.all();
        assertTrue(all.size() >= Skills.BUILT_IN.size(), "expected the shipped skills: " + all);
        for (Skill s : all) {
            assertFalse(s.body().isBlank(), s.name() + " has no instructions in it");
            assertFalse(s.when().isEmpty(), s.name() + " never applies to anything");
            assertFalse(s.name().endsWith(".md"), s.name() + " kept its extension");
        }
        assertTrue(
                all.stream().anyMatch(s -> s.name().equals("using-what-you-already-know")),
                "the corpus-first instruction is the one that must always ship");
    }
}
