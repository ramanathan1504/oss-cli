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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One registered capability that OSS-CLI can call out to.
 *
 * <p>OSS-CLI knows things. It deliberately owns no clone, runs no build, and is specific to no
 * project -- that boundary is the reason it works against any repository in any language. But the
 * two questions it cannot answer alone are "does this actually run?" and "have I already worked
 * this out once?". Those belong to something that executes and something that remembers.
 *
 * <p>An extension is how such a thing announces itself. It is declared by a manifest file at the
 * root of any repository, so the extension does not have to be Java, does not have to be built, and
 * does not have to know OSS-CLI exists beyond writing that file:
 *
 * <pre>{@code
 * {
 *   "name": "log4j",
 *   "kind": "bench",
 *   "description": "Log4j across a version x config x app matrix",
 *   "exec": "./bench",
 *   "verbs": { "list": "list", "run": "run", "matrix": "matrix", "review": "review" },
 *   "axes": ["app", "version", "config"]
 * }
 * }</pre>
 *
 * <p>Shelling out rather than loading a plugin is the whole point. A Java SPI would have forced
 * every bench author to write Java, and the first bench this was built for is 936 lines of bash
 * that already worked. The contract is therefore a file and a process, which any language can
 * satisfy.
 *
 * <p><b>{@code verbs} maps a portable name to whatever that tool actually calls it.</b> OSS-CLI
 * speaks {@code run}; one bench spells that {@code run}, another spells it {@code exec}. Without the
 * indirection every bench would have to rename its own commands to join in, which is precisely the
 * kind of tax that stops people writing one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Extension {

    /** The manifest filename looked for at the root of a candidate repository. */
    public static final String MANIFEST = "oss-ext.json";

    /**
     * What an extension is for.
     *
     * <p>Two kinds, because there are two questions OSS-CLI cannot answer alone. Deliberately not
     * an open string: a third kind should be a considered addition, not a typo that silently
     * registers something nothing will ever dispatch to.
     */
    public enum Kind {
        /** Runs something real -- a matrix, a build, a repro. Answers "does it actually work?" */
        RUNNER("bench"),
        /** Remembers -- an archive that files and retrieves. Answers "have I worked this out?" */
        MEMORY("kb");

        /**
         * What this kind used to be called.
         *
         * <p>"bench" and "kb" were the author's words for these, and they read as jargon to anyone
         * who did not invent them: a newcomer has to be told what a "kb" is before they can decide
         * whether they want one. "runner" and "memory" say what the thing does.
         *
         * <p>The old names keep working, permanently and silently. Manifests already written are
         * files in other people's repositories -- breaking them to tidy a word would be charging
         * them for a rename they did not ask for.
         */
        private final String legacy;

        Kind(String legacy) {
            this.legacy = legacy;
        }

        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("manifest is missing \"kind\" (expected: runner, memory)");
            }
            String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (Kind k : values()) {
                if (k.lower().equals(v) || k.legacy.equals(v)) {
                    return k;
                }
            }
            throw new IllegalArgumentException(
                    "unknown kind \"" + raw + "\" (expected: runner, memory — or the older bench, kb)");
        }

        public String lower() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private String name;
    private String kind;
    private String description;
    private String exec;
    private Map<String, String> verbs = new LinkedHashMap<>();
    private List<String> axes = List.of();

    /**
     * The pack this extension supports, or null when it supports everything equally.
     *
     * <p>A bench is built for a subject. The one this was written against executes Log4j
     * configurations against real Log4j; pointing it at a Kafka issue would produce a confident
     * answer about nothing. Until now nothing recorded that, so every attached runner looked
     * equally applicable to every repository in the corpus, and choosing between them was the
     * reader's job every single time.
     *
     * <p>Deliberately a free string rather than a checked reference. The pack it names is usually a
     * repository being followed -- {@code owner/name} -- and then OSS-CLI can say so, but
     * an author may equally name a subject that spans several, and a manifest that fails to load
     * because a repository has not been synced <em>yet</em> would make attaching things depend on
     * the order they were done in. Where it matches nothing, {@link Attachments} says that out loud
     * rather than dropping the extension.
     */
    private String supports;

    /**
     * Verbs that write somewhere outward-facing, and so must be confirmed before they run.
     *
     * <p>Declared by the extension because only the extension knows. OSS-CLI cannot tell from the
     * outside that {@code followup} is read-only while {@code followup --comment} posts, so guessing
     * from a verb name would be both wrong and quietly reassuring. Anything listed here is refused
     * unless the operator named this repository with {@code --approve-upstream} and confirms at the
     * terminal; anything not listed runs freely, which is why the default is the empty list and an
     * author has to opt a verb in deliberately.
     */
    private List<String> writes = List.of();

    /**
     * Where those writes land, as {@code owner/name} — compared for equality against
     * {@code --approve-upstream}, so it must be the bare repository and nothing else. A sentence
     * here (it once held one) can never match an approval, silently making the verb unusable.
     */
    private String writesTo;

    /**
     * Where this extension keeps the data it owns, if it keeps any.
     *
     * <p>Declared rather than discovered, because an archive lives wherever its owner put it and
     * guessing is how a backup quietly misses the thing it existed to protect. Supports {@code ~}
     * so a manifest stays portable between machines.
     */
    private String archive;

    /**
     * SHA-256 of the manifest exactly as it was when this was registered.
     *
     * <p>The registry stores a snapshot rather than re-reading every manifest on every command, so
     * that a checkout switching branches cannot silently change what is registered. The cost is that
     * an edited manifest leaves the snapshot stale -- and that failed silently once, in the worst
     * possible way: {@code writesTo} was corrected on disk while the registry kept the old value, so
     * an approval naming the right repository could never match it and the verb was simply
     * unusable, with nothing saying why.
     *
     * <p>So drift is now detectable. The snapshot is still authoritative -- it just can no longer be
     * used without knowing whether it is current.
     */
    private String manifestSha;

    /**
     * Absolute path to the repository this was registered from.
     *
     * <p>Written by the registry, never read from the manifest. A manifest that could name its own
     * root would let a copied file point at a directory it does not live in.
     */
    private String root;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExec() {
        return exec;
    }

    public void setExec(String exec) {
        this.exec = exec;
    }

    public Map<String, String> getVerbs() {
        return verbs;
    }

    public void setVerbs(Map<String, String> verbs) {
        this.verbs = verbs == null ? new LinkedHashMap<>() : verbs;
    }

    public String getSupports() {
        return supports;
    }

    public void setSupports(String supports) {
        this.supports = supports;
    }

    public List<String> getAxes() {
        return axes;
    }

    public void setAxes(List<String> axes) {
        this.axes = axes == null ? List.of() : axes;
    }

    public List<String> getWrites() {
        return writes;
    }

    public void setWrites(List<String> writes) {
        this.writes = writes == null ? List.of() : writes;
    }

    public String getArchive() {
        return archive;
    }

    public void setArchive(String archive) {
        this.archive = archive;
    }

    /** The declared data directory, expanded, or null when it declares none. */
    public java.nio.file.Path archivePath() {
        if (archive == null || archive.isBlank()) {
            return null;
        }
        String a = archive.trim();
        if (a.startsWith("~")) {
            a = System.getProperty("user.home") + a.substring(1);
        }
        return java.nio.file.Path.of(a);
    }

    public String getWritesTo() {
        return writesTo;
    }

    public void setWritesTo(String writesTo) {
        this.writesTo = writesTo;
    }

    /** Whether dispatching this portable verb would write somewhere outward-facing. */
    public boolean writesOutward(String portableVerb) {
        return writes.stream().anyMatch(w -> w.equalsIgnoreCase(portableVerb));
    }

    /** Human description of where a write lands; falls back to naming the extension itself. */
    public String writeTarget() {
        return (writesTo == null || writesTo.isBlank()) ? ("whatever " + name + " posts to") : writesTo;
    }

    public String getManifestSha() {
        return manifestSha;
    }

    public void setManifestSha(String manifestSha) {
        this.manifestSha = manifestSha;
    }

    /** The manifest file this was registered from, wherever the root now is. */
    public Path manifestPath() {
        return rootPath().resolve(MANIFEST);
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Kind kind() {
        return Kind.parse(kind);
    }

    public Path rootPath() {
        return Path.of(root);
    }

    /** The executable, resolved against the extension's own root rather than the caller's cwd. */
    public Path execPath() {
        Path p = Path.of(exec);
        return p.isAbsolute() ? p : rootPath().resolve(p).normalize();
    }

    /**
     * Translate a portable verb into the argument this tool actually takes.
     *
     * @return null when this extension does not offer that verb -- callers report it rather than
     *     guessing, because passing an unknown verb through would run the tool's default command,
     *     and for a bench the default is often "do everything".
     */
    public String resolveVerb(String portable) {
        return verbs.get(portable);
    }

    /**
     * Fail loudly on anything that would only surface later as a confusing exec error.
     *
     * @throws IllegalArgumentException with a message naming the field, for a manifest author
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("manifest is missing \"name\"");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    "\"name\" must be a plain identifier (letters, digits, . _ -): got \"" + name + "\"");
        }
        kind(); // throws with the expected-values message
        if (exec == null || exec.isBlank()) {
            throw new IllegalArgumentException("manifest is missing \"exec\"");
        }
        if (verbs.isEmpty()) {
            throw new IllegalArgumentException("manifest declares no \"verbs\", so nothing could ever be dispatched");
        }
    }
}
