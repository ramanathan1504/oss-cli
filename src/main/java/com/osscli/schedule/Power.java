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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Whether this machine is plugged in.
 *
 * <p>Asked for exactly one reason. Nearly everything here is too small to care about -- an hourly
 * tick that finds nothing changed costs 0.89 CPU-seconds, which is twenty-one seconds a day and not
 * worth a line of code to avoid. Embedding a whole archive is different: minutes of every core, and
 * a background job that spins the fan while somebody is unplugged in a meeting is how a tool earns
 * a reputation and then gets uninstalled.
 *
 * <p><b>Only the scheduled path consults this.</b> A command somebody typed runs whatever it costs,
 * because they asked; deciding on their behalf that their laptop knows better is the same
 * presumption as a background download. What defers is work nobody is waiting for.
 *
 * <p>Unknown counts as plugged in. A machine that cannot answer is usually a desktop or a server,
 * and the failure this protects against -- work quietly never happening -- is worse than the one it
 * risks.
 */
public final class Power {

    private Power() {}

    /** True only when this is known to be running on battery. */
    public static boolean onBattery() {
        String override = System.getenv("OSS_ON_BATTERY");
        if (override != null && !override.isBlank()) {
            // A test cannot unplug a laptop, and neither can CI.
            return "1".equals(override.strip()) || "true".equalsIgnoreCase(override.strip());
        }
        return switch (Platforms.Platform.detect()) {
            case MAC -> macOnBattery();
            case LINUX -> linuxOnBattery();
            default -> false;
        };
    }

    /**
     * macOS says it in one line of {@code pmset}.
     *
     * <p>"Now drawing from 'Battery Power'" against "'AC Power'". Parsed rather than shelled out to
     * a private framework, because the string has been stable for a decade and the alternative is a
     * native dependency for a boolean.
     */
    private static boolean macOnBattery() {
        String out = Platforms.capture("pmset", "-g", "batt");
        return out != null && out.toLowerCase(Locale.ROOT).contains("battery power");
    }

    /** Linux keeps it in sysfs, one file per supply. */
    private static boolean linuxOnBattery() {
        Path supplies = Path.of("/sys/class/power_supply");
        if (!Files.isDirectory(supplies)) {
            return false;
        }
        try (Stream<Path> walk = Files.list(supplies)) {
            for (Path supply : walk.toList()) {
                Path type = supply.resolve("type");
                Path online = supply.resolve("online");
                if (!Files.isReadable(type) || !Files.isReadable(online)) {
                    continue;
                }
                // Mains is the only supply worth reading: a laptop with a battery that is charging
                // is plugged in, and the battery's own status says "Charging" either way.
                if ("Mains".equalsIgnoreCase(Files.readString(type).strip())) {
                    return "0".equals(Files.readString(online).strip());
                }
            }
        } catch (IOException e) {
            // Unreadable is unknown, and unknown is plugged in.
            return false;
        }
        return false;
    }

    /** What to say when work is put off. Names the cost and how to override it. */
    public static String deferred(String what) {
        return what + " skipped — running on battery. It will run on the next tick with the laptop "
                + "plugged in, or now with  oss memory index";
    }
}
