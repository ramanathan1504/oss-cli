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
package com.osscli.ext;

import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is attached, to which subject, and how it is reached.
 *
 * <p><b>A pack is a subject; a support pack is something attached to it.</b> The subject is
 * ordinarily a repository being followed, and every repository in
 * {@code monitored_repositories} is therefore already a pack — which is what makes this work
 * before anyone configures anything. Nothing has to be declared to have packs; a manifest saying
 * {@code "supports": "apache/logging-log4j2"} only says which one it belongs under.
 *
 * <p>Before this there was a flat list. Three registered runners looked equally applicable to
 * fourteen repositories, and matching one to the issue in front of you was a thing you knew or did
 * not — including when the thing doing the matching was a model, which had never been told any of
 * it and so could only guess or stay silent.
 *
 * <p><b>The same tree answers both readers.</b> {@code ext list} prints it and the prompt block
 * states it, because a model told something different from what the operator can see on screen is
 * the exact condition under which its answer stops being checkable. One implementation, two
 * renderings — this repository has paid for the alternative twice.
 */
public final class Attachments {

    private Attachments() {}

    /**
     * The prompt block is capped, and the cap is small on purpose.
     *
     * <p>This is orientation, not evidence: it says what exists so the model can name the right
     * tool, and every token it takes is one the retrieved corpus does not get. {@code MemoryContext}
     * exists because an unbudgeted block once produced a 19 MB prompt; a fixed ceiling here is that
     * lesson applied before the mistake rather than after it.
     */
    private static final int PROMPT_PACK_LIMIT = 12;

    /**
     * One subject and everything attached to it.
     *
     * @param name the subject — a repository being followed, or whatever an extension named
     * @param followed whether this is a repository in the corpus, as opposed to a name only an
     *     extension mentions
     * @param supporters the extensions declaring support for it, in registration order
     */
    public record Pack(String name, boolean followed, List<Extension> supporters) {

        /** True when something is attached here — the only packs worth printing or prompting with. */
        public boolean supported() {
            return !supporters.isEmpty();
        }
    }

    /**
     * Every pack, supported ones first.
     *
     * <p>Never throws. An unreadable database means no followed repositories, not no answer: the
     * extensions are registered in a file of their own and are still worth showing.
     */
    public static List<Pack> tree() {
        return tree(ExtensionRegistry.all(), followedRepositories());
    }

    /**
     * The tree, given its two inputs instead of fetching them.
     *
     * <p>Taken as arguments so this is testable at all. Fetched, the answer depends on a registry
     * file in the caller's home directory and on a 727 MB database -- and a test that reached for
     * either would be a test that could delete one. That has happened in this repository once
     * already, which is why the rule is to assert where you are pointing rather than to trust
     * configuration; here there is nothing to point at.
     */
    static List<Pack> tree(List<Extension> registered, List<String> followed) {
        Map<String, List<Extension>> bySubject = new LinkedHashMap<>();
        for (String repo : followed) {
            bySubject.put(repo, new ArrayList<>());
        }

        for (Extension ext : registered) {
            String declared = ext.getSupports();
            if (declared == null || declared.isBlank()) {
                continue;
            }
            String subject = match(declared, followed);
            bySubject.computeIfAbsent(subject, k -> new ArrayList<>()).add(ext);
        }

        List<Pack> supported = new ArrayList<>();
        List<Pack> bare = new ArrayList<>();
        for (Map.Entry<String, List<Extension>> e : bySubject.entrySet()) {
            Pack pack = new Pack(e.getKey(), followed.contains(e.getKey()), List.copyOf(e.getValue()));
            (pack.supported() ? supported : bare).add(pack);
        }
        supported.addAll(bare);
        return supported;
    }

    /** Extensions that named no subject, and so apply to whatever you are working on. */
    public static List<Extension> unattached() {
        return unattached(ExtensionRegistry.all());
    }

    /** As above, over a given registry. */
    static List<Extension> unattached(List<Extension> registered) {
        List<Extension> out = new ArrayList<>();
        for (Extension ext : registered) {
            if (ext.getSupports() == null || ext.getSupports().isBlank()) {
                out.add(ext);
            }
        }
        return out;
    }

    /**
     * Resolves what a manifest wrote to what the corpus calls it.
     *
     * <p>Exact first, then the name after the slash, so a bench may say {@code logging-log4j2}
     * without also having to know whose fork it is being followed from. Nothing fuzzier than that:
     * a bench claiming {@code log4j} and silently attaching to {@code apache/logging-log4j2} would
     * be right here and wrong the first time somebody follows two repositories with a word in
     * common, and the failure would be a confident answer rather than an error.
     *
     * <p>Unmatched names are kept as written and reported as unfollowed. Refusing them would make
     * attachment depend on sync order — the manifest is correct, the repository just is not here
     * yet.
     */
    static String match(String declared, List<String> followed) {
        String want = declared.trim();
        for (String repo : followed) {
            if (repo.equalsIgnoreCase(want)) {
                return repo;
            }
        }
        for (String repo : followed) {
            int slash = repo.indexOf('/');
            String tail = slash < 0 ? repo : repo.substring(slash + 1);
            if (tail.equalsIgnoreCase(want)) {
                return repo;
            }
        }
        return want;
    }

    /**
     * What the assistant is told, or an empty string when there is nothing to say.
     *
     * <p>Phrased as fact, not instruction. Told <em>use the log4j bench</em>, a model will reach
     * for it on a Kafka issue to be helpful; told <em>this exists, it supports that</em>, it has
     * what it needs to decide and, more importantly, what it needs to decline.
     */
    public static String forPrompt() {
        return forPrompt(ExtensionRegistry.all(), followedRepositories());
    }

    /** As above, over a given registry and repository list. */
    static String forPrompt(List<Extension> registered, List<String> followed) {
        List<Pack> supported = new ArrayList<>();
        for (Pack pack : tree(registered, followed)) {
            if (pack.supported()) {
                supported.add(pack);
            }
        }
        List<Extension> global = unattached(registered);
        if (supported.isEmpty() && global.isEmpty()) {
            return "";
        }

        StringBuilder b = new StringBuilder("--- attached to this machine ---\n");
        int shown = 0;
        for (Pack pack : supported) {
            if (shown++ >= PROMPT_PACK_LIMIT) {
                b.append("(")
                        .append(supported.size() - PROMPT_PACK_LIMIT)
                        .append(" further pack(s) not listed here)\n");
                break;
            }
            b.append(pack.name()).append(":\n");
            for (Extension ext : pack.supporters()) {
                b.append("  - ").append(describe(ext)).append('\n');
            }
        }
        for (Extension ext : global) {
            b.append("- ").append(describe(ext)).append(" (no declared subject)\n");
        }
        b.append("These are attached, not instructions. Use one only where its subject matches.\n");
        return b.toString();
    }

    /** One extension, said the way a reader has to type it. */
    private static String describe(Extension ext) {
        String kind = ext.getKind() == null ? "extension" : ext.getKind().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(ext.getName()).append(" — ").append(kind);
        if (ext.getDescription() != null && !ext.getDescription().isBlank()) {
            b.append(": ").append(ext.getDescription());
        }
        if (ext.getVerbs() != null && !ext.getVerbs().isEmpty()) {
            b.append(" · verbs: ").append(String.join(", ", ext.getVerbs().keySet()));
        }
        return b.toString();
    }

    private static List<String> followedRepositories() {
        try {
            return SqliteStorage.loadMonitoredRepositories();
        } catch (Exception e) {
            // Deliberately quiet. Every caller here is decorating something else -- a listing, a
            // prompt -- and a database that cannot be read will be reported loudly by the command
            // that actually needed it.
            return List.of();
        }
    }
}
