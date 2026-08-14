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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the console stays synchronous and the file does not.
 *
 * <p>In this program {@code LOGGER.info} is not diagnostics, it is the user interface: the console
 * appender's layout is a bare {@code %msg%n} to stdout, so the doctor report, the chat banner and a
 * review verdict all reach the screen through it. Queuing that output breaks two things that have
 * nothing to do with speed.
 *
 * <ul>
 *   <li><b>Ordering.</b> The chat loop prints its prompt with {@code System.err.print("\n> ")} and
 *       blocks on stdin. A queued answer would land after the prompt, and after the user had begun
 *       typing into it.
 *   <li><b>Delivery.</b> The picker takes the terminal into raw mode and an alternate screen. A line
 *       arriving late lands on a display that no longer expects it, and a line still queued at exit
 *       is never seen at all.
 * </ul>
 *
 * <p>The file appender is the opposite case — DEBUG, rolling, far larger, and nobody reads it live.
 * Every console line the user waited for also waited on a synchronous write to it, and that is the
 * I/O worth moving off the critical path.
 *
 * <p>This is asserted against the configuration rather than at runtime because the failure it
 * guards against is an edit, not a bug: somebody making "the logging" async without noticing that
 * one of these two appenders is the screen.
 */
class LoggingConfigTest {

    private static String config() throws IOException {
        return Files.readString(Path.of("src/main/resources/log4j2.xml"));
    }

    /** Everything between the opening and closing tag of the named element, attributes included. */
    private static String element(String xml, String tag, String name) {
        int at = xml.indexOf("<" + tag);
        while (at >= 0) {
            int close = xml.indexOf(">", at);
            String head = xml.substring(at, close + 1);
            if (head.contains("name=\"" + name + "\"")) {
                int end = xml.indexOf("</" + tag + ">", close);
                return end < 0 ? head : xml.substring(at, end);
            }
            at = xml.indexOf("<" + tag, at + 1);
        }
        throw new AssertionError("no <" + tag + " name=\"" + name + "\"> in log4j2.xml");
    }

    // ==========================================
    // The configuration as log4j actually loaded it
    // ==========================================

    private static org.apache.logging.log4j.core.config.Configuration loaded() {
        return ((org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false))
                .getConfiguration();
    }

    @Test
    @DisplayName("at runtime, the console appender is not an async one")
    void runtimeConsoleIsDirect() {
        // The XML is what was written; this is what log4j built from it. A plugin that failed to
        // load, a typo in a ref, an override from a system property -- none of those show up in a
        // file read, and all of them change what actually reaches the screen.
        var appenders = loaded().getAppenders();

        assertTrue(appenders.containsKey("Console"), "the console appender must exist: " + appenders.keySet());
        assertFalse(
                appenders.get("Console") instanceof org.apache.logging.log4j.core.appender.AsyncAppender,
                "the console must not have become asynchronous");
    }

    @Test
    @DisplayName("at runtime, the file appender IS an async one")
    void runtimeFileIsAsync() {
        var async = loaded().getAppenders().get("AsyncFile");

        assertNotNull(async, "AsyncFile must be built");
        assertTrue(
                async instanceof org.apache.logging.log4j.core.appender.AsyncAppender,
                "AsyncFile resolved to " + async.getClass().getName() + ", not an AsyncAppender");
    }

    @Test
    @DisplayName("at runtime, the root logger reaches the console directly and the file through the queue")
    void runtimeRootWiring() {
        var refs = loaded().getRootLogger().getAppenderRefs().stream()
                .map(r -> r.getRef())
                .toList();

        assertTrue(refs.contains("Console"), "root should reference Console directly: " + refs);
        assertTrue(refs.contains("AsyncFile"), "root should reach the file through AsyncFile: " + refs);
        assertFalse(refs.contains("File"), "root should NOT also write the file synchronously: " + refs);
    }

    @Test
    @DisplayName("the console is written directly, never through a queue")
    void consoleIsSynchronous() throws IOException {
        String xml = config();

        // The root logger must reference the Console appender itself. If it ever referenced an
        // async wrapper around it instead, the screen would be on a queue.
        assertTrue(xml.contains("<AppenderRef ref=\"Console\""), "the root logger must write to the console directly");

        for (String queued : new String[] {"AsyncConsole", "ref=\"AsyncConsole\""}) {
            assertFalse(xml.contains(queued), "the console must not be wrapped in an async appender: found " + queued);
        }
    }

    @Test
    @DisplayName("the file appender is asynchronous")
    void fileIsAsynchronous() throws IOException {
        String xml = config();
        String async = element(xml, "Async", "AsyncFile");

        assertTrue(async.contains("<AppenderRef ref=\"File\""), "the async wrapper must wrap the rolling file");
        assertTrue(
                xml.contains("<AppenderRef ref=\"AsyncFile\""),
                "the root logger must reach the file through the async wrapper, or wrapping it achieved nothing");
    }

    @Test
    @DisplayName("the queue is the lock-free one, which is the reason for doing this")
    void theQueueIsTheDisruptor() throws IOException {
        assertTrue(
                element(config(), "Async", "AsyncFile").contains("<DisruptorBlockingQueue"),
                "without it this is an ArrayBlockingQueue taking a lock per message");
    }

    @Test
    @DisplayName("a full buffer blocks rather than discarding")
    void diagnosticsAreNotDropped() throws IOException {
        // The alternative silently discards messages once the buffer fills -- and the moment it
        // fills is exactly the moment worth reading about afterwards. A slow log beats a log with
        // the incident cut out of it.
        assertTrue(
                element(config(), "Async", "AsyncFile").contains("blocking=\"true\""),
                "the diagnostic log must not drop the entries written during an incident");
    }

    @Test
    @DisplayName("the disruptor queue is on the classpath, so it cannot silently fall back")
    void theQueueClassIsActuallyPresent() {
        // This is the failure the pom grep could not see. DisruptorBlockingQueue is a log4j
        // plugin: when the jar is absent, log4j notes it in the status logger and carries on
        // with the default ArrayBlockingQueue. Nothing fails, nothing is logged at WARN, and
        // the lock-free queue that was the entire reason for this is quietly not there.
        //
        // Shading is where that would happen -- a relocation rule or a minimizeJar that drops
        // an apparently unreferenced class. A declaration in the pom says what was asked for;
        // this says what arrived.
        assertDoesNotThrow(
                () -> Class.forName("com.conversantmedia.util.concurrent.DisruptorBlockingQueue"),
                "the disruptor jar is missing, so the async appender is silently using ArrayBlockingQueue");
    }
}
