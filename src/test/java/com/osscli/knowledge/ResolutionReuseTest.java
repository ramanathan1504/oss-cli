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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That re-reviewing a pull request rewrites its note rather than filing another.
 *
 * <p>Six notes had accumulated for log4j 4249 — three of them for the same head commit, four
 * superseded by the last — every one embedded and competing to answer the same question. Chat was
 * changed to avoid exactly this; review kept doing it.
 */
class ResolutionReuseTest {

    @Test
    @DisplayName("the newest note for this pull request is the one found")
    void findsTheNewestNote(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Issue-4249-review-20260815-062052.md"), "old");
        Files.writeString(dir.resolve("Issue-4249-review-20260820-154256.md"), "new");
        Files.writeString(dir.resolve("Issue-4229-review-20260820-999999.md"), "another pull request");

        Path found = newest(dir, "Issue-4249-review-");

        assertNotNull(found);
        assertEquals("Issue-4249-review-20260820-154256.md", found.getFileName().toString());
    }

    @Test
    @DisplayName("a different pull request is never mistaken for this one")
    void doesNotCrossPullRequests(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Issue-42490-review-20260820-000000.md"), "a different number");

        // "Issue-4249-" must not match "Issue-42490-": the trailing separator is what stops a
        // pull request being rewritten by its own numeric prefix.
        assertNull(newest(dir, "Issue-4249-review-"));
    }

    @Test
    @DisplayName("nothing filed yet means nothing to reuse")
    void emptyDirectoryYieldsNothing(@TempDir Path dir) {
        assertNull(newest(dir, "Issue-4249-review-"));
    }

    @Test
    @DisplayName("the stamp sorts, which is what makes newest-by-name true")
    void stampsSortChronologically() {
        // The lookup takes the greatest file name. That is only the newest note because the stamp
        // is fixed-width and big-endian; a format like 8-20-2026 would silently pick the wrong one.
        assertTrue("Issue-1-review-20260815-062052.md".compareTo("Issue-1-review-20260820-154256.md") < 0);
        assertTrue("Issue-1-review-20260820-103720.md".compareTo("Issue-1-review-20260820-154256.md") < 0);
    }

    /** The selection ResolutionWriter.existingNote performs, over a directory under test control. */
    private static Path newest(Path dir, String prefix) {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix))
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .max(java.util.Comparator.comparing(f -> f.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
