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
package com.osscli.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Many requests at once, in the order they were asked for.
 *
 * <p>Sleeps stand in for the network deliberately: the property being checked is that a slow first
 * item does not hold up a fast fourth one, and that the answer still arrives in input order. Both
 * are provable without a socket, and neither is provable with one.
 */
class ParallelTest {

    @Test
    @DisplayName("results come back in input order, never completion order")
    void orderSurvivesUnevenWork() {
        // Deliberately inverted: the first item is the slowest. Anything that appends on completion
        // returns this list reversed, which is exactly the bug that makes a report undiffable.
        List<Integer> items = List.of(60, 40, 20, 10, 5, 1, 0);

        List<String> out = Parallel.map(
                items,
                ms -> {
                    sleep(ms);
                    return "item-" + ms;
                },
                null);

        assertEquals(
                List.of("item-60", "item-40", "item-20", "item-10", "item-5", "item-1", "item-0"),
                out,
                "the slowest item was first in and must be first out");
    }

    @Test
    @Timeout(20)
    @DisplayName("work actually overlaps rather than queueing")
    void workRunsConcurrently() {
        // Twelve items of 200ms each. Serially that is 2.4s; with six lanes it is two rounds, so
        // roughly 400ms. The assertion is deliberately loose -- CI machines are not quiet -- but
        // 1.5s cannot be reached by anything running these one after another.
        List<Integer> items = new ArrayList<>(Collections.nCopies(12, 200));

        long start = System.nanoTime();
        List<Integer> out = Parallel.map(
                items,
                ms -> {
                    sleep(ms);
                    return ms;
                },
                null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(12, out.size());
        assertTrue(elapsedMs < 1500, "twelve 200ms items took " + elapsedMs + "ms — that is serial, not parallel");
    }

    @Test
    @DisplayName("one failure costs one slot, not the run")
    void oneFailureDoesNotEndTheRest() {
        List<Integer> items = List.of(1, 2, 3, 4);

        List<String> out = Parallel.map(
                items,
                n -> {
                    if (n == 2) {
                        throw new IllegalStateException("this row is unreachable");
                    }
                    return "ok-" + n;
                },
                null);

        assertEquals(4, out.size(), "a thrown item still occupies its position");
        assertEquals("ok-1", out.get(0));
        assertNull(out.get(1), "the failure is a null the caller can report on that row");
        assertEquals("ok-3", out.get(2));
        assertEquals("ok-4", out.get(3));
    }

    @Test
    @DisplayName("progress counts completions, on the calling thread, in order")
    void progressIsOrderedAndSingleThreaded() {
        List<Integer> items = List.of(50, 30, 10, 5);
        List<Integer> seen = new ArrayList<>();
        AtomicInteger threads = new AtomicInteger();
        String caller = Thread.currentThread().getName();

        Parallel.map(
                items,
                ms -> {
                    sleep(ms);
                    return ms;
                },
                n -> {
                    if (!Thread.currentThread().getName().equals(caller)) {
                        threads.incrementAndGet();
                    }
                    seen.add(n);
                });

        assertEquals(List.of(1, 2, 3, 4), seen, "a status line must count 1,2,3,4 and never 3,1,4,2");
        assertEquals(0, threads.get(), "progress on a worker thread would need locking the caller does not have");
    }

    @Test
    @DisplayName("nothing to do is not an error, and one item needs no pool")
    void emptyAndSingle() {
        assertEquals(List.of(), Parallel.map(List.of(), x -> x, null));
        assertEquals(List.of(), Parallel.map(null, x -> x, null));

        AtomicInteger progress = new AtomicInteger();
        assertEquals(List.of("only"), Parallel.map(List.of("only"), s -> s, progress::set));
        assertEquals(1, progress.get());
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
