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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That an empty file does not become a vector. */
class EmptyNoteTest {

    @Test
    @DisplayName("an empty note is not indexed")
    void emptyIsSkipped() {
        // Found in a real corpus: six 0-byte files, each with a full 8,059-byte vector, each the
        // embedding of the empty string — so identical to one another and equidistant from every
        // query. They matched everything at the same score and crowded out real passages.
        assertFalse(com.osscli.retrieval.NoteIndexer.worthIndexing(""));
        assertFalse(com.osscli.retrieval.NoteIndexer.worthIndexing("   \n\t  \n"));
        assertFalse(com.osscli.retrieval.NoteIndexer.worthIndexing(null));
    }

    @Test
    @DisplayName("a note with anything in it still is")
    void contentIsIndexed() {
        assertTrue(com.osscli.retrieval.NoteIndexer.worthIndexing("a"));
        assertTrue(com.osscli.retrieval.NoteIndexer.worthIndexing("# heading\n\nsome notes about appenders\n"));
    }
}
