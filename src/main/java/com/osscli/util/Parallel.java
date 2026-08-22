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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Fetch many things at once, and hand them back in the order they were asked for.
 *
 * <p>{@code followup} and {@code hub} each walk a ledger making three API calls per row -- the pull
 * request, its comments, its reviews. Seventeen rows is fifty-one requests one after another, which
 * measured at 38 and 40 seconds. {@code FollowupCommand} already said so in a comment: <em>"an API
 * call per row ... a ledger of seventeen is three quarters of a minute"</em>. The progress line
 * exists because of it.
 *
 * <p><b>Order is the whole point.</b> Results come back in the order the items went in, never the
 * order they happened to finish. A report whose rows shuffle between runs cannot be diffed, cannot
 * be read twice, and turns "what changed since yesterday" into a reading exercise. This is why the
 * work is split from the printing rather than each thread printing as it lands.
 *
 * <p><b>Bounded, and small.</b> Six at a time against one host: enough to turn fifty-one serial
 * round trips into nine, few enough that it is not mistaken for an attack by anything counting
 * requests. Threads are daemons so a Ctrl-C at the prompt ends the process rather than waiting on a
 * socket read.
 */
public final class Parallel {

    /** Requests in flight at once. Against a single API host, more is rude before it is faster. */
    public static final int LANES = 6;

    private Parallel() {}

    /**
     * Map every item, concurrently, and return the results in input order.
     *
     * <p>{@code progress} is called on the calling thread as each result is collected -- never from
     * a worker -- so a status line or a counter needs no locking of its own. It sees results in
     * order too, which means it counts completions rather than reporting whichever item finished
     * first as though it were the first.
     *
     * <p>An item whose function throws does not stop the others: its slot holds {@code null} and
     * the caller decides. A single unreachable pull request must not cost the other sixteen.
     */
    public static <T, R> List<R> map(List<T> items, Function<T, R> fn, IntConsumer progress) {
        List<R> out = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        // One item is not worth a thread pool, and this is the common case for `--pr 4249`.
        if (items.size() == 1) {
            out.add(apply(fn, items.get(0)));
            if (progress != null) {
                progress.accept(1);
            }
            return out;
        }

        int lanes = Math.min(LANES, items.size());
        ExecutorService pool = Executors.newFixedThreadPool(lanes, runnable -> {
            Thread t = new Thread(runnable, "oss-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(pool.submit(() -> apply(fn, item)));
            }
            int done = 0;
            for (Future<R> f : futures) {
                // Collected in submission order. Waiting on the first future while the sixth is
                // already finished costs nothing -- the work is done either way, and the ordering
                // is what the reader gets.
                try {
                    out.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(null);
                } catch (Exception e) {
                    out.add(null);
                }
                if (progress != null) {
                    progress.accept(++done);
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T, R> R apply(Function<T, R> fn, T item) {
        try {
            return fn.apply(item);
        } catch (RuntimeException e) {
            // One row's failure is that row's problem. The caller sees null and says so on the line
            // for that item, where a reader can act on it.
            return null;
        }
    }
}
