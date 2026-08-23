package com.osscli.serve;

import com.osscli.AppPaths;
import com.osscli.schedule.Platforms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Keep the local service running, on whichever operating system this is.
 *
 * <p>Each platform has exactly one right answer for "start this at login and restart it if it
 * dies", and they share no common mechanism -- launchd, systemd and the Task Scheduler differ in
 * file format, install command and where they put logs. So this is three implementations behind one
 * decision, rather than a lowest common denominator that would be worse everywhere.
 *
 * <p>Deliberately NOT a background thread, a wrapper script or a cron entry. Those are the portable
 * options and each is worse than what the platform already provides: none of them restart after a
 * crash, survive a reboot, or can be inspected and stopped with the tools an administrator already
 * knows.
 *
 * <p>An unrecognised platform reports that plainly and does nothing. Writing a file into a directory
 * that may not be read by anything would look like success.
 */
final class Autostart {

    /** Reverse-DNS on macOS, a unit name on Linux, a task name on Windows. */
    private static final String LABEL = "com.osscli.serve";

    private static final String UNIT = "oss-serve";
    private static final String TASK = "oss serve";

    private Autostart() {}

    /**
     * The shared platform detection, named locally so the switches below stay readable.
     *
     * <p>It used to be a second enum with a second copy of the os.name rules. Two copies of "what
     * kind of machine is this" is how one job learns about a platform and the other does not.
     */
    private static Platforms.Platform platform() {
        return Platforms.Platform.detect();
    }

    /** Where the unit/plist/marker lives, so uninstall and status can find it. */
    static Path descriptor() {
        String home = System.getProperty("user.home");
        return switch (platform()) {
            case MAC -> Path.of(home, "Library", "LaunchAgents", LABEL + ".plist");
            case LINUX -> Path.of(home, ".config", "systemd", "user", UNIT + ".service");
            // Windows keeps its definition inside the Task Scheduler rather than in a file we own,
            // so a marker stands in for "installed" and keeps the three platforms answerable by the
            // same question.
            case WINDOWS -> AppPaths.BASE_DIR.resolve("serve-task-installed");
            case UNKNOWN -> AppPaths.BASE_DIR.resolve("serve-autostart-unsupported");
        };
    }

    static boolean isInstalled() {
        return Files.exists(descriptor());
    }

    /** Whether this machine has a mechanism at all, asked before anything is given up for one. */
    static boolean supported() {
        return platform() != Platforms.Platform.UNKNOWN;
    }

    /** Where the service's own output goes. Named once, because a failure is read from it. */
    static Path outLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("serve.out.log");
    }

    static Path errLog() {
        return AppPaths.BASE_DIR.resolve("logs").resolve("serve.err.log");
    }

    /**
     * What makes an installed service start <em>now</em>, rather than at the next login.
     *
     * <p>Installing is not starting, and the gap between the two is where saying yes used to stop
     * working. On Windows the task is registered {@code onlogon} and does not run until one; on
     * macOS and Linux the service is started at install, but if that first start failed -- and it
     * did, every time, because the terminal that asked the question still held the port -- the
     * restart policy waits out its interval before trying again. A minute of nothing is what a
     * person reads as "it did not work".
     *
     * <p>A value rather than a call, for the reason {@link #plistFor} is: two of these three can
     * never be run on the machine you are on, and something unreadable is something unchecked.
     */
    static List<String> startNowCommand() {
        return switch (platform()) {
            // kickstart -k, not `launchctl start`: -k restarts it if it is already up and, the part
            // that matters here, ignores the ThrottleInterval a failed first start just began.
            case MAC -> List.of("launchctl", "kickstart", "-k", "gui/" + Platforms.uid() + "/" + LABEL);
            case LINUX -> List.of("systemctl", "--user", "restart", UNIT + ".service");
            case WINDOWS -> List.of("schtasks", "/run", "/tn", TASK);
            case UNKNOWN -> List.of();
        };
    }

    /** Start it now. Reports whether the platform's own tool said it did. */
    static boolean startNow() {
        List<String> cmd = startNowCommand();
        return !cmd.isEmpty() && Platforms.exec(cmd.toArray(new String[0])) == 0;
    }

    /** The stable name for this program — one lookup, shared with the daily job. */
    static Path launcher() {
        return Platforms.launcher();
    }

    /** How to start this program, preferring the name that outlives a version. */
    static List<String> startCommand(Path jvm, Path jar) {
        return Platforms.startCommand(jvm, jar);
    }

    /**
     * The launchd plist for a given start command and port.
     *
     * <p>Separated from {@link #install} so the definition can be read without installing it. It
     * used to be built inside the switch, next to the {@code launchctl} calls, which meant the only
     * way to see what a platform would be told was to tell it — so on any machine, two of the three
     * definitions were unreadable and untested. They are the part that goes wrong: a plist naming a
     * path that {@code brew} deletes on the next upgrade is a service that stops working at a time
     * unrelated to anything anyone did.
     */
    static String plistFor(List<String> start, int port, Path out, Path err) {
        String plistArgs = start.stream()
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
                    <string>serve</string><string>--no-open</string>
                    <string>--port</string><string>%d</string>
                  </array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><true/>
                  <key>ThrottleInterval</key><integer>60</integer>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict></plist>
                """.formatted(LABEL, plistArgs, port, Platforms.xml(out.toString()), Platforms.xml(err.toString()));
    }

    /** The systemd user unit, for the same reason. */
    static String unitFor(List<String> start, int port, Path out, Path err) {
        return """
                [Unit]
                Description=oss local service
                After=network.target

                [Service]
                ExecStart=%s serve --no-open --port %d
                Restart=always
                RestartSec=60
                StandardOutput=append:%s
                StandardError=append:%s

                [Install]
                WantedBy=default.target
                """.formatted(
                        start.stream().map(Platforms::unitArg).collect(Collectors.joining(" ")),
                        port,
                        Platforms.unitValue(out.toString()),
                        Platforms.unitValue(err.toString()));
    }

    /** What the Task Scheduler is asked to run. */
    static String taskCommandFor(List<String> start, int port) {
        String quoted = start.stream().map(a -> "\"" + a + "\"").collect(Collectors.joining(" "));
        return "%s serve --no-open --port %d".formatted(quoted, port);
    }

    /**
     * Install, replacing any previous definition.
     *
     * @return a human sentence describing what happened, or null if the platform is unsupported
     */
    static String install(Path jvm, Path jar, int port) throws IOException {
        Platforms.Platform platform = platform();
        Path out = outLog();
        Path err = errLog();
        Files.createDirectories(out.getParent());

        List<String> start = startCommand(jvm, jar);

        switch (platform) {
            case MAC -> {
                String plist = plistFor(start, port, out, err);
                Path p = descriptor();
                Files.createDirectories(p.getParent());
                Files.writeString(p, plist);
                // bootout first, so install doubles as "apply a change" -- without it, editing the
                // port and re-installing leaves the old agent running on the old one.
                Platforms.exec("launchctl", "bootout", "gui/" + Platforms.uid() + "/" + LABEL);
                int rc = Platforms.exec("launchctl", "bootstrap", "gui/" + Platforms.uid(), p.toString());
                return rc == 0 ? "launchd agent installed: " + p : null;
            }
            case LINUX -> {
                String unit = unitFor(start, port, out, err);
                Path p = descriptor();
                Files.createDirectories(p.getParent());
                Files.writeString(p, unit);
                Platforms.exec("systemctl", "--user", "daemon-reload");
                int rc = Platforms.exec("systemctl", "--user", "enable", "--now", UNIT + ".service");
                if (rc != 0) {
                    return null;
                }
                // Without lingering, a user service stops at logout -- which looks like it randomly
                // dies. Best-effort: it needs privileges that may not be there, and the service
                // still works within a session without it.
                Platforms.exec("loginctl", "enable-linger", System.getProperty("user.name", ""));
                return "systemd user service installed: " + p;
            }
            case WINDOWS -> {
                // schtasks rather than a Startup-folder shortcut: it survives a reboot, can be
                // inspected with the tools an administrator already has, and does not depend on a
                // shell being launched.
                String cmd = taskCommandFor(start, port);
                Platforms.exec("schtasks", "/delete", "/tn", TASK, "/f");
                int rc = Platforms.exec("schtasks", "/create", "/tn", TASK, "/tr", cmd, "/sc", "onlogon", "/f");
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
    static boolean uninstall() throws IOException {
        boolean had = isInstalled();
        switch (platform()) {
            case MAC -> Platforms.exec("launchctl", "bootout", "gui/" + Platforms.uid() + "/" + LABEL);
            case LINUX -> {
                Platforms.exec("systemctl", "--user", "disable", "--now", UNIT + ".service");
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

    /** What to tell someone whose platform has no supported mechanism. */
    static String unsupportedAdvice(int port) {
        return "no supported autostart on this platform (" + System.getProperty("os.name") + ").\n"
                + "  Start it yourself with:  oss serve --no-open --port " + port;
    }
}
