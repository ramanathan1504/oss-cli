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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The daily job's definition, read without installing it.
 *
 * <p>Two of the three platforms cannot be exercised on whatever machine runs this, and the one that
 * can must not have a launch agent installed by a test run. So the definitions are values, and these
 * are assertions about the values — which is the only way the Linux and Windows shapes get checked
 * at all.
 *
 * <p>Every case here is one that has already gone wrong once for the sibling always-on service: an
 * unescaped ampersand, an unquoted space, a percent sign systemd rewrites. They are repeated because
 * a second job is exactly where a fixed bug comes back.
 */
class DailyJobTest {

    private static final List<String> START = List.of("/opt/homebrew/bin/oss");
    private static final Path OUT = Path.of("/Users/x/.oss-cli/logs/harvest.out.log");
    private static final Path ERR = Path.of("/Users/x/.oss-cli/logs/harvest.err.log");

    private String realOs;

    private void pretend(String os) {
        if (realOs == null) {
            realOs = System.getProperty("os.name");
        }
        System.setProperty("os.name", os);
    }

    @AfterEach
    void restoreOs() {
        if (realOs != null) {
            System.setProperty("os.name", realOs);
            realOs = null;
        }
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("the agent fires on the clock, not on an interval, and does not restart itself")
    void plistSchedulesRatherThanLoops() throws Exception {
        String plist = DailyJob.plistFor(START, 9, 15, OUT, ERR);

        assertEquals("plist", parse(plist).getDocumentElement().getNodeName());
        assertTrue(plist.contains("<key>StartCalendarInterval</key>"), "a daily job needs a calendar entry");
        assertTrue(plist.contains("<key>Hour</key><integer>9</integer>"));
        assertTrue(plist.contains("<key>Minute</key><integer>15</integer>"));

        // StartInterval drifts by however long the machine slept, so within a month it fires at an
        // hour nobody chose.
        assertFalse(plist.contains("StartInterval"), "an interval is not a schedule");

        // KeepAlive on a job that exits is a harvest that restarts the instant it finishes -- a
        // loop against the GitHub API for as long as the machine is on.
        assertFalse(plist.contains("<key>KeepAlive</key><true/>"), "this job exits; keeping it alive is a loop");
        assertTrue(plist.contains("<key>RunAtLoad</key><false/>"), "installing it must not run it");
    }

    @Test
    @DisplayName("it runs the harvest, by the name that survives an upgrade")
    void plistRunsTheRightThing() {
        String plist = DailyJob.plistFor(START, 9, 15, OUT, ERR);

        assertTrue(plist.contains("<string>memory</string><string>harvest</string>"));
        assertTrue(plist.contains("/opt/homebrew/bin/oss"), "the launcher on PATH is the stable handle");
        assertFalse(plist.contains("Cellar"), "a versioned path is deleted by the next brew upgrade");
    }

    @Test
    @DisplayName("a path with XML metacharacters still produces a plist launchd can parse")
    void hostilePathsStayWellFormed() throws Exception {
        // & < > are all legal in a filesystem path, and every value here is one. Raw interpolation
        // made /Users/R&D produce a document that is not XML -- which launchd accepts at install
        // time and then declines to start at the next boot.
        String plist = DailyJob.plistFor(
                List.of("/Users/R&D/bin/oss"),
                7,
                0,
                Path.of("/Users/R&D/logs/out & err.log"),
                Path.of("/Users/R&D/logs/err.log"));

        assertNotNull(parse(plist).getDocumentElement());
        assertTrue(plist.contains("&amp;"), "the ampersand was not escaped");
        assertFalse(plist.contains("/Users/R&D/bin"), "a raw ampersand is still in the document");
    }

    @Test
    @DisplayName("the systemd side is a timer plus a one-shot, and catches up after a machine was off")
    void timerCatchesUp() {
        String timer = DailyJob.timerFor(9, 15);
        String service = DailyJob.serviceFor(START, OUT, ERR);

        assertTrue(timer.contains("OnCalendar=*-*-* 09:15:00"), "the hour must be zero-padded for systemd");
        // Without Persistent, a machine that was off at 09:15 skips that day and nothing says so.
        assertTrue(timer.contains("Persistent=true"));
        assertTrue(timer.contains("WantedBy=timers.target"));

        // oneshot, not a daemon: Restart=always here would be the same loop KeepAlive would be.
        assertTrue(service.contains("Type=oneshot"));
        assertFalse(service.contains("Restart=always"));
        assertTrue(service.contains("memory harvest"));
    }

    @Test
    @DisplayName("a space in the install path does not become two arguments")
    void unitQuotesItsCommand() {
        String service = DailyJob.serviceFor(List.of("/Users/a b/bin/oss"), OUT, ERR);

        // systemd splits ExecStart on whitespace; unquoted, this reads as the command /Users/a
        // with an argument b/bin/oss, and the unit installs cleanly and never starts.
        assertTrue(service.contains("ExecStart=\"/Users/a b/bin/oss\" memory harvest"), service);
    }

    @Test
    @DisplayName("a percent sign in a log path is not read as a systemd specifier")
    void unitEscapesPercent() {
        String service = DailyJob.serviceFor(START, Path.of("/logs/100%/out.log"), ERR);

        // %h is the user's home to systemd, so an unescaped path is silently rewritten elsewhere.
        assertTrue(service.contains("/logs/100%%/out.log"), service);
    }

    @Test
    @DisplayName("the scheduled task names a time Windows accepts in any locale")
    void taskTimeIsPadded() {
        assertEquals("09:15", DailyJob.taskTime(9, 15));
        assertEquals("07:00", DailyJob.taskTime(7, 0));
        assertEquals("23:59", DailyJob.taskTime(23, 59));

        String cmd = DailyJob.taskCommandFor(List.of("C:\\Program Files\\oss\\oss.exe"));
        assertTrue(cmd.startsWith("\""), "an unquoted Program Files path is two arguments");
        assertTrue(cmd.endsWith(" memory harvest"), cmd);
    }

    @Test
    @DisplayName("each platform keeps its definition where that platform looks")
    void descriptorPerPlatform() {
        pretend("Mac OS X");
        assertTrue(DailyJob.descriptor().toString().endsWith("com.osscli.harvest.plist"));

        pretend("Linux");
        // The timer, not the service: a service with no timer never fires, so the timer is what
        // "installed" means.
        assertTrue(DailyJob.descriptor().toString().endsWith("oss-harvest.timer"));

        pretend("Windows 11");
        assertTrue(DailyJob.descriptor().toString().contains("harvest-task-installed"));

        pretend("Plan 9");
        assertTrue(DailyJob.descriptor().toString().contains("unsupported"));
    }

    @Test
    @DisplayName("an unrecognised platform is refused by name rather than half-installed")
    void unknownPlatformIsHonest() throws Exception {
        pretend("Plan 9");

        assertEquals(Platforms.Platform.UNKNOWN, Platforms.Platform.detect());
        // Null means "not installed", which the caller turns into a refusal. Writing a definition
        // into a directory nothing reads and reporting success is the failure this avoids.
        assertEquals(null, DailyJob.install(Path.of("/x/java"), Path.of("/x/oss.jar"), 9, 15));
        assertTrue(DailyJob.unsupportedAdvice().contains("oss memory harvest"));
    }

    @Test
    @DisplayName("the daily job and the always-on service never collide")
    void twoJobsTwoNames() {
        pretend("Mac OS X");
        String harvest = DailyJob.descriptor().toString();

        // Same label would mean installing one boots the other out -- and the symptom is the board
        // dying every morning at 09:15, which nobody would connect to a harvest.
        assertFalse(harvest.contains("com.osscli.serve"), "the two jobs share a label");
        assertTrue(harvest.contains("com.osscli.harvest"));
    }
}
