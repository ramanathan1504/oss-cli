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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.osscli.AppPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The set of extensions this machine has registered, persisted as JSON.
 *
 * <p>Deliberately a plain file under {@link AppPaths#BASE_DIR} rather than a table in the SQLite
 * database. The registry has to be readable and editable when the database is mid-migration or
 * broken -- {@code doctor} is exactly the moment you want to see what is wired up -- and it is a
 * handful of records that a person may reasonably want to edit by hand.
 *
 * <p>Registration stores a <em>snapshot</em> of the manifest, plus the root it was read from. The
 * alternative, re-reading each manifest on every command, was rejected: a registry that silently
 * changes shape because a checkout moved to another branch makes the failure appear at the call
 * site, far from the cause. {@code refresh} makes the update explicit.
 */
public class ExtensionRegistry {

    /** {@code ~/.oss-cli/extensions.json} unless OSS_CLI_HOME relocates the base directory. */
    public static final Path REGISTRY_FILE = AppPaths.BASE_DIR.resolve("extensions.json");

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ExtensionRegistry() {}

    /** Every registered extension, in registration order. Empty when nothing is registered yet. */
    public static List<Extension> all() {
        if (!Files.exists(REGISTRY_FILE)) {
            return new ArrayList<>();
        }
        try {
            String body = Files.readString(REGISTRY_FILE);
            if (body.isBlank()) {
                return new ArrayList<>();
            }
            return MAPPER.readValue(body, new TypeReference<List<Extension>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + REGISTRY_FILE, e);
        }
    }

    public static List<Extension> ofKind(Extension.Kind kind) {
        return all().stream().filter(e -> e.kind() == kind).toList();
    }

    /** Case-insensitive, because a registry of five entries is not worth a spelling argument. */
    public static Optional<Extension> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return all().stream()
                .filter(e -> e.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /**
     * Resolve which extension a command means.
     *
     * <p>With a name, that name. Without one, the only extension of that kind -- and if there are
     * several, an error listing them rather than a silent pick, because "the first one registered"
     * is not a rule anyone would predict.
     */
    public static Extension resolve(Extension.Kind kind, String name) {
        if (name != null && !name.isBlank()) {
            Extension found = byName(name)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "no extension named \"" + name + "\" -- see: oss-cli ext list"));
            if (found.kind() != kind) {
                throw new IllegalArgumentException("\"" + found.getName() + "\" is a "
                        + found.kind().lower() + " extension, not a " + kind.lower());
            }
            return found;
        }
        List<Extension> candidates = ofKind(kind);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "no " + kind.lower() + " extension is registered -- add one with: oss-cli ext add <path-to-repo>");
        }
        if (candidates.size() > 1) {
            String names = String.join(
                    ", ", candidates.stream().map(Extension::getName).toList());
            throw new IllegalArgumentException("several " + kind.lower() + " extensions are registered (" + names
                    + ") -- name the one you mean with --" + kind.lower() + " <name>");
        }
        return candidates.get(0);
    }

    /**
     * Read and validate a manifest from a repository root.
     *
     * @param repoRoot directory containing {@link Extension#MANIFEST}, or the manifest file itself
     */
    public static Extension readManifest(Path repoRoot) {
        Path dir = repoRoot.toAbsolutePath().normalize();
        Path manifest = Files.isDirectory(dir) ? dir.resolve(Extension.MANIFEST) : dir;
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("no " + Extension.MANIFEST + " at " + dir);
        }
        Extension ext;
        try {
            ext = MAPPER.readValue(Files.readString(manifest), Extension.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Jackson's own message names the line and column; a manifest author needs that far
            // more than a stack trace, so it is surfaced rather than wrapped away.
            throw new IllegalArgumentException(manifest + " is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read " + manifest + ": " + e.getMessage(), e);
        }
        ext.setRoot(manifest.getParent().toString());
        ext.setManifestSha(shaOf(manifest));
        ext.validate();
        if (!Files.isExecutable(ext.execPath())) {
            throw new IllegalArgumentException("\"exec\": " + ext.execPath()
                    + " does not exist or is not executable (chmod +x it, or fix the path)");
        }
        return ext;
    }

    /**
     * SHA-256 of a manifest file, or null when it cannot be read.
     *
     * <p>A hash rather than a modification time: a checkout, a stash pop or a `touch` all move the
     * mtime without changing a byte, and a drift warning that fires when nothing changed is one
     * people learn to ignore -- which is worse than not warning at all.
     */
    static String shaOf(Path manifest) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifest));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the manifest on disk has changed since this was registered.
     *
     * <p>A missing manifest is NOT drift -- that is a broken registration, which
     * {@code isReachable} already reports, and calling it "stale" would send someone to
     * {@code refresh} for a problem refreshing cannot fix. An entry registered before this field
     * existed has no recorded hash and is reported as current, so an upgrade does not declare
     * everything stale on first run.
     */
    public static boolean isStale(Extension ext) {
        if (ext.getManifestSha() == null) {
            return false;
        }
        Path manifest = ext.manifestPath();
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        String now = shaOf(manifest);
        return now != null && !now.equals(ext.getManifestSha());
    }

    /** Register, replacing any existing entry of the same name. Returns true when it replaced one. */
    public static boolean add(Extension ext) {
        List<Extension> current = all();
        boolean replaced = current.removeIf(e -> e.getName().equalsIgnoreCase(ext.getName()));
        current.add(ext);
        write(current);
        return replaced;
    }

    public static boolean remove(String name) {
        List<Extension> current = all();
        boolean removed = current.removeIf(e -> e.getName().equalsIgnoreCase(name.trim()));
        if (removed) {
            write(current);
        }
        return removed;
    }

    /** Re-read a registered extension's manifest from disk, keeping its position in the list. */
    public static Extension refresh(String name) {
        Extension existing =
                byName(name).orElseThrow(() -> new IllegalArgumentException("no extension named \"" + name + "\""));
        Extension fresh = readManifest(existing.rootPath());
        List<Extension> current = all();
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).getName().equalsIgnoreCase(name.trim())) {
                current.set(i, fresh);
                break;
            }
        }
        write(current);
        return fresh;
    }

    private static void write(List<Extension> extensions) {
        try {
            Files.createDirectories(REGISTRY_FILE.getParent());
            // Write-then-move, so an interrupted write cannot leave a half-written registry that
            // every later command fails to parse.
            Path tmp = REGISTRY_FILE.resolveSibling(REGISTRY_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(extensions));
            Files.move(tmp, REGISTRY_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + REGISTRY_FILE, e);
        }
    }

    /** Summary for {@code doctor}: how many of each kind are registered. */
    public static Map<String, Integer> countsByKind() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Extension.Kind k : Extension.Kind.values()) {
            counts.put(k.name().toLowerCase(Locale.ROOT), ofKind(k).size());
        }
        return counts;
    }
}
