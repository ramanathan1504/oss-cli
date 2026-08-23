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

import com.osscli.github.GitHubClient;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a report about this program goes.
 *
 * <p>Named in one place and overridable, for two reasons that are really the same one. A fork is a
 * different program with different maintainers, and one that shipped this command unchanged would
 * send its users' crashes to a repository that cannot fix them. And a test must be able to point the
 * only outward write in this codebase at something that is not a real issue tracker.
 */
public final class Home {

    /** Filed with this, so a maintainer can tell a machine-assembled report from a written one. */
    public static final List<String> LABELS = List.of("bug", "filed-from-cli");

    private Home() {}

    /** {@code owner/name}, overridable by a fork or a test. */
    public static String slug() {
        String override = System.getenv("OSS_BUG_REPO");
        if (override != null && override.contains("/")) {
            return override.trim();
        }
        return "ramanathan1504/oss-cli";
    }

    public static String owner() {
        return slug().substring(0, slug().indexOf('/'));
    }

    public static String repo() {
        return slug().substring(slug().indexOf('/') + 1);
    }

    public static String issueUrl(long number) {
        return "https://github.com/" + slug() + "/issues/" + number;
    }

    /** Where somebody with no token, or no network, can still put the report. */
    public static String newIssueUrl() {
        return "https://github.com/" + slug() + "/issues/new";
    }

    /**
     * Whether this exact fault is already filed.
     *
     * <p>By the signature the body carries, not by the title: two people hitting one bug describe it
     * two ways, and a model drafting each of their titles makes that worse rather than better. The
     * signature is the command, the exception and the first frame inside this program, which is the
     * same string on both machines.
     *
     * <p>Never fatal. Search is a courtesy to the maintainer's inbox, and a search that fails must
     * not stop somebody reporting a bug -- worst case a duplicate is filed, which is a smaller
     * problem than a report that was not.
     */
    public static Optional<Long> alreadyFiled(GitHubClient github, String signature) {
        if (signature == null || signature.isBlank()) {
            return Optional.empty();
        }
        try {
            var found = github.searchIssuesAndPrs("repo:" + slug() + " is:issue \"oss-signature: " + signature + "\"");
            return found.isEmpty()
                    ? Optional.empty()
                    : Optional.of((long) found.get(0).number());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Every repository this store has synced, so the redactor knows which names are not its business.
     *
     * <p>Empty when the store cannot be read, and that is the honest answer: a crash in the storage
     * layer is exactly when this is asked, and a redactor given nothing redacts paths and keys and
     * leaves names alone rather than refusing to file the report about the storage layer.
     */
    public static Set<String> syncedRepositories() {
        try {
            return Set.copyOf(com.osscli.storage.SqliteStorage.loadMonitoredRepositories());
        } catch (Exception e) {
            return Set.of();
        }
    }
}
