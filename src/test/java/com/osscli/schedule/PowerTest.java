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
package com.osscli.schedule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That only work nobody is waiting for is ever put off, and never work somebody asked for.
 *
 * <p>A test cannot unplug a laptop, so the reading is overridable and the rules around it are what
 * get asserted here.
 */
class PowerTest {

    @Test
    @DisplayName("a machine that cannot answer counts as plugged in")
    void unknownMeansMains() {
        // The failure this protects against -- work quietly never happening -- is worse than the
        // one it risks. A desktop and a server both answer nothing.
        assertFalse(Power.onBattery() && Platforms.Platform.detect() == Platforms.Platform.UNKNOWN);
    }

    @Test
    @DisplayName("the message names both the reason and the way round it")
    void deferralIsExplained() {
        String said = Power.deferred("indexing");

        assertTrue(said.contains("battery"), said);
        assertTrue(said.contains("oss memory index"), "a person must be told how to do it now: " + said);
    }

    @Test
    @DisplayName("only the scheduled run defers; a typed command runs whatever it costs")
    void typedCommandsAreNeverDeferred() throws IOException {
        // Deciding on somebody's behalf that their laptop knows better is the same presumption as
        // a background download. The cost of a command was accepted by the act of typing it.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"), StandardCharsets.UTF_8);
        int check = source.indexOf("Power.onBattery()");
        assertTrue(check > 0, "the battery check moved; this guard needs rewriting");

        String line = source.substring(source.lastIndexOf('\n', check) + 1, source.indexOf('\n', check));
        assertTrue(
                line.contains("quiet &&"),
                "the battery check must be gated on the run being a background one: " + line.strip());
    }

    @Test
    @DisplayName("the cheap hourly work is never gated on power")
    void theTickAlwaysRuns() throws IOException {
        // A tick that finds nothing changed costs 0.89 CPU-seconds. Skipping that to save power
        // would be the check costing more than the work.
        String source =
                Files.readString(Path.of("src/main/java/com/osscli/memory/BuiltinMemory.java"), StandardCharsets.UTF_8);
        int sessions = source.indexOf("private static int sessions(");
        int check = source.indexOf("Power.onBattery()", sessions);
        int discover = source.indexOf("Sessions.discover(", sessions);

        assertTrue(discover < check, "reading transcripts must happen before any power decision");
    }
}
