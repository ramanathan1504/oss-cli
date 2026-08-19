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
package com.osscli.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The layer that produces facts instead of opinions, and the ways it could produce a false one.
 *
 * <p>A verification says "this test fails when the change is reverted, so it is testing the change".
 * That sentence is only worth having if it cannot be said wrongly, and there are three ways it can
 * be: reading one command's exit code as a verdict on several test classes, treating a class that
 * never ran as one that passed, and reverting to a branch tip that has moved on so the second run
 * fails for somebody else's reasons.
 */
class VerifierTest {

    /** Surefire's real shape, with the module prefix and the class name it actually prints. */
    private static final String THREE_CLASSES_ONE_FAILING = """
            [INFO] Running org.apache.logging.log4j.core.util.ThrowablesTest
            [ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.12 s <<< FAILURE! -- in org.apache.logging.log4j.core.util.ThrowablesTest
            [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.28 s -- in org.apache.logging.log4j.core.appender.nosql.NoSqlDatabaseManagerTest
            [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.05 s -- in org.apache.logging.log4j.core.appender.db.jpa.converter.ThrowableAttributeConverterTest
            [INFO] BUILD FAILURE
            """;

    @Test
    @DisplayName("each class is judged on its own line, not on the command's exit code")
    void verdictsArePerClass() {
        // The bug this replaced: one Maven invocation covering three classes, one verdict taken
        // from its exit code, and all three reported as proven because one of them failed.
        assertEquals(Verifier.Verdict.PROVEN, Verifier.verdictFor("ThrowablesTest", THREE_CLASSES_ONE_FAILING));
        assertEquals(
                Verifier.Verdict.PROVES_NOTHING,
                Verifier.verdictFor("NoSqlDatabaseManagerTest", THREE_CLASSES_ONE_FAILING));
        assertEquals(
                Verifier.Verdict.PROVES_NOTHING,
                Verifier.verdictFor("ThrowableAttributeConverterTest", THREE_CLASSES_ONE_FAILING));
    }

    @Test
    @DisplayName("an error counts as failing, since a StackOverflow is not a pass")
    void errorsCountAsFailures() {
        String output =
                "[ERROR] Tests run: 5, Failures: 0, Errors: 1, Skipped: 0 -- in x.y.ThrowableAttributeConverterTest";

        // Reverting a fix for infinite recursion produces an Error, not a Failure. Counting only
        // failures would report the most dramatic possible proof as no proof at all.
        assertEquals(Verifier.Verdict.PROVEN, Verifier.verdictFor("ThrowableAttributeConverterTest", output));
    }

    @Test
    @DisplayName("a class that never ran is not a class that passed")
    void silenceIsNotEvidence() {
        // A module missing from the reactor prints nothing for its tests. Reading that as "passed
        // with the change reverted" would report the flattering answer for something that did not
        // happen -- and reading it as "proven" would be worse.
        assertEquals(Verifier.Verdict.NOT_RUN, Verifier.verdictFor("SomeOtherTest", THREE_CLASSES_ONE_FAILING));
        assertEquals(Verifier.Verdict.NOT_RUN, Verifier.verdictFor("ThrowablesTest", ""));
    }

    @Test
    @DisplayName("a class whose name is a suffix of another is not mistaken for it")
    void namesAreMatchedWhole() {
        String output = "[ERROR] Tests run: 3, Failures: 1, Errors: 0 -- in x.y.ExtendedThrowablesTest";

        // "ThrowablesTest" appears inside "ExtendedThrowablesTest". Matching loosely would report a
        // verdict for a class that was never mentioned.
        assertEquals(Verifier.Verdict.NOT_RUN, Verifier.verdictFor("ThrowablesTest", output));
        assertEquals(Verifier.Verdict.PROVEN, Verifier.verdictFor("ExtendedThrowablesTest", output));
    }

    @Test
    @DisplayName("what to build and what to run come from the changed paths")
    void modulesAndTestsAreDerived() {
        List<String> changed = List.of(
                "log4j-core/src/main/java/org/apache/logging/log4j/core/util/Throwables.java",
                "log4j-core-test/src/test/java/org/apache/logging/log4j/core/util/ThrowablesTest.java",
                "log4j-jpa/src/test/java/org/apache/logging/log4j/core/appender/db/jpa/converter/ThrowableAttributeConverterTest.java",
                "src/changelog/.2.x.x/4249_fix-circular-exception.xml",
                "README.md");

        assertEquals(List.of("log4j-core", "log4j-core-test", "log4j-jpa"), Verifier.modulesOf(changed));
        assertEquals(List.of("ThrowablesTest", "ThrowableAttributeConverterTest"), Verifier.testClassesOf(changed));
        // A changelog entry and a README are not production source, so reverting them proves nothing
        // and rebuilding for them costs minutes.
        assertEquals(1, changed.stream().filter(Verifier::isMainSource).count());
    }

    @Test
    @DisplayName("a change with no test is refused, because there is nothing to prove")
    void noTestNothingToProve(@TempDir Path dir) {
        Verifier.Report report =
                Verifier.verify(dir, "abc123", null, "2.x", List.of("log4j-core/src/main/java/x/Y.java"), s -> {});

        assertFalse(report.ran());
        assertTrue(report.why().contains("no test"), report.why());
    }

    @Test
    @DisplayName("a docs-only change is refused, because there is nothing to revert")
    void noProductionSourceNothingToRevert(@TempDir Path dir) {
        Verifier.Report report = Verifier.verify(dir, "abc123", null, "2.x", List.of("README.md"), s -> {});

        assertFalse(report.ran());
        assertTrue(report.why().contains("no production source"), report.why());
    }

    @Test
    @DisplayName("without a clone it says so, rather than half-running")
    void noCloneIsReported(@TempDir Path notARepo) {
        Verifier.Report report = Verifier.verify(
                notARepo,
                "abc123",
                null,
                "2.x",
                List.of("m/src/main/java/A.java", "m/src/test/java/ATest.java"),
                s -> {});

        assertFalse(report.ran());
        assertTrue(report.why().contains("--clone"), report.why());
    }

    @Test
    @DisplayName("the line kept from a failed build is the one that says what failed")
    void theUsefulLineIsKept() {
        String output = """
                [INFO] Building log4j-core
                [ERROR] /x/Y.java:[12,5] cannot find symbol
                [ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] For more information about the errors and possible solutions, please read the following articles:
                [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                """;

        // Maven's last line is true of every failure and says nothing about this one.
        String kept = Verifier.lastMeaningfulLine(output);
        assertTrue(kept.contains("Tests run: 8, Failures: 1"), kept);
        assertFalse(kept.contains("Help 1"), kept);
    }

    @Test
    @DisplayName("the report separates what was proven from what proves nothing")
    void theReportAnswersTheQuestionAsked() {
        Verifier.Report report = new Verifier.Report(
                true,
                null,
                List.of(),
                List.of(
                        new Verifier.TestResult("GoodTest", Verifier.Verdict.PROVEN, ""),
                        new Verifier.TestResult("EmptyTest", Verifier.Verdict.PROVES_NOTHING, "")),
                List.of("m"),
                null);

        assertTrue(report.anythingProven());
        assertEquals(1, report.provesNothing().size());
        assertEquals("EmptyTest", report.provesNothing().get(0).testClass());
    }
}
