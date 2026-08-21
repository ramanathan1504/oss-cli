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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A job that runs once a day, at a time its owner picked.
 *
 * <p>Distinct from the always-on service in exactly one respect and identical in every other: this
 * one fires on the clock and exits, that one is kept alive. So the two share {@link Platforms} and
 * differ only in the schedule stanza, which is the whole reason this is a second class and not a
 * second copy.
 *
 * <p><b>Nothing installs itself.</b> A daily job that appeared because the tool was run once is the
 * same broken promise as a 22 MB download nobody asked for. This is offered by
 * {@code oss memory schedule --install} and removed by {@code --uninstall}, and until then the
 * machine is untouched.
 *
 * <p><b>launchd rather than cron on macOS.</b> cron is deprecated there, gets no Full Disk Access,
 * and does not run a job that was missed because the laptop was closed. launchd runs the missed one
 * after wake-up, which for a daily job on a laptop is the difference between a schedule and a
 * lottery. systemd timers behave the same way with {@code Persistent=true}.
 */
public final class DailyJob {

    /** Reverse-DNS on macOS, a unit name on Linux, a task name on Windows. */
    public static final String LABEL = "com.osscli.harvest";

    private static final String UNIT = "oss-harvest";
    private static final String TASK = "oss memory harvest";

    /** Where the run's outcome is recorded, so a health check has something to read. */
    public static final Path STATE = AppPaths.BASE_DIR.resolve("logs").resolve("harvest.state");

    /** Late enough that a laptop opened at nine has caught up, early enough to be there by ten. */
    public static final int DEFAULT_HOUR = 9;

    public static final int DEFAULT_MINUTE = 15;

    private DailyJob() {}

    /** Where the definition lives, so uninstall and status can find it. */
    public static Path descriptor() {
        String home = System.getProperty("user.home");
        return switch (Platforms.Platform.detect()) {
            case MAC -> Path.of(home, "Library", "LaunchAgents", LABEL + ".plist");
            // The timer, not the service: a service with no timer never fires, so the timer is
            // the file whose presence means "this is scheduled".
            case LINUX -> Path.of(home, ".config", "systemd", "user", UNIT + ".timer");
            case WINDOWS -> AppPaths.BASE_DIR.resolve("harvest-task-installed");
            case UNKNOWN -> AppPaths.BASE_DIR.resolve("harvest-schedule-unsupported");
        };
    }

    /** The systemd service the timer starts. Nothing on the other platforms needs a second file. */
    static Path serviceDescriptor() {
        return Path.of(System.getProperty("user.home"), ".config", "systemd", "user", UNIT + ".service");
    }

    public static boolean isInstalled() {
        return Files.exists(descriptor());
    }

    /** Standard output and error of the scheduled run, in the place logs already go. */
    public static Path outLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("harvest.out.log");
    }

    public static Path errLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("harvest.err.log");
    }

    /**
     * The launchd agent, as a value so it can be read without being installed.
     *
     * <p>{@code StartCalendarInterval} rather than {@code StartInterval}: a job asked to run "every
     * 86400 seconds" drifts by however long the machine was asleep, so within a month it fires at
     * an hour nobody chose. A calendar entry means 09:15, and a missed 09:15 runs at wake-up.
     *
     * <p>No {@code KeepAlive}. This job exits when it is done, and telling launchd to keep it alive
     * would restart the harvest the moment it finished — a loop against the GitHub API, all day.
     */
    public static String plistFor(List<String> start, int hour, int minute, Path out, Path err) {
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
                    <string>memory</string><string>harvest</string>
                  </array>
                  <key>StartCalendarInterval</key>
                  <dict>
                    <key>Hour</key><integer>%d</integer>
                    <key>Minute</key><integer>%d</integer>
                  </dict>
                  <key>RunAtLoad</key><false/>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict></plist>
                """.formatted(LABEL, args, hour, minute, Platforms.xml(out.toString()), Platforms.xml(err.toString()));
    }

    /** The systemd service the timer starts — one shot, not a daemon. */
    public static String serviceFor(List<String> start, Path out, Path err) {
        return """
                [Unit]
                Description=oss daily knowledge harvest
                After=network-online.target

                [Service]
                Type=oneshot
                ExecStart=%s memory harvest
                StandardOutput=append:%s
                StandardError=append:%s
                """.formatted(
                        start.stream().map(Platforms::unitArg).collect(Collectors.joining(" ")),
                        Platforms.unitValue(out.toString()),
                        Platforms.unitValue(err.toString()));
    }

    /**
     * The timer that fires it.
     *
     * <p>{@code Persistent=true} is the systemd half of launchd's catch-up: without it, a machine
     * that was off at 09:15 simply skips that day and nothing says so.
     */
    public static String timerFor(int hour, int minute) {
        return """
                [Unit]
                Description=oss daily knowledge harvest

                [Timer]
                OnCalendar=*-*-* %02d:%02d:00
                Persistent=true

                [Install]
                WantedBy=timers.target
                """.formatted(hour, minute);
    }

    /** What the Task Scheduler is asked to run. */
    public static String taskCommandFor(List<String> start) {
        String quoted = start.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining(" "));
        return quoted + " memory harvest";
    }

    /** {@code HH:mm}, the only time format schtasks accepts on every locale. */
    public static String taskTime(int hour, int minute) {
        return "%02d:%02d".formatted(hour, minute);
    }

    /**
     * Install, replacing any previous definition.
     *
     * @return a human sentence describing what happened, or null if it could not be installed
     */
    public static String install(Path jvm, Path jar, int hour, int minute) throws IOException {
        Files.createDirectories(outLog().getParent());
        List<String> start = Platforms.startCommand(jvm, jar);

        switch (Platforms.Platform.detect()) {
            case MAC -> {
                Path p = descriptor();
                Files.createDirectories(p.getParent());
                Files.writeString(p, plistFor(start, hour, minute, outLog(), errLog()));
                // bootout first, so install doubles as "change the time" -- without it, installing
                // over an existing agent leaves the old one loaded at the old hour.
                Platforms.exec("launchctl", "bootout", "gui/" + Platforms.uid() + "/" + LABEL);
                int rc = Platforms.exec("launchctl", "bootstrap", "gui/" + Platforms.uid(), p.toString());
                return rc == 0 ? "launchd agent installed: " + p : null;
            }
            case LINUX -> {
                Path timer = descriptor();
                Files.createDirectories(timer.getParent());
                Files.writeString(serviceDescriptor(), serviceFor(start, outLog(), errLog()));
                Files.writeString(timer, timerFor(hour, minute));
                Platforms.exec("systemctl", "--user", "daemon-reload");
                int rc = Platforms.exec("systemctl", "--user", "enable", "--now", UNIT + ".timer");
                if (rc != 0) {
                    return null;
                }
                // Without lingering, a user timer stops at logout, which looks like it randomly
                // stopped working. Best-effort: it needs privileges that may not be there.
                Platforms.exec("loginctl", "enable-linger", System.getProperty("user.name", ""));
                return "systemd user timer installed: " + timer;
            }
            case WINDOWS -> {
                String cmd = taskCommandFor(start);
                Platforms.exec("schtasks", "/delete", "/tn", TASK, "/f");
                int rc = Platforms.exec(
                        "schtasks",
                        "/create",
                        "/tn",
                        TASK,
                        "/tr",
                        cmd,
                        "/sc",
                        "daily",
                        "/st",
                        taskTime(hour, minute),
                        "/f");
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

    /** Remove it. Reports whether anything was there to remove. */
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

    /** Whether the operating system is actually holding the job, not merely whether a file exists. */
    public static boolean running() {
        return Platforms.loaded(Platforms.Platform.detect() == Platforms.Platform.LINUX ? UNIT : LABEL);
    }

    /**
     * Record how the run went, for the health check to read.
     *
     * <p>Written by the harvest itself rather than by the scheduler, because the question worth
     * answering is not "did launchd start it" but "did it work". A sibling tool's scheduled job
     * failed for four days into a log nobody read; the fix is not a better log, it is a line
     * somewhere a doctor command already looks.
     */
    public static void record(boolean ok, String summary) {
        try {
            Files.createDirectories(STATE.getParent());
            Files.writeString(
                    STATE,
                    (ok ? "ok" : "failed") + "\t" + java.time.Instant.now() + "\t" + summary.replace('\n', ' ')
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A health note that cannot be written must not take down the harvest that succeeded.
            System.err.println("  (could not record the run: " + e.getMessage() + ")");
        }
    }

    /** The last recorded outcome as {@code ok|failed<TAB>instant<TAB>summary}, or null if never run. */
    public static String lastRun() {
        try {
            return Files.exists(STATE)
                    ? Files.readString(STATE, StandardCharsets.UTF_8).strip()
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** What to tell someone whose platform has no supported mechanism. */
    public static String unsupportedAdvice() {
        return "no supported scheduler on this platform (" + System.getProperty("os.name") + ").\n"
                + "  Run it yourself, however you already schedule things:  oss memory harvest";
    }
}
