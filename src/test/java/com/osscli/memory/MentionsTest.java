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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That a mention is a word a reader would count, not letters inside another word. */
class MentionsTest {

    private static int count(String term, String text) {
        return Mentions.of(term).in(text);
    }

    @Test
    @DisplayName("letters inside another word are not a mention")
    void substringsAreNotMentions() {
        assertEquals(0, count("Raft", "a first draft, then a second draft"), "159 notes were about Raft this way");
        assertEquals(0, count("SLO", "the build was slow"));
        assertEquals(0, count("Trie", "passage retrieval"));
        assertEquals(0, count("eta", "see the details in the metadata"));
        assertEquals(0, count("rds", "in other words, the records"));
        assertEquals(0, count("java", "the javadoc is wrong"));
    }

    @Test
    @DisplayName("the word itself is a mention however it is capitalised")
    void wordsAreMentions() {
        assertEquals(3, count("Raft", "Raft elects a leader; raft logs; RAFT"));
        assertEquals(2, count("eta", "ETA Friday, eta slipped"));
        assertEquals(1, count("MDC", "put it in the MDC."));
    }

    @Test
    @DisplayName("a plural or a version number is still the same thing")
    void pluralsAndVersions() {
        assertEquals(1, count("log4j", "upgrade to log4j2"), "a whole-word rule lost 2,241 log4j mentions this way");
        assertEquals(1, count("junit", "migrated to JUnit5"));
        assertEquals(2, count("SLO", "SLOs and SLO burn"));
        assertEquals(1, count("Trie", "compressed tries"));
        assertEquals(1, count("Appender", "two appenders"));
    }

    @Test
    @DisplayName("an identifier made of the term's words is a mention of it")
    void identifiers() {
        assertEquals(1, count("ThreadPool", "new ThreadPoolExecutor(4)"));
        assertEquals(1, count("BlockingQueue", "a LinkedBlockingQueue"));
        assertEquals(1, count("CopyOnWrite", "CopyOnWriteArrayList"));
        assertEquals(1, count("Async Appender", "configure an AsyncAppender"));
        assertEquals(1, count("Async Appender", "two async appenders"));
        assertEquals(1, count("JSON Template Layout", "json-template-layout"));
        assertEquals(1, count("Log4j", "the Log4jLogger bridge"));
        assertEquals(0, count("Appender Ref", "an appender references nothing"));
    }

    @Test
    @DisplayName("a symbol the term starts or ends with is part of it")
    void symbolsInTerms() {
        assertEquals(1, count("@test", "@Test void works() in LoggerTest, with tests"), "every test was @test");
        assertEquals(1, count("@bean", "@Bean DataSource ds() and a bean"));
        assertEquals(2, count("c#", "C# and c#, not c or cc#"));
        assertEquals(3, count("soc 2", "SOC 2, soc2 and SOC-2"));
    }

    @Test
    @DisplayName("mentions do not overlap, and a term with no letters is counted as written")
    void countingAndBlanks() {
        assertEquals(2, count("log", "log log"));
        assertEquals(3, count("☕", "☕ ☕ ☕"));
        assertEquals(0, count("", "anything at all"));
        assertEquals(0, count("  ", "anything at all"));
    }

    @Test
    @DisplayName("a note whose lowercase is a different length is still read correctly")
    void unicodeThatChangesLength() {
        String text = "İstanbul: Raft here, and a draft there. Raft again";
        assertTrue(text.toLowerCase(java.util.Locale.ROOT).length() != text.length(), "the premise of this test");
        assertEquals(2, count("Raft", text));
    }
}
