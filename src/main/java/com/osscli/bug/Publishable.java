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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything that must not be published, taken out before anybody is asked to publish it.
 *
 * <p>A bug report is the one thing this tool sends outward, and it is assembled from the three
 * places a machine keeps its private business: the command line, a stack trace and a working
 * directory. Each of those routinely carries a home directory with a real name in it, an API key
 * that was passed as an argument, and the name of every repository somebody follows.
 *
 * <p>The last of those is the one that would be missed. This repository's rule is that a worked
 * example never names a third-party project, because naming one reads as "this tool is for that
 * project" -- and a crash report filed from a laptop is a worked example that publishes itself.
 * {@code oss hub} against eight repositories puts eight of somebody else's project names into a
 * public issue, none of which is the bug. So the store's own list of synced repositories is handed
 * in and every one of them becomes {@code owner/name}, which is what this repository writes anyway.
 *
 * <p><b>Credentials are not redacted here.</b> {@link com.osscli.util.Redactor} already does that,
 * for text on its way <em>into</em> the store, and it knows more shapes than this ever would --
 * AWS keys, Slack tokens, private key blocks, passwords, JDBC credentials. A second copy of those
 * patterns is the thing this repository has been bitten by three times, and the copy that would
 * rot is this one: nobody adding a new token shape thinks to look in the bug reporter. So this
 * calls it, and adds only what is specific to going outward -- who the machine belongs to, and
 * whose projects are on it.
 *
 * <p>Pure, and separated from everything that gathers or posts, because the consequence of getting
 * it wrong is not a failed command -- it is a public issue that cannot be unpublished. A test can
 * hand it the nastiest string it can think of; nothing here needs a network, a store or a machine.
 */
public final class Publishable {

    /**
     * Where a repository name appears in a text that named it itself.
     *
     * <p>The store's list is not enough, and a journey caught that: the name of somebody else's
     * project reaches a crash report through {@code --repo}, and a report is most likely to be
     * filed from a machine whose store is fresh, unreadable, or the fault -- exactly the cases
     * where that list comes back empty. So the text is read for names as well as the store, and the
     * union is what gets taken out.
     *
     * <p>Anchored on the flag or the host rather than on the shape. A bare {@code a/b} would match
     * every relative path and half of every stack trace, and a redactor that eats the evidence gets
     * worked around rather than fixed.
     */
    private static final Pattern NAMED =
            Pattern.compile("(?:--repo(?:sitory)?|-r|repo:)[=\\s]*([A-Za-z0-9._-]+/[A-Za-z0-9._-]+)"
                    + "|github\\.com[/:]([A-Za-z0-9._-]+/[A-Za-z0-9._-]+?)(?:\\.git)?(?![\\w./-])");

    /** Every repository name these texts name, so it can be taken out of all of them. */
    public static Set<String> namesIn(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            java.util.regex.Matcher m = NAMED.matcher(text);
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                if (name != null && !name.isBlank()) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private Publishable() {}

    /**
     * Take out the home directory, every credential, every address and every named repository.
     *
     * @param text what would be posted
     * @param home the user's home directory, whose last segment is also their account name
     * @param repositories the repositories this store has synced, in {@code owner/name} form
     */
    public static String text(String text, String home, Set<String> repositories) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Credentials first, and through the one implementation of that: a key inside a path must
        // not survive because the path was rewritten to ~ around it.
        String out = com.osscli.util.Redactor.redact(text).text();
        out = EMAIL.matcher(out).replaceAll("[redacted]");

        // Repositories before paths: a checkout under the home directory is BOTH, and the name is
        // the part that identifies somebody else's project.
        for (String repo : sortedLongestFirst(repositories)) {
            if (repo == null || !repo.contains("/") || repo.isBlank()) {
                continue;
            }
            out = out.replace(repo, "owner/name");
            // The bare name too: a stack trace says `logging-log4j2` where the ledger says
            // `apache/logging-log4j2`, and only one of those two would have been taken out.
            String bare = repo.substring(repo.indexOf('/') + 1);
            if (bare.length() >= 3) {
                out = out.replaceAll("(?<![\\w/-])" + Pattern.quote(bare) + "(?![\\w-])", "name");
            }
        }

        if (home != null && !home.isBlank()) {
            out = out.replace(home, "~");
            // The account name on its own. It is the last segment of the home directory, and it
            // appears in places no path rewrite reaches -- a git author, a launchd domain, a
            // keychain label.
            String account = home.substring(home.replace('\\', '/').lastIndexOf('/') + 1);
            if (account.length() >= 3) {
                out = out.replaceAll("(?<![\\w.-])" + Pattern.quote(account) + "(?![\\w-])", "someone");
            }
        }
        return out;
    }

    /**
     * Longest first, so a name that contains another is replaced before its own substring is.
     *
     * <p>Given {@code owner/log4j} and {@code owner/log4j-extras}, replacing the shorter one first
     * leaves {@code owner/name-extras} -- which still names the project.
     */
    private static Set<String> sortedLongestFirst(Set<String> repositories) {
        if (repositories == null || repositories.isEmpty()) {
            return Set.of();
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(repositories);
        sorted.sort((a, b) -> Integer.compare(length(b), length(a)));
        return new LinkedHashSet<>(sorted);
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /** Whether anything was taken out, so the person confirming can be told rather than guess. */
    public static boolean changed(String before, String after) {
        return !java.util.Objects.equals(before, after);
    }
}
