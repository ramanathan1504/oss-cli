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
package com.osscli.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * That a timeout actually ends something.
 *
 * <p>An {@code oss memory sessions --enrich --claude} run was still resident long after its
 * 120-second budget, doing nothing. The deadline loop was correct and the process it killed was the
 * wrong one -- so the budget expired, the code moved on, and the work carried on underneath it.
 *
 * <p>Three separate defects on one path, each of which alone is enough to hang the command. They
 * are tested separately because fixing one and believing the path is fixed is how the second and
 * third survived the first reading.
 */
class CliTimeoutTest {

    @Test
    @DisplayName("killing a launcher kills what it launched")
    @DisabledOnOs(OS.WINDOWS)
    void descendantsDieToo() throws IOException, InterruptedException {
        // These tools are launchers: a shell in front of a Node process in front of whatever that
        // spawns. destroyForcibly ends the one this code started and leaves the tree running --
        // still working, still holding the pipes, invisible to the code that gave up on it.
        Process p = new ProcessBuilder("sh", "-c", "sleep 120 & sleep 120").start();
        // Let the shell get as far as starting its child.
        Thread.sleep(300);
        List<ProcessHandle> before = p.descendants().toList();
        assertFalse(before.isEmpty(), "the fixture did not start a child; the test proves nothing");

        CliClient.kill(p);

        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the process itself did not die");
        for (ProcessHandle child : before) {
            // Waited for, not asked about.
            //
            // The first version called onExit().orTimeout(...).isDone(), which reports whether the
            // future has completed *at that instant* rather than waiting for it -- so the test
            // asked "is it dead yet" a microsecond after the signal and passed only on machines
            // where the answer happened to be yes. It failed on one CI runner and nowhere else,
            // which is the signature of a race in the test rather than a bug in the code.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(
                    child.isAlive(), "a child outlived the timeout that was supposed to end the work: " + child.pid());
        }
    }

    @Test
    @DisplayName("a stuck pipe reader can never hold the JVM open")
    void pipeThreadsAreDaemons() throws IOException {
        // The default thread factory makes non-daemon threads. These two read pipes that a wedged
        // child may never close, so one stuck in read() keeps the JVM alive after main returns --
        // which is precisely what was observed, and is indistinguishable from a hung command.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/llm/CliClient.java"), StandardCharsets.UTF_8);
        int pool = source.indexOf("newFixedThreadPool");
        assertTrue(pool > 0, "the pipe pool moved; this guard needs rewriting");

        assertTrue(
                source.indexOf("setDaemon(true)", pool) > 0
                        && source.indexOf("setDaemon(true)", pool) < source.indexOf("submit(", pool),
                "the pipe readers must be daemon threads or a timeout cannot end the process");
    }

    @Test
    @DisplayName("waiting for output is bounded, like waiting for the process was")
    void drainingIsBounded() throws IOException {
        // The deadline undone one line below where it was enforced: the process exiting does not
        // mean the pipe closed, because a grandchild can inherit the write end. An unbounded get()
        // after a bounded waitFor() waits for an EOF that is never coming.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/llm/CliClient.java"), StandardCharsets.UTF_8);

        assertFalse(
                source.contains("out = stdout.get();"),
                "an unbounded get() after a bounded wait is the timeout undone");
        assertTrue(source.contains("stdout.get(PIPE_DRAIN_SECONDS"), "draining stdout must have a deadline");
        assertTrue(source.contains("stderr.get(PIPE_DRAIN_SECONDS"), "draining stderr must have a deadline");
    }
}
