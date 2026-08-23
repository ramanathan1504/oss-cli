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
package com.osscli.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.schedule.Platforms;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every platform is told to run, checked on whichever platform you happen to be on.
 *
 * <p>Three mechanisms — launchd, systemd and the Task Scheduler — and a machine can only ever
 * exercise one of them. That is the whole problem: two thirds of this code was, until now,
 * unreadable without running it, on a path where being wrong means a service that does not start
 * at some later boot rather than an error anybody sees.
 *
 * <p>So the definitions are generated as values and asserted here. What cannot be tested from one
 * machine is the *scheduling* — whether {@code systemctl --user enable} succeeds on a given distro,
 * whether {@code schtasks} accepts the argument quoting on a given Windows. Those need those
 * systems, and this file does not pretend to cover them.
 */
class AutostartAllPlatformsTest {

    private static final List<String> START = List.of("/opt/homebrew/bin/oss");
    private static final Path OUT = Path.of("/tmp/oss/serve.out.log");
    private static final Path ERR = Path.of("/tmp/oss/serve.err.log");

    private String realOs;

    @AfterEach
    void restoreOs() {
        // Every test that forces a platform has to put it back, or the ones after it are running
        // on an operating system that is not there.
        if (realOs != null) {
            System.setProperty("os.name", realOs);
            realOs = null;
        }
    }

    private void pretend(String osName) {
        realOs = System.getProperty("os.name");
        System.setProperty("os.name", osName);
    }

    // ---------------------------------------------------------------- macOS ---

    @Test
    @DisplayName("the launchd plist is well-formed XML, not merely a string that looks like it")
    void plistParses() throws Exception {
        String plist = Autostart.plistFor(START, 1504, OUT, ERR);

        // Parsed rather than grepped. launchd rejects a malformed plist quietly at boot, which is
        // the worst possible time to learn that a path had an ampersand in it.
        var factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var doc = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(plist.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("plist", doc.getDocumentElement().getNodeName());
    }

    @Test
    @DisplayName("a path with XML metacharacters still produces a plist launchd can parse")
    void hostilePathsStayWellFormed() throws Exception {
        // & < > are all legal in a filesystem path, and every value in this plist is one. Raw
        // interpolation made /Users/R&D produce a document that is not XML -- which launchd accepts
        // at install time and then declines to start at the next boot. plutil, Apple's own parser,
        // rejected exactly these.
        java.nio.file.Path out = java.nio.file.Path.of("/Users/R&D/logs/out & err.log");
        java.nio.file.Path err = java.nio.file.Path.of("/Users/R&D/logs/err.log");
        String plist = Autostart.plistFor(List.of("/Users/R&D/bin/oss"), 1504, out, err);

        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var doc = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(plist.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(doc.getDocumentElement() != null);
        assertTrue(plist.contains("&amp;"), "the ampersand was not escaped");
        assertFalse(plist.contains("/Users/R&D"), "a raw ampersand is still in the document");
    }

    @Test
    @DisplayName("the plist says what launchd needs to keep it alive")
    void plistCarriesItsPolicy() {
        String plist = Autostart.plistFor(START, 1504, OUT, ERR);

        assertTrue(plist.contains("<key>RunAtLoad</key><true/>"), "would not start at login");
        assertTrue(plist.contains("<key>KeepAlive</key><true/>"), "would not restart if it died");
        // Without a throttle, a service that fails immediately is restarted in a tight loop --
        // which is a laptop with a fan running and no error anywhere.
        assertTrue(plist.contains("ThrottleInterval"), "no restart throttle");
        assertTrue(plist.contains("--port</string><string>1504"), "the port did not reach the definition");
        assertTrue(plist.contains(OUT.toString()) && plist.contains(ERR.toString()), "logs go nowhere");
    }

    @Test
    @DisplayName("the recorded command is one that survives an upgrade")
    void noVersionedPathInTheDefinition() {
        String plist = Autostart.plistFor(List.of("/opt/homebrew/bin/oss"), 1504, OUT, ERR);

        // The bug this guards: an agent that recorded /opt/homebrew/Cellar/oss/<version>/… kept
        // working until the next `brew upgrade` deleted that exact directory, and then failed at a
        // moment with no connection to anything anyone did.
        assertFalse(plist.contains("/Cellar/"), "the definition names a path brew deletes on upgrade");
    }

    // ---------------------------------------------------------------- Linux ---

    @Test
    @DisplayName("the systemd unit has the three sections systemd requires")
    void unitIsComplete() {
        String unit = Autostart.unitFor(START, 1504, OUT, ERR);

        for (String section : List.of("[Unit]", "[Service]", "[Install]")) {
            assertTrue(unit.contains(section), "a unit without " + section + " will not enable");
        }
        // Quoted: systemd splits ExecStart on whitespace, so the executable is one argument or it
        // is several. systemd-analyze accepts this form and reads the whole path as the command.
        assertTrue(unit.contains("ExecStart=\"/opt/homebrew/bin/oss\" serve --no-open --port 1504"), unit);
        assertTrue(unit.contains("Restart=always"), "would not come back after a crash");
        assertTrue(unit.contains("RestartSec=60"), "would restart in a tight loop");
        // default.target rather than multi-user.target: this is a --user unit, and the wrong target
        // installs cleanly and never starts.
        assertTrue(unit.contains("WantedBy=default.target"), "wrong install target for a user unit");
    }

    @Test
    @DisplayName("the unit quotes its executable, as the Windows command already did")
    void unitQuotesTheExecutable() {
        // systemd splits ExecStart on whitespace. systemd-analyze, given the unquoted form of this
        // path, answered "Command /Users/a is not executable" -- a unit that installs cleanly and
        // never starts. The scheduled-task command has quoted its arguments since it was written;
        // this one did not, because no machine here could run systemd to find out.
        String unit = Autostart.unitFor(List.of("/opt/a b/oss"), 1504, OUT, ERR);

        assertTrue(unit.contains("ExecStart=\"/opt/a b/oss\""), unit);
    }

    @Test
    @DisplayName("a percent in a path is not read as a systemd specifier")
    void percentIsEscaped() {
        // %h is the user's home and %i the instance name, so an unescaped percent silently rewrites
        // the path into a different one -- which is worse than failing, because the service starts.
        String unit = Autostart.unitFor(List.of("/opt/100%/oss"), 1504, OUT, ERR);

        assertTrue(unit.contains("/opt/100%%/oss"), unit);
    }

    @Test
    @DisplayName("the unit's log lines append rather than truncate")
    void unitLogsAppend() {
        String unit = Autostart.unitFor(START, 1504, OUT, ERR);

        // StandardOutput=file: truncates on every start, so the log of the crash that caused the
        // restart is destroyed by the restart.
        assertTrue(unit.contains("StandardOutput=append:"), unit);
        assertTrue(unit.contains("StandardError=append:"), unit);
    }

    // -------------------------------------------------------------- Windows ---

    @Test
    @DisplayName("the scheduled task command quotes the executable")
    void taskCommandIsQuoted() {
        String cmd = Autostart.taskCommandFor(List.of("C:\\Program Files\\oss\\oss.exe"), 1504);

        // "Program Files" is the default install location and contains a space, so an unquoted
        // command is the normal case rather than the exotic one.
        assertTrue(cmd.startsWith("\"C:\\Program Files\\oss\\oss.exe\""), cmd);
        assertTrue(cmd.endsWith("serve --no-open --port 1504"), cmd);
    }

    @Test
    @DisplayName("a jvm-and-jar start command keeps both parts quoted")
    void taskCommandWithTwoParts() {
        String cmd = Autostart.taskCommandFor(List.of("C:\\jdk\\bin\\java.exe", "-jar", "C:\\oss\\oss.jar"), 1504);

        assertTrue(cmd.contains("\"C:\\jdk\\bin\\java.exe\" \"-jar\" \"C:\\oss\\oss.jar\""), cmd);
    }

    // ------------------------------------------------------ starting it now ---

    @Test
    @DisplayName("every platform has a way to start it now, not only at the next login")
    void startNowPerPlatform() {
        // Installing is not starting, and the gap is where saying yes stopped working. Windows is
        // the plainest case -- the task is registered `onlogon` and would not have run until one --
        // but all three needed it, because the first start races the terminal that is still holding
        // the port and loses.
        pretend("Mac OS X");
        List<String> mac = Autostart.startNowCommand();
        assertEquals("launchctl", mac.get(0));
        // kickstart -k, not `launchctl start`: only kickstart overrides the ThrottleInterval that
        // the failed first start has just begun, and waiting that out is the whole bug.
        assertTrue(mac.contains("kickstart") && mac.contains("-k"), mac.toString());
        assertTrue(mac.get(mac.size() - 1).endsWith("/com.osscli.serve"), mac.toString());
        restoreOs();

        pretend("Linux");
        assertEquals(List.of("systemctl", "--user", "restart", "oss-serve.service"), Autostart.startNowCommand());
        restoreOs();

        pretend("Windows 11");
        assertEquals(List.of("schtasks", "/run", "/tn", "oss serve"), Autostart.startNowCommand());
        restoreOs();

        pretend("Plan 9");
        // Nothing to run, and startNow() reports false rather than pretending a machine with no
        // mechanism was started.
        assertTrue(Autostart.startNowCommand().isEmpty());
        assertFalse(Autostart.startNow());
        assertFalse(Autostart.supported());
    }

    // ------------------------------------------------------- where it lives ---

    @Test
    @DisplayName("each platform keeps its definition where that platform looks for it")
    void descriptorPerPlatform() {
        pretend("Mac OS X");
        // Compared with separators normalised. This test pretends to be each platform in turn, but
        // it still builds real Paths on the host it runs on -- so on Windows the macOS descriptor
        // renders as Library\\LaunchAgents and a literal "Library/LaunchAgents" fails on a
        // difference the test is not about.
        assertTrue(
                Autostart.descriptor().toString().replace('\\', '/').contains("Library/LaunchAgents"),
                Autostart.descriptor().toString());
        assertTrue(Autostart.descriptor().toString().endsWith(".plist"));
        restoreOs();

        pretend("Linux");
        // Normalised for the same reason as the macOS branch above: the Path is built on the host,
        // not on the platform being pretended.
        assertTrue(
                Autostart.descriptor().toString().replace('\\', '/').contains(".config/systemd/user"),
                Autostart.descriptor().toString());
        assertTrue(Autostart.descriptor().toString().endsWith(".service"));
        restoreOs();

        pretend("Windows 11");
        // Windows keeps the definition inside the Task Scheduler, so a marker file stands in and
        // "is it installed" stays answerable by the same question on all three.
        assertTrue(Autostart.descriptor().toString().contains("serve-task-installed"));
    }

    @Test
    @DisplayName("an unrecognised platform is refused by name rather than half-installed")
    void unknownPlatformIsHonest() {
        pretend("Plan 9");

        // Guessing a mechanism on an unknown system is how something gets written to a path nobody
        // reads and reported as installed.
        assertEquals(Platforms.Platform.UNKNOWN, Platforms.Platform.detect());
    }

    @Test
    @DisplayName("the platforms that are recognised are recognised by every name they use")
    void platformDetection() {
        for (String mac : List.of("Mac OS X", "Darwin", "mac os x")) {
            pretend(mac);
            assertEquals(Platforms.Platform.MAC, Platforms.Platform.detect(), mac);
            restoreOs();
        }
        for (String linux : List.of("Linux", "FreeBSD", "AIX", "SunOS unix")) {
            pretend(linux);
            assertEquals(Platforms.Platform.LINUX, Platforms.Platform.detect(), linux);
            restoreOs();
        }
        for (String win : List.of("Windows 10", "Windows Server 2022")) {
            pretend(win);
            assertEquals(Platforms.Platform.WINDOWS, Platforms.Platform.detect(), win);
            restoreOs();
        }
    }
}
