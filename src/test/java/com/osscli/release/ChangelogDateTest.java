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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a release is dated the same day here as it is on the website.
 *
 * <p>These two disagreed by a day. The site is generated from GitHub's {@code published_at}, which
 * is UTC; this file was typed by hand at release time, in local time. Three releases tagged late in
 * a UTC evening therefore read {@code 2026-08-16} here and {@code 2026-08-15} there — the same
 * release, two dates, and no way for a reader to tell which was wrong.
 *
 * <p>A date in the future in UTC is the exact shape of that mistake, and it is the only part of it a
 * test can see without a network. Typing tomorrow's date is always wrong; typing yesterday's is
 * merely a release PR opened the day before, which is fine.
 */
class ChangelogDateTest {

    private static final Pattern ENTRY = Pattern.compile("^## (\\d+\\.\\d+\\.\\d+)$", Pattern.MULTILINE);
    private static final Pattern DATE = Pattern.compile("^_(\\d{4}-\\d{2}-\\d{2})_$", Pattern.MULTILINE);

    private record Release(String version, LocalDate date) {}

    private static List<Release> releases() throws IOException {
        Path changelog = Path.of("CHANGELOG.md");
        assertTrue(Files.exists(changelog), "CHANGELOG.md is part of the release surface and must exist");
        String text = Files.readString(changelog);

        // Each version's date must appear inside that version's own section. Searching the whole of
        // the rest of the file instead -- which the first version of this did -- means an UNDATED
        // release silently borrows the date of the one below it, so the check that every entry is
        // dated can never fail. A parser that cannot report the absence it exists to find is
        // decoration.
        List<Release> out = new ArrayList<>();
        Matcher heading = ENTRY.matcher(text);
        List<int[]> sections = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        while (heading.find()) {
            versions.add(heading.group(1));
            sections.add(new int[] {heading.end(), text.length()});
            if (sections.size() > 1) {
                sections.get(sections.size() - 2)[1] = heading.start();
            }
        }
        for (int i = 0; i < versions.size(); i++) {
            Matcher date = DATE.matcher(text.substring(sections.get(i)[0], sections.get(i)[1]));
            if (date.find()) {
                out.add(new Release(versions.get(i), LocalDate.parse(date.group(1))));
            }
        }
        assertFalse(out.isEmpty(), "no dated entries found — the format this parses has changed");
        return out;
    }

    @Test
    @DisplayName("no release is dated in the future in UTC")
    void noReleaseIsDatedTomorrow() throws IOException {
        // The website renders published_at, which is UTC. A local date typed after midnight local
        // but before midnight UTC is a day ahead of what the site will say, forever.
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);

        for (Release r : releases()) {
            assertFalse(
                    r.date().isAfter(todayUtc),
                    r.version() + " is dated " + r.date() + ", which is after today in UTC (" + todayUtc
                            + "). The site dates releases from GitHub's published_at, in UTC — "
                            + "a local date typed late in the evening will not match it.");
        }
    }

    @Test
    @DisplayName("entries run newest first, and their dates do not go backwards")
    void datesDescendWithVersions() throws IOException {
        List<Release> releases = releases();

        for (int i = 1; i < releases.size(); i++) {
            Release newer = releases.get(i - 1);
            Release older = releases.get(i);
            assertFalse(
                    newer.date().isBefore(older.date()),
                    newer.version() + " (" + newer.date() + ") is listed above " + older.version() + " (" + older.date()
                            + ") but dated earlier — one of the two dates is wrong.");
        }
    }

    @Test
    @DisplayName("every entry carries a date at all")
    void everyEntryIsDated() throws IOException {
        String text = Files.readString(Path.of("CHANGELOG.md"));
        int headings = 0;
        Matcher m = ENTRY.matcher(text);
        while (m.find()) {
            headings++;
        }

        assertTrue(
                releases().size() == headings,
                "there are " + headings + " version headings but " + releases().size()
                        + " dated entries — an undated release is one the site cannot place.");
    }
}
