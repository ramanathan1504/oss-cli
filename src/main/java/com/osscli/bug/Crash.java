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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The last thing that went wrong, kept so it can still be filed tomorrow.
 *
 * <p>A crash is reported by whoever saw it, and the moment they saw it is the moment they are
 * mid-something else. Offering to file it right there is right, and answering no has to cost
 * nothing -- so the crash is written down either way and {@code oss bug --last} picks it up
 * afterwards. Without that, declining once means the report is gone, and the second time it happens
 * the person has already learned that this tool asks questions it does not need answered.
 *
 * <p>One file, overwritten. Not a queue: a crash log that accumulates is a directory nobody empties,
 * and the useful report is the one from the failure someone can still describe.
 */
public record Crash(String command, String type, String message, String stack, String version, String platform) {

    /**
     * The {@code type} of a report somebody typed rather than one an exception produced.
     *
     * <p>A value rather than a null field, because every other part of this treats a report the
     * same way and only two things differ: there is no stack, and there is no signature.
     */
    public static final String BY_HAND = "reported by hand";

    /** A report somebody wrote, with no exception behind it. */
    public static Crash byHand(String said) {
        return new Crash("oss", BY_HAND, said == null ? "" : said.strip(), "", thisVersion(), thisPlatform());
    }

    public boolean isByHand() {
        return BY_HAND.equals(type);
    }

    /** Where the last one is kept, beside the store rather than in it: the store may be the fault. */
    static Path file() {
        return com.osscli.AppPaths.BASE_DIR.resolve("last-crash.txt");
    }

    /** What a running command knows about its own failure. */
    public static Crash of(String command, Throwable error) {
        StringWriter w = new StringWriter();
        error.printStackTrace(new PrintWriter(w));
        return new Crash(
                command == null || command.isBlank() ? "oss" : command,
                error.getClass().getName(),
                error.getMessage() == null ? "" : error.getMessage(),
                w.toString(),
                thisVersion(),
                thisPlatform());
    }

    public static String thisVersion() {
        try {
            String[] lines = new com.osscli.VersionProvider().getVersion();
            return lines.length > 0 ? lines[0] : "oss";
        } catch (Exception e) {
            // The version decorates the report; failing to read it must not lose the report.
            return "oss";
        }
    }

    public static String thisPlatform() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " · java "
                + System.getProperty("java.version");
    }

    /**
     * The line that makes two reports of the same fault the same report.
     *
     * <p>The message is deliberately not in it. "could not read /Users/a/x" and "could not read
     * /Users/b/x" are one bug, and a signature that included the message would file it twice.
     */
    public String signature() {
        // Nothing for a report somebody typed. Two people describing two different faults in their
        // own words would otherwise share one signature -- "oss:reported by hand:unknown" -- and the
        // second of them would be told their bug was already filed, by an issue about something
        // else entirely. A duplicate check that is wrong is worse than none.
        if (isByHand()) {
            return "";
        }
        return command.split("\\s+")[0] + ":" + type + ":" + topFrame();
    }

    /** The first frame that belongs to this program, which is where the fault actually is. */
    public String topFrame() {
        for (String line : stack.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("at com.osscli.")) {
                return t.substring("at ".length()).replaceAll("\\(.*\\)$", "");
            }
        }
        return "unknown";
    }

    /** Remember it, so declining the offer does not throw it away. */
    public void remember() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(
                    file(),
                    String.join(
                            "\n",
                            "command\t" + command,
                            "type\t" + type,
                            "message\t" + message.replace("\n", " "),
                            "version\t" + version,
                            "platform\t" + platform,
                            "stack",
                            stack));
        } catch (IOException ignored) {
            // Not being able to write the note is not a reason to replace one failure with two.
        }
    }

    /** Read back what was remembered, if anything was. */
    public static Optional<Crash> last() {
        try {
            if (!Files.exists(file())) {
                return Optional.empty();
            }
            String text = Files.readString(file());
            int at = text.indexOf("\nstack\n");
            if (at < 0) {
                return Optional.empty();
            }
            java.util.Map<String, String> head = new java.util.HashMap<>();
            for (String line : text.substring(0, at).split("\\R")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    head.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
            return Optional.of(new Crash(
                    head.getOrDefault("command", "oss"),
                    head.getOrDefault("type", "java.lang.Exception"),
                    head.getOrDefault("message", ""),
                    text.substring(at + "\nstack\n".length()),
                    head.getOrDefault("version", "oss"),
                    head.getOrDefault("platform", thisPlatform())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
