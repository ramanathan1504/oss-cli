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
package com.osscli.llm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * How much memory this machine actually has spare.
 *
 * <p>Asked because loading a model larger than the free memory does not fail — it swaps, and the
 * whole machine stops responding for minutes while it does. That is the worst failure mode this
 * program has: not a wrong answer, not an error, but a laptop that has to be waited out. A 7B model
 * on an 8 GB Apple-silicon machine with a browser open did it for ten minutes.
 *
 * <p>Everything here degrades to {@link #UNKNOWN} rather than guessing. A memory reading that cannot
 * be taken must never be the reason a command refuses to run: the check exists to prevent a freeze,
 * and refusing on no evidence would be its own kind of broken.
 */
public final class MachineMemory {

    private static final Logger LOGGER = LogManager.getLogger(MachineMemory.class);

    /** No reading could be taken. Callers proceed as they did before this existed. */
    public static final MachineMemory UNKNOWN = new MachineMemory(0, 0);

    private final long totalBytes;
    private final long availableBytes;

    private MachineMemory(long totalBytes, long availableBytes) {
        this.totalBytes = totalBytes;
        this.availableBytes = availableBytes;
    }

    /**
     * A reading with the numbers already in hand.
     *
     * <p>Exists so the arithmetic can be tested against stated figures. Reading the real machine
     * gives a different answer every second, which is untestable, and a rule about memory that
     * nobody can pin down is one that drifts.
     */
    public static MachineMemory of(long totalBytes, long availableBytes) {
        return new MachineMemory(totalBytes, availableBytes);
    }

    public boolean known() {
        return totalBytes > 0 && availableBytes > 0;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public long availableBytes() {
        return availableBytes;
    }

    /**
     * How much of the free memory a model may take: half of it.
     *
     * <p>The point is not to fit the model in — it is to fit it in <em>and leave the machine
     * usable</em>. Taking the last free gigabyte technically succeeds and still makes everything
     * else on the desktop unresponsive, which from where the user sits is the same freeze.
     *
     * <p>A share of what is free, rather than a fixed reserve subtracted from it. The fixed reserve
     * was tried first and was wrong in the case that matters: on a loaded 8 GB laptop with 2.2 GB
     * free, a 2 GB reserve left 0.1 GB usable and refused every model — including a 0.5B one that
     * had just run perfectly well. A rule that forbids what demonstrably works is not protecting
     * anybody, it is only turning the feature off.
     *
     * <p>Half of 2 GB free is 1 GB for the model and 1 GB left for the user, which is the intent
     * stated plainly.
     */
    public long usableBytes() {
        return availableBytes / 2;
    }

    /** What is deliberately left for everything else on the machine. */
    public long reserveBytes() {
        return availableBytes - usableBytes();
    }

    public static String human(long bytes) {
        double gb = bytes / 1_000_000_000.0;
        return gb >= 10 ? String.format("%.0f GB", gb) : String.format("%.1f GB", gb);
    }

    @Override
    public String toString() {
        return known() ? human(availableBytes) + " free of " + human(totalBytes) : "memory unknown";
    }

    // ==========================================
    // Reading it
    // ==========================================

    /** This machine, now. Never throws. */
    public static MachineMemory read() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("mac")) {
                return readMac();
            }
            if (os.contains("linux")) {
                return readLinux();
            }
            if (os.contains("win")) {
                return readWindows();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.debug("Could not read machine memory: {}", e.getMessage());
        }
        return UNKNOWN;
    }

    /**
     * macOS: {@code hw.memsize} for the total, {@code vm_stat} for what is actually reclaimable.
     *
     * <p>"Free" alone is misleading here — macOS deliberately keeps free memory near zero and holds
     * the rest as inactive and speculative pages, which are handed back on demand. Reading only the
     * free count would report an idle machine as full and refuse every model.
     */
    private static MachineMemory readMac() throws Exception {
        long total = Long.parseLong(run("sysctl", "-n", "hw.memsize").strip());

        String stat = run("vm_stat");
        long pageSize = 4096;
        java.util.regex.Matcher pm =
                java.util.regex.Pattern.compile("page size of (\\d+) bytes").matcher(stat);
        if (pm.find()) {
            pageSize = Long.parseLong(pm.group(1));
        }
        long free = pages(stat, "Pages free");
        long inactive = pages(stat, "Pages inactive");
        long speculative = pages(stat, "Pages speculative");
        long purgeable = pages(stat, "Pages purgeable");

        long available = (free + inactive + speculative + purgeable) * pageSize;
        return available > 0 ? new MachineMemory(total, available) : UNKNOWN;
    }

    private static long pages(String vmStat, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(label) + ":\\s+(\\d+)")
                .matcher(vmStat);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /** Linux: MemAvailable is the kernel's own answer to this exact question, so it is used as given. */
    private static MachineMemory readLinux() throws Exception {
        Path meminfo = Path.of("/proc/meminfo");
        if (!Files.isReadable(meminfo)) {
            return UNKNOWN;
        }
        long total = 0;
        long available = 0;
        for (String line : Files.readAllLines(meminfo)) {
            if (line.startsWith("MemTotal:")) {
                total = kb(line);
            } else if (line.startsWith("MemAvailable:")) {
                available = kb(line);
            }
        }
        return total > 0 && available > 0 ? new MachineMemory(total, available) : UNKNOWN;
    }

    private static long kb(String line) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)\\s*kB").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) * 1024 : 0L;
    }

    private static MachineMemory readWindows() throws Exception {
        // Values come back in kilobytes, one per line, under a header.
        String out = run(
                "powershell",
                "-NoProfile",
                "-Command",
                "$o=Get-CimInstance Win32_OperatingSystem;"
                        + "'{0} {1}' -f $o.TotalVisibleMemorySize,$o.FreePhysicalMemory");
        String[] parts = out.strip().split("\\s+");
        if (parts.length < 2) {
            return UNKNOWN;
        }
        long total = Long.parseLong(parts[0]) * 1024;
        long available = Long.parseLong(parts[1]) * 1024;
        return total > 0 && available > 0 ? new MachineMemory(total, available) : UNKNOWN;
    }

    /** Runs a short command and returns its output, or throws. Bounded, because this is on a hot path. */
    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (java.io.InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException(command[0] + " did not answer");
        }
        return out;
    }
}
