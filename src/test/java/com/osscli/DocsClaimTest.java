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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The claim the product lives or dies on, checked against the files that make it.
 *
 * <p>The Homebrew formula says: <em>"Nothing but a GitHub token is required — no Java, no model, no
 * account."</em> It is true — the published archives carry a runtime built with {@code jlink}. The
 * README opened by asking for Java 17 and Maven anyway, and INSTALL.md's requirements table led
 * with <em>"Java 17+ — the CLI is a Java jar"</em>. Both had been telling every reader to install
 * the one prerequisite the project exists to remove.
 *
 * <p>Nobody noticed for the same reason nobody notices any stale sentence: the code kept getting
 * better and the paragraph describing it did not move. So the sentence is under test now, the same
 * way {@code HarvestClaimTest} holds the class documentation to what {@code harvest} actually does.
 */
class DocsClaimTest {

    /** Files a person reads before they install anything. */
    private static final List<String> ENTRY_DOCS = List.of("README.md", "INSTALL.md");

    /**
     * Wording that makes a mention of Java a description rather than a demand.
     *
     * <p>Java may absolutely be named — building from source needs it, and so does the plain jar.
     * What it may not be is listed flatly as something you need before you can use this.
     */
    private static final List<String> QUALIFIERS = List.of(
            "only if",
            "only to",
            "only for",
            "if you build",
            "from source",
            "plain `.jar`",
            // A sentence describing what this file used to demand is a correction, not a demand.
            "used to",
            "developing.md",
            // "that is the path that needs Java 17 and Maven" -- the path being the source build,
            // named in the clause before it.
            "that is the path");

    @Test
    @DisplayName("no entry document lists Java as something you need before you can use this")
    void javaIsNeverAFlatRequirement() throws IOException {
        List<String> offences = new ArrayList<>();
        for (String doc : ENTRY_DOCS) {
            Path path = Path.of(doc);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            // One CLAIM at a time, which is neither a line nor a paragraph.
            //
            // Line-by-line cried wolf: prose wraps, so "This README used to open by asking for
            // Java 17" put the qualifier on the line above the mention. Paragraph-by-paragraph
            // then went blind in the other direction -- a bullet list is one paragraph, so
            // "* **Java 17**" was excused by a LATER bullet reading "only if you build from
            // source". Verified by restoring the original wording: the paragraph version passed.
            //
            // So: a bullet, a table row or a heading is its own claim; anything else joins the
            // prose block it belongs to.
            int lineNumber = 0;
            StringBuilder prose = new StringBuilder();
            int proseStart = 1;
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i <= lines.size(); i++) {
                String line = i < lines.size() ? lines.get(i) : "";
                lineNumber = i + 1;
                String trimmed = line.strip();
                boolean ownClaim = trimmed.startsWith("* ")
                        || trimmed.startsWith("- ")
                        || trimmed.startsWith("|")
                        || trimmed.startsWith("#");
                if (ownClaim || trimmed.isEmpty() || i == lines.size()) {
                    check(doc, proseStart, prose.toString(), offences);
                    prose.setLength(0);
                    proseStart = lineNumber + 1;
                    if (ownClaim) {
                        check(doc, lineNumber, line, offences);
                    }
                    continue;
                }
                prose.append(line).append('\n');
            }
        }
        assertTrue(
                offences.isEmpty(),
                "the tap promises \"no Java\" and these lines ask for it anyway — qualify them "
                        + "(\"only if you build from source\") or remove them:\n  " + String.join("\n  ", offences));
    }

    /** One claim: does it name Java, and does it say in the same breath that you do not need it. */
    private static void check(String doc, int line, String claim, List<String> offences) {
        String lower = claim.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsJava = lower.contains("java 17") || lower.contains("java 21") || lower.contains("jdk");
        if (mentionsJava && QUALIFIERS.stream().noneMatch(lower::contains)) {
            offences.add(
                    doc + ":" + line + "  " + claim.strip().lines().findFirst().orElse(""));
        }
    }

    @Test
    @DisplayName("the README gives the install command that actually exists")
    void readmeInstallsTheWayThePageDoes() throws IOException {
        String readme = Files.readString(Path.of("README.md"));

        // It used to say: build the jar, then `sudo nano /usr/local/bin/oss` and paste a shell
        // wrapper around an absolute path into it. That is not how anybody installs this, it is
        // not what ubuos.com tells them, and the path it told them to paste carried a version
        // number that went stale with every release.
        assertTrue(
                readme.contains("brew install ramanathan1504/oss-cli/oss"),
                "the README should give the install line the site gives");
        assertTrue(
                !readme.contains("sudo nano /usr/local/bin/oss"),
                "the hand-written launcher instructions are back; brew and the archives replaced them");
    }

    @Test
    @DisplayName("no entry document points at a file this repository does not have")
    void noDocumentNamesAMissingFile() throws IOException {
        // "Configure osscli-master.sh with your correct paths" survived the deletion of
        // osscli-master.sh, which is worse than no instructions: it sends a reader looking for a
        // file that is not there and leaves them assuming they have lost it.
        List<String> offences = new ArrayList<>();
        java.util.regex.Pattern scripts = java.util.regex.Pattern.compile("`([A-Za-z0-9_.-]+\\.(?:sh|plist))`");
        for (String doc : ENTRY_DOCS) {
            Path path = Path.of(doc);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            for (String line : Files.readAllLines(path)) {
                // A line saying a file is gone must be allowed to name it. Removing the name would
                // leave "this section used to describe a script", which explains nothing.
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("used to") || lower.contains("is not in this repository")) {
                    continue;
                }
                java.util.regex.Matcher m = scripts.matcher(line);
                while (m.find()) {
                    String named = m.group(1);
                    if (!anywhereInRepo(named)) {
                        offences.add(doc + " names " + named + ", which is not in this repository");
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), String.join("\n  ", offences));
    }

    /**
     * Whether a file of this name exists anywhere that is not build output.
     *
     * <p>Two guessed directories was the first version, and it reported {@code pack.sh} -- which is
     * real, at {@code runner/packs/example/pack.sh} -- as missing. A test that cries wolf about a
     * correct reference gets switched off, and then it is not there for the reference that is
     * actually wrong.
     */
    private static boolean anywhereInRepo(String name) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(Path.of("."))) {
            return walk.filter(p -> !p.toString().contains("/target/"))
                    .anyMatch(p -> p.getFileName().toString().equals(name));
        }
    }
}
