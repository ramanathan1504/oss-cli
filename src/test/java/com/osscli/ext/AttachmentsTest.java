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
package com.osscli.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pack is a subject; a support pack is something attached to it.
 *
 * <p>The registry was flat, so a bench built for Log4j looked exactly as applicable to a Kafka
 * issue as to a Log4j one, and the model was told about neither. These are the properties that
 * makes the tree worth having.
 *
 * <p>Every test here works on lists it built itself. Nothing reads
 * {@code ~/.oss-cli/extensions.json} and nothing opens the database — a test in this repository
 * once "redirected" itself with a system property that redirects nothing and deleted a real 496 MB
 * store. The seams exist so there is nothing to redirect.
 */
class AttachmentsTest {

    private static Extension ext(String name, String kind, String supports) {
        Extension e = new Extension();
        e.setName(name);
        e.setKind(kind);
        e.setSupports(supports);
        return e;
    }

    private static final List<String> FOLLOWED =
            List.of("apache/logging-log4j2", "apache/kafka", "elastic/elasticsearch");

    @Test
    @DisplayName("a manifest may name the repository in full, or just its name")
    void matchIsExactThenTheNameAfterTheSlash() {
        assertEquals("apache/logging-log4j2", Attachments.match("apache/logging-log4j2", FOLLOWED));
        assertEquals("apache/logging-log4j2", Attachments.match("logging-log4j2", FOLLOWED));
        assertEquals("apache/kafka", Attachments.match("KAFKA", FOLLOWED), "spelling is not worth an argument");
        assertEquals("apache/logging-log4j2", Attachments.match("  logging-log4j2  ", FOLLOWED));
    }

    @Test
    @DisplayName("a half-name never attaches to something that merely contains it")
    void matchIsNotFuzzy() {
        // "log4j" is a substring of "logging-log4j2" and must NOT resolve. Fuzzy is right here and
        // confidently wrong the first time two followed repositories share a word -- and the failure
        // would arrive as an assured answer about the wrong project rather than as an error.
        assertEquals("log4j", Attachments.match("log4j", FOLLOWED));
    }

    @Test
    @DisplayName("a subject that is not followed is kept, not dropped")
    void unfollowedSubjectsSurvive() {
        List<Attachments.Pack> tree = Attachments.tree(List.of(ext("bench", "runner", "not/followed")), FOLLOWED);

        Attachments.Pack pack = tree.stream()
                .filter(p -> p.name().equals("not/followed"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("an unmatched subject must still appear: " + tree));
        assertTrue(pack.supported());
        assertFalse(pack.followed(), "and must say that it is not one of yours");
    }

    @Test
    @DisplayName("supporters nest under their subject, and supported packs come first")
    void treeGroupsAndOrders() {
        List<Attachments.Pack> tree = Attachments.tree(
                List.of(ext("workout", "runner", "logging-log4j2"), ext("notes", "memory", "apache/logging-log4j2")),
                FOLLOWED);

        assertEquals("apache/logging-log4j2", tree.get(0).name(), "the pack with something attached leads");
        assertEquals(
                List.of("workout", "notes"),
                tree.get(0).supporters().stream().map(Extension::getName).toList(),
                "both attach to the same subject, spelled two ways");

        List<String> rest = tree.subList(1, tree.size()).stream()
                .map(Attachments.Pack::name)
                .toList();
        assertTrue(rest.contains("apache/kafka"), "every followed repository is a pack, attached or not");
        for (int i = 1; i < tree.size(); i++) {
            assertFalse(tree.get(i).supported(), "supported packs must not appear after bare ones");
        }
    }

    @Test
    @DisplayName("an extension naming no subject applies everywhere and nests nowhere")
    void unattachedStayOutOfTheTree() {
        List<Extension> registered = List.of(ext("global", "memory", null), ext("blank", "memory", "  "));
        assertEquals(
                List.of("global", "blank"),
                Attachments.unattached(registered).stream()
                        .map(Extension::getName)
                        .toList());
        for (Attachments.Pack pack : Attachments.tree(registered, FOLLOWED)) {
            assertFalse(pack.supported(), "nothing declared a subject, so nothing is attached to one");
        }
    }

    @Test
    @DisplayName("with nothing registered the model is told nothing")
    void silenceWhenThereIsNothingToSay() {
        assertEquals("", Attachments.forPrompt(List.of(), FOLLOWED), "fourteen empty packs is not orientation");
    }

    @Test
    @DisplayName("the prompt block states the subject and declines to give orders")
    void promptStatesRatherThanInstructs() {
        String block = Attachments.forPrompt(List.of(ext("workout", "runner", "logging-log4j2")), FOLLOWED);

        assertTrue(block.contains("apache/logging-log4j2"), block);
        assertTrue(block.contains("workout"), block);
        assertTrue(block.contains("not instructions"), "told to use a bench, a model will use it on anything");
        assertFalse(block.toLowerCase(java.util.Locale.ROOT).contains("you should"), block);
    }

    @Test
    @DisplayName("the prompt block is capped, and says how many it left out")
    void promptIsBounded() {
        List<Extension> many = new ArrayList<>();
        List<String> followed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            followed.add("owner/repo-" + i);
            many.add(ext("bench-" + i, "runner", "owner/repo-" + i));
        }
        String block = Attachments.forPrompt(many, followed);

        assertTrue(block.contains("8 further pack(s) not listed here"), block);
        assertFalse(block.contains("bench-19"), "the cap must actually stop, not merely be documented");
    }

    @Test
    @DisplayName("a subject narrows several candidates to the one built for it")
    void resolutionPrefersTheSupportingExtension() {
        Extension log4j = ext("workout", "runner", "apache/logging-log4j2");
        Extension kafka = ext("kafka-bench", "runner", "apache/kafka");

        assertSame(
                log4j,
                ExtensionRegistry.prefer(List.of(log4j, kafka), List.of("workout"))
                        .get(0));
        assertEquals(
                1,
                ExtensionRegistry.prefer(List.of(log4j, kafka), List.of("workout"))
                        .size());
    }

    @Test
    @DisplayName("a subject that narrows to nothing leaves the choice exactly as it was")
    void preferenceNeverInvents() {
        Extension a = ext("a", "runner", "apache/kafka");
        Extension b = ext("b", "runner", "elastic/elasticsearch");
        List<Extension> both = List.of(a, b);

        // Still ambiguous, and still two: refusing with a list the reader recognises is the right
        // answer. Picking "the first registered" is not a rule anybody could predict.
        assertEquals(both, ExtensionRegistry.prefer(both, List.of()));
    }
}
