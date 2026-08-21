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
package com.osscli.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one platform that lost the embedder, and the sentence that says so.
 *
 * <p>Bumping onnxruntime from 1.22 to 1.29 removed {@code osx-x64} from the jar: 35.7 MB of Intel
 * Mac library in the old build, nothing in the new one. Counted by listing both jars, not by
 * reading a changelog.
 *
 * <p>An Intel Mac therefore fails to load the model with a message about a missing native library,
 * which reads as a broken install and is nothing of the kind. The machine that has the problem is
 * the one machine that cannot run this test, so the sentence is tested by being handed the
 * platform rather than by asking the JVM which one it is on.
 */
class PlatformNoteTest {

    @Test
    @DisplayName("an Intel Mac is told its hardware lost the build, not that it misconfigured something")
    void intelMacIsExplained() {
        String note = LocalEmbedder.unsupportedPlatformNote("Mac OS X", "x86_64");

        assertTrue(note.contains("Intel-Mac"), "it must name the platform: " + note);
        assertTrue(note.contains("Nothing is misconfigured"), "and rule out the reader's setup: " + note);
        // The floor is the point: losing the model is not losing search.
        assertTrue(note.contains("by term"), "and say what still works: " + note);
        assertEquals(note, LocalEmbedder.unsupportedPlatformNote("Mac OS X", "amd64"), "amd64 is the same machine");
    }

    @Test
    @DisplayName("every other platform gets nothing, because for them it is a real fault")
    void everyoneElseGetsNothing() {
        // Apple silicon, Linux and Windows all still ship a native. On those, a load failure means
        // something IS wrong, and appending "your hardware lost the build" would send the reader
        // away from the actual cause.
        assertEquals("", LocalEmbedder.unsupportedPlatformNote("Mac OS X", "aarch64"));
        assertEquals("", LocalEmbedder.unsupportedPlatformNote("Linux", "amd64"));
        assertEquals("", LocalEmbedder.unsupportedPlatformNote("Windows 11", "amd64"));
    }
}
