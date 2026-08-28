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

import com.osscli.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The hourly job that files CLI transcripts under what they were about.
 *
 * <p>Separate from {@link DailyJob} for one reason that is not organisational: the daily job talks
 * to GitHub and this one does not. A harvest needs a network, a token and somebody's patience for a
 * few hundred API calls; this reads files that are already on the disk. Running them on the same
 * schedule would mean either harvesting twenty-four times a day against a rate limit, or noticing
 * this morning's work tomorrow.
 *
 * <h2>Why an interval and not a calendar</h2>
 *
 * <p>{@link DailyJob} argues at length for {@code StartCalendarInterval}, and is right: a job asked
 * for "daily" should happen at an hour somebody chose, and drift of a few hours makes that a
 * lottery. None of that applies here. "Every hour" has no preferred minute, so drift costs nothing,
 * and {@code StartInterval} is the honest expression of it -- twenty-four calendar entries would be
 * the same thing spelled out badly.
 *
 * <p>launchd runs a missed interval once at wake-up rather than replaying every hour the laptop was
 * shut, which is the behaviour you want: catching up means reading whatever changed, and whatever
 * changed is the same set however many hours have passed.
 *
 * <h2>What makes an hourly job acceptable at all</h2>
 *
 * <p>253 MB of transcripts across 239 files, the largest 51 MB. Read in full, hourly, that is a job
 * you would find in Activity Monitor and turn off. It is affordable only because
 * {@code SessionLedger} remembers each file by size and modification time, so the ordinary run opens
 * the two that changed and skips the rest without reading a byte of them.
 */
public final class SessionJob {

    /** Reverse-DNS on macOS, a unit name on Linux, a task name on Windows. */
    public static final String LABEL = "com.osscli.sessions";

    private static final String UNIT = "oss-sessions";
    private static final String TASK = "oss memory sessions";

    /** Every hour, in seconds. */
    public static final int INTERVAL_SECONDS = 3_600;

    private SessionJob() {}

    public static Path descriptor() {
        String home = System.getProperty("user.home");
        return switch (Platforms.Platform.detect()) {
            case MAC -> Path.of(home, "Library", "LaunchAgents", LABEL + ".plist");
            case LINUX -> Path.of(home, ".config", "systemd", "user", UNIT + ".timer");
            case WINDOWS -> AppPaths.BASE_DIR.resolve("sessions-task-installed");
            case UNKNOWN -> AppPaths.BASE_DIR.resolve("sessions-schedule-unsupported");
        };
    }

    static Path serviceDescriptor() {
        return Path.of(System.getProperty("user.home"), ".config", "systemd", "user", UNIT + ".service");
    }

    public static boolean isInstalled() {
        return Files.exists(descriptor());
    }

    public static Path outLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("sessions.out.log");
    }

    public static Path errLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("sessions.err.log");
    }

    /**
     * The launchd agent, as a value so it can be read without being installed.
     *
     * <p>{@code RunAtLoad} is false. Installing a job should not also run it -- the install command
     * says what it did and the first tick comes an hour later, which is a schedule rather than a
     * surprise. {@code oss memory sessions} is right there for anyone who wants it now.
     *
     * <p>No {@code KeepAlive}: this exits when it is done, and telling launchd to keep it alive
     * would restart it the instant it finished.
     */
    public static String plistFor(List<String> start, Path out, Path err) {
        String args = start.stream()
                .map(a -> "<string>" + Platforms.xml(a) + "</string>")
                .collect(Collectors.joining());
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" \
                "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                    %s
                    <string>memory</string><string>sessions</string><string>--quiet</string>
                  </array>
                  <key>StartInterval</key><integer>%d</integer>
                  <key>RunAtLoad</key><false/>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                  <key>ProcessType</key><string>Background</string>
                  <key>LowPriorityIO</key><true/>
                  <key>Nice</key><integer>10</integer>
                </dict></plist>
                """.formatted(
                        LABEL, args, INTERVAL_SECONDS, Platforms.xml(out.toString()), Platforms.xml(err.toString()));
    }

    static String serviceFor(List<String> start) {
        String exec = start.stream().map(Platforms::unitArg).collect(Collectors.joining(" "));
        return """
                [Unit]
                Description=File local CLI transcripts under what they were about

                [Service]
                Type=oneshot
                Nice=10
                IOSchedulingClass=idle
                ExecStart=%s memory sessions --quiet
                """.formatted(exec);
    }

    static String timerFor() {
        return """
                [Unit]
                Description=Hourly filing of local CLI transcripts

                [Timer]
                OnBootSec=15min
                OnUnitActiveSec=1h
                Persistent=true

                [Install]
                WantedBy=timers.target
                """;
    }

    /** Install it. Returns what happened, or null when the platform cannot hold a schedule. */
    public static String install(Path jvm, Path jar) throws IOException {
        Files.createDirectories(outLog().getParent());
        List<String> start = Platforms.startCommand(jvm, jar);

        switch (Platforms.Platform.detect()) {
            case MAC -> {
                Path p = descriptor();
                Files.createDirectories(p.getParent());
                Files.writeString(p, plistFor(start, outLog(), errLog()));
                // bootout first, so install doubles as "reinstall" rather than leaving the old
                // agent loaded alongside the new file.
                Platforms.exec("launchctl", "bootout", "gui/" + Platforms.uid() + "/" + LABEL);
                int rc = Platforms.exec("launchctl", "bootstrap", "gui/" + Platforms.uid(), p.toString());
                return rc == 0 ? "launchd agent installed: " + p : null;
            }
            case LINUX -> {
                Path timer = descriptor();
                Files.createDirectories(timer.getParent());
                Files.writeString(serviceDescriptor(), serviceFor(start));
                Files.writeString(timer, timerFor());
                Platforms.exec("systemctl", "--user", "daemon-reload");
                int rc = Platforms.exec("systemctl", "--user", "enable", "--now", UNIT + ".timer");
                if (rc != 0) {
                    return null;
                }
                Platforms.exec("loginctl", "enable-linger", System.getProperty("user.name", ""));
                return "systemd user timer installed: " + timer;
            }
            case WINDOWS -> {
                String cmd = String.join(" ", start) + " memory sessions --quiet";
                Platforms.exec("schtasks", "/delete", "/tn", TASK, "/f");
                int rc = Platforms.exec("schtasks", "/create", "/tn", TASK, "/tr", cmd, "/sc", "hourly", "/f");
                if (rc != 0) {
                    return null;
                }
                Files.createDirectories(descriptor().getParent());
                Files.writeString(descriptor(), cmd + System.lineSeparator());
                return "scheduled task installed: " + TASK;
            }
            case UNKNOWN -> {
                return null;
            }
        }
        return null;
    }

    public static boolean uninstall() throws IOException {
        boolean had = isInstalled();
        switch (Platforms.Platform.detect()) {
            case MAC -> Platforms.exec("launchctl", "bootout", "gui/" + Platforms.uid() + "/" + LABEL);
            case LINUX -> {
                Platforms.exec("systemctl", "--user", "disable", "--now", UNIT + ".timer");
                Files.deleteIfExists(serviceDescriptor());
                Platforms.exec("systemctl", "--user", "daemon-reload");
            }
            case WINDOWS -> Platforms.exec("schtasks", "/delete", "/tn", TASK, "/f");
            case UNKNOWN -> {
                // nothing was ever installed
            }
        }
        Files.deleteIfExists(descriptor());
        return had;
    }

    /**
     * The hook line that files a session the moment it ends, rather than within the hour.
     *
     * <p>Offered as a value rather than installed. Claude Code's settings file belongs to the
     * person using Claude Code, and a tool that edits another program's configuration because it
     * would be convenient is the same class of thing as a background download nobody asked for.
     *
     * <p>The hourly job stays the floor either way: a hook only fires for the tool that has one,
     * and only while that tool is installed. Between them the hook makes a session appear at once
     * and the schedule guarantees nothing is ever missed -- including sessions from every other
     * tool, and any that ended while the hook was misconfigured.
     */
    public static String hookFor(String command) {
        return """
                {
                  "hooks": {
                    "SessionEnd": [
                      { "hooks": [ { "type": "command", "command": "%s memory sessions --quiet" } ] }
                    ]
                  }
                }""".formatted(command);
    }

    /** Whether the operating system is holding it, not merely whether a file exists. */
    public static boolean running() {
        return Platforms.loaded(Platforms.Platform.detect() == Platforms.Platform.LINUX ? UNIT : LABEL);
    }
}
