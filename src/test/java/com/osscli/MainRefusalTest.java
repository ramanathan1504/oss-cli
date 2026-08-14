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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.storage.SchemaTooNewException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the user is left with when the store cannot be opened.
 *
 * <p>Refusing is only half of the fix. The other half is that the refusal has to be usable: it has
 * to say which build is complaining, that nothing was touched, and what to do — and it must not take
 * away {@code doctor}, which is the command somebody reaches for the moment their tool starts saying
 * no.
 */
class MainRefusalTest {

    @Test
    @DisplayName("doctor and the version and help flags stay available")
    void diagnosticsStayReachable() {
        assertTrue(Main.diagnostic(new String[] {"doctor"}));
        assertTrue(Main.diagnostic(new String[] {"--version"}));
        assertTrue(Main.diagnostic(new String[] {"-V"}));
        assertTrue(Main.diagnostic(new String[] {"--help"}));
        assertTrue(Main.diagnostic(new String[] {"-h"}));
    }

    @Test
    @DisplayName("anything that reads or writes the store does not")
    void everythingElseIsRefused() {
        assertFalse(Main.diagnostic(new String[] {"sync", "--all"}), "sync writes");
        assertFalse(Main.diagnostic(new String[] {"chat", "4129"}), "chat writes");
        assertFalse(Main.diagnostic(new String[] {"history"}), "history reads tables it may not understand");
        assertFalse(Main.diagnostic(new String[] {"search", "anything"}), "search reads");
        assertFalse(Main.diagnostic(new String[] {}), "a bare invocation is not a diagnostic");
    }

    @Test
    @DisplayName("only the first argument counts, so a flag buried later cannot unlock the store")
    void onlyTheVerbCounts() {
        assertFalse(
                Main.diagnostic(new String[] {"sync", "--all", "--help"}),
                "a --help further along the line is still a sync invocation as far as this check goes");
        assertFalse(Main.diagnostic(new String[] {"chat", "doctor"}));
    }

    @Test
    @DisplayName("the message says what happened, that nothing changed, and what to do about it")
    void refusalMessageIsUseful() {
        String out = capture(() -> Main.refuse(new SchemaTooNewException(99, 14)));

        assertTrue(out.contains("99"), "the store's version is missing:\n" + out);
        assertTrue(out.contains("14"), "this build's version is missing:\n" + out);
        assertTrue(out.contains("newer oss"), "it does not say what is wrong:\n" + out);
        assertTrue(out.contains("Nothing has been read or changed"), "the reassurance is missing:\n" + out);
        assertTrue(out.contains("brew upgrade oss"), "the fix is missing:\n" + out);
        assertTrue(out.contains("OSS_CLI_HOME"), "the escape hatch is missing:\n" + out);
        assertTrue(out.contains("oss doctor"), "it does not point at the command that still works:\n" + out);
    }

    @Test
    @DisplayName("it goes to stderr, because stdout is where results go")
    void refusalGoesToStderr() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            capture(() -> Main.refuse(new SchemaTooNewException(15, 14)));
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(stdout.toString(StandardCharsets.UTF_8).isEmpty(), "the refusal must not land on stdout");
    }

    @Test
    @DisplayName("reading the build version cannot itself break the message")
    void messageSurvivesAnUnreadableVersion() {
        // version.properties is filtered in at package time and is absent from a bare test
        // classpath in some setups. The refusal must still be printed and still be complete.
        String out = capture(() -> Main.refuse(new SchemaTooNewException(20, 14)));
        assertTrue(out.contains("schema 20"), out);
        assertTrue(out.contains("schema 14"), out);
    }

    private static String capture(Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
