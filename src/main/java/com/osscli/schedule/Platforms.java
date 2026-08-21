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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The parts of "ask the operating system to run this" that must exist exactly once.
 *
 * <p>There are two jobs to install now — the always-on board and the daily harvest — and they differ
 * only in <em>when</em> they run. Everything else is identical, and everything else is the part that
 * has already gone wrong: an unescaped {@code &} in a home directory producing a plist that launchd
 * accepts and then silently declines to start; an unquoted space in a path that systemd reads as a
 * command plus arguments; a {@code %} in a path that systemd rewrites into a different path; a
 * versioned jar path that the next {@code brew upgrade} deletes.
 *
 * <p>Every one of those was found once and fixed once. Copying the file to add a second job would
 * put all four back, in the copy, where no test looks — which is precisely how this repository grew
 * two embedders and two reference parsers. So the escaping, the platform detection, the launcher
 * lookup and the process call live here, and each job supplies only its own schedule.
 */
public final class Platforms {

    private Platforms() {}

    /** Reverse-DNS on macOS, a unit name on Linux, a task name on Windows. */
    public enum Platform {
        MAC,
        LINUX,
        WINDOWS,
        UNKNOWN;

        public static Platform detect() {
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

    /**
     * XML-escapes a value going into a plist.
     *
     * <p>Every path here comes from the filesystem, and {@code &}, {@code <} and {@code >} are all
     * legal in one. Interpolated raw, a home directory like {@code /Users/R&D} produces a plist that
     * is not XML -- and launchd does not complain when you install it, it declines to start the job
     * at the next boot, which is the furthest possible point from the mistake.
     */
    public static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * One ExecStart argument, quoted the way systemd expects.
     *
     * <p>ExecStart is split on whitespace, so an unquoted install path containing a space is read as
     * a command plus arguments. {@code systemd-analyze} on a path like {@code /Users/a b/oss}
     * reports {@code Command /Users/a is not executable} -- and at boot that is a service that
     * installs cleanly and never starts.
     */
    public static String unitArg(String value) {
        return "\"" + unitValue(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * A literal value in a unit file.
     *
     * <p>{@code %} introduces a specifier to systemd -- {@code %h} is the user's home, {@code %i}
     * the instance name -- so a path containing one is silently rewritten into a different path. A
     * literal percent is written as two.
     */
    public static String unitValue(String value) {
        return value.replace("%", "%%");
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
     * by its owner and stays put. Whatever {@code oss} means when typed is what the job should run.
     */
    public static Path launcher() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        boolean windows = Platform.detect() == Platform.WINDOWS;
        for (String dir : path.split(File.pathSeparator)) {
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
    public static List<String> startCommand(Path jvm, Path jar) {
        Path stable = launcher();
        return stable != null ? List.of(stable.toString()) : List.of(jvm.toString(), "-jar", jar.toString());
    }

    /**
     * Whether the operating system currently holds a job whose name contains this.
     *
     * <p><b>Not {@code launchctl list | grep -q}.</b> Under {@code pipefail} that reports the agent
     * as missing precisely <em>when</em> it matches: {@code grep -q} exits on the first hit and
     * closes the pipe, {@code launchctl} dies of SIGPIPE, and the pipeline returns the failure. A
     * sibling tool shipped that bug and its health check read "not loaded" for a job that was
     * running. Capture first, match second — which in Java means never building the pipe at all.
     */
    public static boolean loaded(String nameFragment) {
        String listing =
                switch (Platform.detect()) {
                    case MAC -> capture("launchctl", "list");
                    case LINUX -> capture("systemctl", "--user", "list-units", "--all", "--no-pager");
                    case WINDOWS -> capture("schtasks", "/query", "/fo", "list");
                    case UNKNOWN -> "";
                };
        return listing.contains(nameFragment);
    }

    /** Run a command and return what it printed, or empty when it could not be run at all. */
    public static String capture(String... cmd) {
        try {
            Process p =
                    new ProcessBuilder(List.of(cmd)).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out;
        } catch (InterruptedException e) {
            // Restoring the flag is the whole point of catching this separately: swallowing it
            // leaves a thread that has been asked to stop with no record that it was.
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            // A machine without launchctl/systemctl/schtasks answers "nothing is loaded", which is
            // true of that machine, rather than throwing at a caller that only wanted to report.
            return "";
        }
    }

    /** Run a command for its exit code. */
    public static int exec(String... cmd) {
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

    /** The user id launchd domains are keyed by. */
    public static String uid() {
        String s = capture("id", "-u").trim();
        return s.isEmpty() ? "501" : s;
    }
}
