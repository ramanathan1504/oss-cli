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
package com.osscli.release;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every tracked path must be one Windows can check out.
 *
 * <p>A file called {@code :memory:.ses} — a session sidecar an IDE dropped in the project root,
 * swept in by {@code git add -A} — reached a tag. A colon cannot appear in a Windows filename, so
 * {@code git checkout} on the Windows runner failed with {@code error: invalid path}, the Windows
 * build never ran, Distributions failed, Packages was skipped, and v2.2.4 published with three
 * assets instead of seven. The macOS and Linux jobs passed throughout, and the local build was
 * green, because none of them can see the problem.
 *
 * <p>That is the shape worth guarding: a fault that only exists on the platform least likely to be
 * in front of you, discovered at the one moment it is most expensive — after the tag is pushed.
 * One test on the file list catches it in a second, on any machine.
 */
class CheckoutablePathsTest {

    /**
     * Characters Windows forbids in a path component.
     *
     * <p>{@code < > : " | ? *} plus control characters. The forward slash is the separator here and
     * the backslash cannot occur in a git path, so neither is listed.
     */
    private static final String FORBIDDEN = "<>:\"|?*";

    /** Names Windows reserves whatever the extension. */
    private static final List<String> RESERVED = List.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1",
            "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    @Test
    @DisplayName("no tracked file has a name Windows cannot create")
    void everyPathSurvivesAWindowsCheckout() throws IOException, InterruptedException {
        List<String> offences = new ArrayList<>();
        for (String path : trackedFiles()) {
            for (String component : path.split("/")) {
                if (component.isEmpty()) {
                    continue;
                }
                for (char c : FORBIDDEN.toCharArray()) {
                    if (component.indexOf(c) >= 0) {
                        offences.add(path + "   (contains '" + c + "')");
                    }
                }
                String bare = component.contains(".") ? component.substring(0, component.indexOf('.')) : component;
                if (RESERVED.contains(bare.toLowerCase(java.util.Locale.ROOT))) {
                    offences.add(path + "   (reserved device name '" + bare + "')");
                }
                // Windows silently strips these, so two paths can collide after checkout.
                if (component.endsWith(" ") || component.endsWith(".")) {
                    offences.add(path + "   (ends with a space or a dot)");
                }
            }
        }
        assertTrue(
                offences.isEmpty(),
                "these tracked paths break `git checkout` on Windows, which fails the Windows build "
                        + "and takes Distributions and Packages with it:\n  " + String.join("\n  ", offences));
    }

    /** What git says is in the repository — the same list a checkout has to create. */
    private static List<String> trackedFiles() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "ls-files", "-z")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        List<String> files = new ArrayList<>();
        for (String f : out.split("\0")) {
            if (!f.isBlank()) {
                files.add(f);
            }
        }
        // -z rather than plain ls-files on purpose: git quotes unusual names in the default output,
        // so ":memory:.ses" would have arrived wrapped in quotes and the colon check would still
        // have fired -- but a path with a newline in it would have arrived as two paths.
        assertTrue(files.size() > 50, "git ls-files returned " + files.size() + " paths, which cannot be right");
        return files;
    }
}
