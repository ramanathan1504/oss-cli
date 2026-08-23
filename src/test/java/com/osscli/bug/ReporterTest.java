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
package com.osscli.bug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which failures are worth asking about, and what survives being asked about. */
class ReporterTest {

    @Test
    @DisplayName("a pulled cable is not a defect and must not be offered as one")
    void networkIsNotABug() {
        // Asking to file these teaches people to answer no to the question -- after which the one
        // that mattered gets a no as well.
        assertTrue(Reporter.theWorldIsNotCooperating(new java.net.UnknownHostException("api.github.com")));
        assertTrue(Reporter.theWorldIsNotCooperating(new java.net.ConnectException()));
        assertTrue(Reporter.theWorldIsNotCooperating(new java.net.SocketTimeoutException()));
        assertTrue(Reporter.theWorldIsNotCooperating(new javax.net.ssl.SSLHandshakeException("bad cert")));
        // A rejected key is this program working correctly against a key that is not.
        assertTrue(Reporter.theWorldIsNotCooperating(new com.osscli.llm.ApiFailure.Permanent(401, "invalid key")));
    }

    @Test
    @DisplayName("it is still not a defect three wrappers deep, which is how it always arrives")
    void looksThroughTheCauseChain() {
        Exception wrapped = new IllegalStateException("hub failed", new IOException(new java.net.ConnectException()));

        assertTrue(Reporter.theWorldIsNotCooperating(wrapped));
    }

    @Test
    @DisplayName("a fault in this program is a defect")
    void realFaultsAreOffered() {
        assertFalse(Reporter.theWorldIsNotCooperating(new NullPointerException()));
        assertFalse(Reporter.theWorldIsNotCooperating(new IllegalStateException("no rung")));
        assertFalse(Reporter.theWorldIsNotCooperating(new IOException("the database is corrupt")));
    }

    @Test
    @DisplayName("a cause chain that points at itself terminates")
    void selfReferentialCause() {
        // Not hypothetical: a wrapper that initialises its own cause to itself is a loop, and this
        // runs inside the handler for a crash, where hanging is the second failure.
        Exception loop = new IllegalStateException("x") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(Reporter.theWorldIsNotCooperating(loop));
    }

    @Test
    @DisplayName("declining does not throw the report away")
    void theCrashIsRememberedEitherWay() throws Exception {
        // The whole reason the question is safe to ask: answering no costs nothing, because
        // `oss bug --last` still has it. Without that, no means the report is gone.
        Path base = com.osscli.AppPaths.BASE_DIR;
        // The rule this repository learned the hard way: assert where the store is pointing rather
        // than trusting that it was redirected. A test once "redirected" itself and deleted 496 MB.
        assertFalse(
                base.equals(Path.of(System.getProperty("user.home"), ".oss-cli")),
                "this test writes, and it is pointed at the real store: " + base);

        Crash crash = Crash.of("oss hub", new IllegalStateException("no ledger"));
        crash.remember();
        try {
            Crash back = Crash.last().orElseThrow();

            assertEquals("oss hub", back.command());
            assertEquals("java.lang.IllegalStateException", back.type());
            assertEquals("no ledger", back.message());
            assertEquals(crash.signature(), back.signature());
            assertTrue(back.stack().contains("com.osscli.bug.ReporterTest"), back.stack());
        } finally {
            Files.deleteIfExists(Crash.file());
        }
    }

    @Test
    @DisplayName("nothing remembered is an empty answer, not a crash inside the crash handler")
    void nothingRemembered() throws Exception {
        Files.deleteIfExists(Crash.file());

        assertTrue(Crash.last().isEmpty());
    }

    @Test
    @DisplayName("the report names the command that was typed, not the root")
    void namesTheSubcommand() {
        picocli.CommandLine cli = com.osscli.Main.commandLine();

        assertEquals("oss bug", Reporter.commandLine(cli.parseArgs("bug")));
        assertEquals("oss hub", Reporter.commandLine(cli.parseArgs("hub")));
    }

    @Test
    @DisplayName("a fault with no oss frame in it still has a signature")
    void signatureWithoutAnOssFrame() {
        Crash crash = new Crash(
                "oss sync", "java.lang.OutOfMemoryError", "heap", "\tat java.base/Foo.bar(Foo.java:1)", "oss", "p");

        assertEquals("unknown", crash.topFrame());
        assertEquals("oss:java.lang.OutOfMemoryError:unknown", crash.signature());
    }
}
