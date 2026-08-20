package com.osscli.serve;

import com.osscli.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

    enum Platform {
        MAC,
        LINUX,
        WINDOWS,
        UNKNOWN;

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac") || os.contains("darwin")) {
                return MAC;
            }
            if (os.contains("win")) {
                return WINDOWS;
            }
            // Everything else with a POSIX shell is treated as Linux-like. systemd --user is
            // present on every mainstream desktop distribution, and the failure when it is not is
            // reported rather than guessed at.
            if (os.contains("nux") || os.contains("nix") || os.contains("aix") || os.contains("bsd")) {
                return LINUX;
            }
            return UNKNOWN;
        }
    }

    /** Where the unit/plist/marker lives, so uninstall and status can find it. */
    static Path descriptor() {
        String home = System.getProperty("user.home");
        return switch (Platform.detect()) {
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

    /**
     * A name for this program that survives an upgrade, or null if there is not one.
     *
     * <p>Recording the resolved jar was the obvious thing and it is wrong, because the path it
     * resolves to is a versioned directory. Installed from Homebrew, the agent came out pinned to
     * {@code /opt/homebrew/Cellar/oss/1.11.10/libexec/lib/oss.jar} — and the very next
     * {@code brew upgrade} deletes that directory. The service then fails at every login into a log
     * nobody reads, which is precisely how a sibling launch agent stayed dead for four days.
     *
     * <p>A launcher on {@code PATH} is the stable handle: Homebrew re-points
     * {@code /opt/homebrew/bin/oss} on every upgrade, and a shim in {@code ~/.local/bin} is chosen
     * by its owner and stays put. Whatever {@code oss} means when typed is what the service should
     * run.
     *
     * <p>Falls back to the jar when there is no launcher — a jar run directly from a build tree has
     * no stable name, and a pinned path still beats no service at all.
     */
    static Path launcher() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        boolean windows = Platform.detect() == Platform.WINDOWS;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : windows ? new String[] {"oss.bat", "oss.cmd", "oss.exe"} : new String[] {"oss"}) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** How to start this program, preferring the name that outlives a version. */
    static List<String> startCommand(Path jvm, Path jar) {
        Path stable = launcher();
        return stable != null ? List.of(stable.toString()) : List.of(jvm.toString(), "-jar", jar.toString());
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
        String plistArgs =
                start.stream().map(a -> "<string>" + xml(a) + "</string>").collect(Collectors.joining());
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
                """.formatted(LABEL, plistArgs, port, xml(out.toString()), xml(err.toString()));
    }

    /**
     * XML-escapes a value going into the plist.
     *
     * <p>Every path here comes from the filesystem, and {@code &}, {@code <} and {@code >} are all
     * legal in one. Interpolated raw, a home directory like {@code /Users/R&D} produces a plist that
     * is not XML -- and launchd does not complain when you install it, it declines to start the job
     * at the next boot, which is the furthest possible point from the mistake.
     *
     * <p>The existing test does parse the plist as XML rather than grepping it, which is the right
     * check; it was only ever given paths that could not break it. {@code plutil -lint}, Apple's own
     * parser, rejected all four of the ones with an ampersand in them.
     */
    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
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
                        start.stream().map(Autostart::unitArg).collect(Collectors.joining(" ")),
                        port,
                        unitValue(out.toString()),
                        unitValue(err.toString()));
    }

    /**
     * One ExecStart argument, quoted the way systemd expects.
     *
     * <p>ExecStart is split on whitespace, so an unquoted install path containing a space is read as
     * a command plus arguments. systemd-analyze on a path like {@code /Users/a b/oss} reports
     * {@code Command /Users/a is not executable} -- and at boot that is a service that installs
     * cleanly and never starts. The Windows command has quoted its arguments since it was written,
     * for exactly this reason; this one did not, because no machine here could run it to find out.
     */
    private static String unitArg(String value) {
        return "\"" + unitValue(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * A literal value in a unit file.
     *
     * <p>{@code %} introduces a specifier to systemd -- {@code %h} is the user's home, {@code %i}
     * the instance name -- so a path containing one is silently rewritten into a different path. A
     * literal percent is written as two.
     */
    private static String unitValue(String value) {
        return value.replace("%", "%%");
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
        Platform platform = Platform.detect();
        Path out = AppPaths.BASE_DIR.resolve("logs").resolve("serve.out.log");
        Path err = AppPaths.BASE_DIR.resolve("logs").resolve("serve.err.log");
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
                exec("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
                int rc = exec("launchctl", "bootstrap", "gui/" + uid(), p.toString());
                return rc == 0 ? "launchd agent installed: " + p : null;
            }
            case LINUX -> {
                String unit = unitFor(start, port, out, err);
                Path p = descriptor();
                Files.createDirectories(p.getParent());
                Files.writeString(p, unit);
                exec("systemctl", "--user", "daemon-reload");
                int rc = exec("systemctl", "--user", "enable", "--now", UNIT + ".service");
                if (rc != 0) {
                    return null;
                }
                // Without lingering, a user service stops at logout -- which looks like it randomly
                // dies. Best-effort: it needs privileges that may not be there, and the service
                // still works within a session without it.
                exec("loginctl", "enable-linger", System.getProperty("user.name", ""));
                return "systemd user service installed: " + p;
            }
            case WINDOWS -> {
                // schtasks rather than a Startup-folder shortcut: it survives a reboot, can be
                // inspected with the tools an administrator already has, and does not depend on a
                // shell being launched.
                String cmd = taskCommandFor(start, port);
                exec("schtasks", "/delete", "/tn", TASK, "/f");
                int rc = exec("schtasks", "/create", "/tn", TASK, "/tr", cmd, "/sc", "onlogon", "/f");
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
        switch (Platform.detect()) {
            case MAC -> exec("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
            case LINUX -> {
                exec("systemctl", "--user", "disable", "--now", UNIT + ".service");
                exec("systemctl", "--user", "daemon-reload");
            }
            case WINDOWS -> exec("schtasks", "/delete", "/tn", TASK, "/f");
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

    private static String uid() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return s.isEmpty() ? "501" : s;
        } catch (Exception e) {
            return "501";
        }
    }

    private static int exec(String... cmd) {
        try {
            return new ProcessBuilder(List.of(cmd))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception e) {
            // A missing launchctl/systemctl/schtasks is reported by the caller as "install failed",
            // which is the truth, rather than as a stack trace nobody can act on.
            return -1;
        }
    }
}
