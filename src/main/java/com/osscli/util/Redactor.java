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
package com.osscli.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips credentials out of text on its way into the database.
 *
 * <p>Ingestion used to store whatever it was given, verbatim. Pointed at a raw AI-assistant export
 * -- the obvious first thing anyone does -- that meant API keys, tokens and database URLs pasted
 * into a conversation months ago went into SQLite in cleartext. On this machine it put a live
 * production database password, a GitHub PAT and three Google API keys into an iCloud-synced file,
 * where they sat for seven weeks.
 *
 * <p>Documentation is not a control: {@code drive.paths} is one config value, and pointing it back
 * at a raw export is a single edit away. So the guarantee belongs here, at the point of write,
 * where it holds no matter what the source is.
 *
 * <p>The patterns mirror the companion harvester's rules deliberately. Two lessons are baked in:
 *
 * <ul>
 *   <li><b>Match the shape of the secret, not the context.</b> The database rule once required a
 *       literal {@code jdbc:} prefix, so a credential in a sibling variable on the same line --
 *       {@code DATABASE_URL="postgresql://user:pass@host"} -- was never scrubbed. It is now
 *       scheme-agnostic.
 *   <li><b>Replace only the secret, keep the syntax.</b> {@code user:secret@host} becomes {@code
 *       user:[REDACTED:label]@host} so the text stays readable and diffable.
 * </ul>
 *
 * <p>This is a safety net, not a guarantee. It catches known shapes; a credential in a format not
 * listed here still gets through. Feeding it already-scrubbed text remains the better path, and a
 * secret that reached storage stays compromised until it is rotated.
 */
public final class Redactor {

    private static final String MARKER_PREFIX = "[REDACTED:";

    private record Rule(String label, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("aws-access-key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new Rule("github-token", Pattern.compile("\\b(?:ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}")),
            new Rule("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
            new Rule("slack-token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new Rule(
                    "private-key",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
            // (?!\$\{) skips template references -- ${DB_PASSWORD}, Log4j's
            // ${secure:sys:...} -- which point AT a secret rather than being one.
            new Rule(
                    "password",
                    Pattern.compile(
                            "(\\bpass(?:word|wd)\\s*[=:]\\s*['\"]?)(?!\\$\\{)[^\\s'\"]{8,}", Pattern.CASE_INSENSITIVE)),
            new Rule("bearer-token", Pattern.compile("(\\bBearer\\s+)[A-Za-z0-9._\\-]{25,}")),
            new Rule("jdbc-credentials", Pattern.compile("((?:jdbc:)?[a-z][a-z0-9+.\\-]*://[^\\s:/@]+:)[^\\s@/]+(@)")));

    /** Redacted text plus a tally of what was removed, so callers can report it. */
    public record Result(String text, Map<String, Integer> counts) {
        public boolean redactedAnything() {
            return !counts.isEmpty();
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            counts.forEach((label, n) -> parts.add(n + "x " + label));
            return String.join(", ", parts);
        }
    }

    private Redactor() {}

    public static Result redact(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return new Result(text, counts);
        }
        String out = text;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            int found = 0;
            while (m.find()) {
                // Never re-match an existing marker. A marker occupies exactly the position a
                // secret did, so `user:[REDACTED:jdbc-credentials]@host` matches the database
                // rule all over again. The replacement is identical, so the text is unchanged
                // and the bug is invisible -- except in the tally, which then reports dozens of
                // credentials in an already-clean corpus. A warning that cries wolf is worse
                // than no warning, because it teaches the reader to skip the next one.
                if (m.group().contains(MARKER_PREFIX)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                found++;
                // Keep whatever groups the rule captured (the surrounding syntax) and
                // replace only the secret between them.
                StringBuilder replacement = new StringBuilder();
                for (int g = 1; g <= m.groupCount(); g++) {
                    if (g == 1) {
                        replacement.append(m.group(g) == null ? "" : m.group(g));
                        replacement.append(MARKER_PREFIX).append(rule.label()).append("]");
                    } else {
                        replacement.append(m.group(g) == null ? "" : m.group(g));
                    }
                }
                if (m.groupCount() == 0) {
                    replacement.setLength(0);
                    replacement.append(MARKER_PREFIX).append(rule.label()).append("]");
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
            }
            m.appendTail(sb);
            if (found > 0) {
                counts.merge(rule.label(), found, Integer::sum);
                out = sb.toString();
            }
        }
        return new Result(out, counts);
    }
}
