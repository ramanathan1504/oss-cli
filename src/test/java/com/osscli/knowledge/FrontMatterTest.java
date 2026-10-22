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

package com.osscli.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrontMatterTest {

    @Test
    @DisplayName("every note opens with the same six keys, in the same order")
    void coreKeysComeFirstInOrder() {
        String block = FrontMatter.of("A title", "pr", "topic", "2026-01-02T03:04:05Z", "involved", List.of("a"))
                .put("github", "owner/name#12")
                .render();

        assertEquals(
                "---\ntitle: A title\nkind: pr\ntopic: topic\ndate: 2026-01-02\nsource: involved\ntags: [a]\n"
                        + "github: owner/name#12\n---\n\n",
                block);
    }

    @Test
    @DisplayName("a value YAML would misread is quoted, and only then")
    void quotesOnlyWhatYamlNeeds() {
        assertEquals("owner/name#12", FrontMatter.scalar("owner/name#12"));
        assertEquals("https://example.org/a", FrontMatter.scalar("https://example.org/a"));
        assertEquals("\"Profile: summary\"", FrontMatter.scalar("Profile: summary"));
        assertEquals("\"`Visitor`'s branch\"", FrontMatter.scalar("`Visitor`'s branch"));
        assertEquals("\"- leading dash\"", FrontMatter.scalar("- leading dash"));
        assertEquals("\"say \\\"hi\\\"\"", FrontMatter.scalar("say \"hi\""));
        assertEquals("\"a, b\"", FrontMatter.scalar("a, b"));
    }

    @Test
    @DisplayName("a quoted value and a quoted list item read back as they were written")
    void quotingRoundTrips() {
        for (String v : List.of("Profile: summary", "say \"hi\"", "`x`", "plain", "back\\slash")) {
            assertEquals(v, FrontMatter.unquote(FrontMatter.scalar(v)), v);
        }
        String list = FrontMatter.list(List.of("a", "b: c", "d, e"));
        assertEquals(List.of("a", "b: c", "d, e"), FrontMatter.items(list));
    }

    @Test
    @DisplayName("a missing or blank key is left out rather than written empty")
    void blankValuesAreOmitted() {
        String block = FrontMatter.of("t", "note", "", null, "manual", List.of())
                .put("empty", " ")
                .put("none", null)
                .render();

        assertTrue(block.contains("topic: general\n"), block);
        assertTrue(block.contains("tags: []\n"), block);
        assertTrue(!block.contains("empty:") && !block.contains("none:"), block);
    }
}
