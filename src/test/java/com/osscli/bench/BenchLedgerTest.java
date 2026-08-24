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
package com.osscli.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner's rung, and the one thing that makes it worth anything: knowing which code it ran.
 *
 * <p>A green result from a tree that is not the change under review reads exactly like a good one,
 * so most of what is checked here is that the two cases stay apart.
 */
class BenchLedgerTest {

    private static BenchLedger.Row row(String repo, int pr, String verb, int exit, String prHead, String ranOn) {
        BenchLedger.Row r = new BenchLedger.Row();
        r.repo = repo;
        r.pr = pr;
        r.verb = verb;
        r.exit = exit;
        r.prHead = prHead;
        r.ranOn = ranOn;
        r.ranAt = "2026-08-24T09:00:00Z";
        r.runner = "built-in";
        return r;
    }

    @Test
    @DisplayName("a run is only about this change when it ran on this change")
    void trustIsAboutTheCommit() {
        String head = "21c00ae5f0d1c2b3a4958677889900aabbccddee";

        assertEquals(
                BenchLedger.Trust.SAME_CODE,
                row("o/n", 1, "test", 0, head, head).trust());
        assertEquals(
                BenchLedger.Trust.OTHER_CODE,
                row("o/n", 1, "test", 0, head, "ffffffffffffffffffffffffffffffffffffffff")
                        .trust());
        // Nothing to compare: no local git, or the pull request could not be read.
        assertEquals(
                BenchLedger.Trust.UNKNOWN, row("o/n", 1, "test", 0, head, "").trust());
        assertEquals(
                BenchLedger.Trust.UNKNOWN, row("o/n", 1, "test", 0, "", head).trust());
    }

    @Test
    @DisplayName("a short sha and a full one are the same commit")
    void shortAndLongShasCompare() {
        // GitHub reports forty characters; `git rev-parse --short` gives seven. Refusing to compare
        // them would report OTHER_CODE for a run that was in fact against the right code -- the
        // failure that turns a working rung into one nobody trusts.
        String full = "21c00ae5f0d1c2b3a4958677889900aabbccddee";
        assertEquals(
                BenchLedger.Trust.SAME_CODE,
                row("o/n", 1, "test", 0, full, "21c00ae").trust());
        assertEquals(
                BenchLedger.Trust.SAME_CODE,
                row("o/n", 1, "test", 0, full, "21C00AE").trust());
        // Six is not enough to be sure, so it says so rather than guessing.
        assertEquals(
                BenchLedger.Trust.UNKNOWN,
                row("o/n", 1, "test", 0, full, "21c00a").trust());
    }

    @Test
    @DisplayName("the summary never lets a result from the wrong tree read like a good one")
    void summarySaysWhichCode() {
        String head = "21c00ae5f0d1c2b3a4958677889900aabbccddee";

        assertEquals("test passed", row("o/n", 1, "test", 0, head, head).summary());
        assertEquals(
                "test failed (exit 1)", row("o/n", 1, "test", 1, head, head).summary());
        assertTrue(
                row("o/n", 1, "test", 0, head, "beefbeefbeefbeefbeefbeefbeefbeefbeefbeef")
                        .summary()
                        .contains("not this change"),
                "a pass from another commit must say so");
        assertTrue(
                row("o/n", 1, "test", 0, head, "").summary().contains("not recorded"),
                "an unknown commit must say so rather than imply this one");
    }

    @Test
    @DisplayName("the headline is the result that changes what you do next")
    void headlinePrefersFailuresAndTheRightCode() {
        String head = "21c00ae5f0d1c2b3a4958677889900aabbccddee";
        String other = "ffffffffffffffffffffffffffffffffffffffff";

        // A failure outranks a pass on equal footing: "test passed" beside "build failed" is the
        // half of the truth that flatters the change.
        List<BenchLedger.Row> both =
                List.of(row("o/n", 4229, "test", 0, head, head), row("o/n", 4229, "build", 2, head, head));
        assertEquals("build", BenchLedger.headline(both, "o/n", 4229).verb);

        // And a pass from the wrong tree must never displace a real result.
        List<BenchLedger.Row> mixed =
                List.of(row("o/n", 4229, "build", 0, head, other), row("o/n", 4229, "test", 0, head, head));
        assertEquals("test", BenchLedger.headline(mixed, "o/n", 4229).verb);

        assertNull(BenchLedger.headline(both, "o/n", 9999), "a pull request never run has no headline");
    }

    @Test
    @DisplayName("owner/Name and owner/name are the same project")
    void repositoryMatchIsCaseInsensitive() {
        // A ledger that disagreed with GitHub about this would silently report "never run".
        List<BenchLedger.Row> rows = List.of(row("Apache/Logging-Log4j2", 4229, "test", 0, "abc1234", "abc1234"));
        assertEquals(1, BenchLedger.forPr(rows, "apache/logging-log4j2", 4229).size());
        assertEquals(
                1, BenchLedger.forPr(rows, "  apache/logging-log4j2  ", 4229).size());
    }

    @Test
    @DisplayName("a row survives being written and read back")
    void roundTrips(@TempDir Path dir) {
        Path file = dir.resolve("bench-runs.tsv");
        BenchLedger.Row r = row("apache/logging-log4j2", 4229, "test", 0, "21c00ae", "21c00ae");
        r.runner = "kafka-bench";

        BenchLedger.writeTo(file, List.of(r));
        List<BenchLedger.Row> back = BenchLedger.readFrom(file);

        assertEquals(1, back.size());
        assertEquals("apache/logging-log4j2", back.get(0).repo);
        assertEquals(4229, back.get(0).pr);
        assertEquals("test", back.get(0).verb);
        assertEquals("kafka-bench", back.get(0).runner);
        assertTrue(back.get(0).passed());
        assertEquals(BenchLedger.Trust.SAME_CODE, back.get(0).trust());
    }

    @Test
    @DisplayName("a malformed row is skipped, not fatal — the rest of the ledger still answers")
    void malformedRowsAreSkipped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bench-runs.tsv");
        Files.writeString(
                file,
                "# repo\tpr\tverb\texit\tpr_head\tran_at\tran_on\trunner\n"
                        + "o/n\tnot-a-number\ttest\t0\tabc1234\t2026-08-24T09:00:00Z\tabc1234\tbuilt-in\n"
                        + "too\tfew\tfields\n"
                        + "\n"
                        + "o/n\t4229\ttest\t0\tabc1234\t2026-08-24T09:00:00Z\tabc1234\tbuilt-in\n");

        List<BenchLedger.Row> rows = BenchLedger.readFrom(file);
        assertEquals(1, rows.size(), "the good row must survive its neighbours");
        assertEquals(4229, rows.get(0).pr);
    }

    @Test
    @DisplayName("reading a ledger that was never written is empty, not an error")
    void missingFileIsEmpty(@TempDir Path dir) {
        assertTrue(BenchLedger.readFrom(dir.resolve("nothing.tsv")).isEmpty());
    }

    @Test
    @DisplayName("a tab in a field cannot forge a column")
    void fieldsCannotBreakTheFormat(@TempDir Path dir) {
        // The runner name is the only free-text field, and it comes from a manifest somebody else
        // wrote. A tab in it would shift every column after it by one.
        Path file = dir.resolve("bench-runs.tsv");
        BenchLedger.Row r = row("o/n", 7, "test", 0, "abc1234", "abc1234");
        r.runner = "bad\tname";

        BenchLedger.writeTo(file, List.of(r));
        List<BenchLedger.Row> back = BenchLedger.readFrom(file);

        assertEquals(1, back.size());
        assertEquals(7, back.get(0).pr);
        assertFalse(back.get(0).runner.contains("\t"), "a tab reached the file: " + back.get(0).runner);
        // And the name survives whole. Splitting on the tab would have returned "bad" -- no column
        // shifted, nothing failed, and half the name quietly gone.
        assertEquals("bad name", back.get(0).runner);
    }
}
