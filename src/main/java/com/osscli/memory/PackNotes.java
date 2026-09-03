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
package com.osscli.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The writing a repository carries, and where a filed copy came from.
 *
 * <p>{@code oss memory file} takes a path and writes a dated copy. That is the right shape for a
 * one-off, and the wrong one for a repository you keep working in: the copy is an orphan the moment
 * the source changes, and nothing on either side knows. Measured on a real store — a repro
 * write-up filed in the morning, three commits added to it by the afternoon, and
 * {@code memory search} still answering from the morning's text with nothing to say it was stale.
 *
 * <p>So a tracked note keeps a pointer home. The filed copy carries the repo-relative path, the
 * commit it was taken at, and a digest of the source, which makes drift a thing that can be
 * <em>detected</em> rather than a thing you find out about. {@code doctor} reads those three fields
 * back.
 *
 * <p><b>Discovery is declared, not guessed.</b> A blind {@code **&#47;*.md} sweep of this project's
 * own pack takes fourteen files and hands back {@code CLAUDE.md}, {@code GAP-ANALYSIS.md} and a
 * vendored sample — operating instructions and other people's text, none of it the writing anyone
 * would search for. The defaults below name the folders that hold findings; {@code --all} is there
 * for a repository laid out differently, and still refuses the build directories.
 */
public final class PackNotes {

    /**
     * Where findings live, in the layouts seen so far.
     *
     * <p>Ordered most specific first. A repository that keeps its writing somewhere else passes
     * {@code --all} rather than editing this list.
     */
    static final List<String> DEFAULT_FOLDERS = List.of("repros", "docs", "findings", "notes", "reviews");

    /** Never walked: build output, version control, and the engine's own disposable cache. */
    static final List<String> SKIP_DIRS =
            List.of(".git", ".svn", "target", "build", "out", "node_modules", ".bench", "logs", ".idea", "dist");

    /**
     * Root-level markdown that describes the repository rather than recording anything.
     *
     * <p>Only at the root: {@code repros/issue-4279/README.md} is a finding and
     * {@code ./README.md} is a table of contents, and the difference between them is depth.
     */
    static final List<String> SKIP_ROOT_NAMES = List.of(
            "readme.md",
            "claude.md",
            "contributing.md",
            "license.md",
            "changelog.md",
            "install.md",
            "setup.md",
            "developing.md",
            "commands.md",
            "agents.md",
            "security.md",
            "code_of_conduct.md");

    private PackNotes() {}

    /** A source file worth filing, with everything needed to file it and to check it later. */
    public record Found(Path source, String relative, String title, String slug, String sha) {}

    /**
     * Whether this directory looks like something worth walking.
     *
     * <p>Both pack shapes the engine knows, plus a plain git repository — {@code track} is useful
     * in any repository that keeps write-ups, and refusing to run outside a pack would be a rule
     * with nothing behind it.
     */
    public static boolean looksTrackable(Path root) {
        return Files.isDirectory(root)
                && (Files.exists(root.resolve("pack.json"))
                        || Files.exists(root.resolve("pack.md"))
                        || Files.exists(root.resolve("pack.sh"))
                        || Files.isDirectory(root.resolve("packs"))
                        || Files.isDirectory(root.resolve(".git")));
    }

    /**
     * The markdown under {@code root} that counts as writing.
     *
     * @param all when true, every markdown file outside {@link #SKIP_DIRS} rather than only the
     *     folders in {@link #DEFAULT_FOLDERS}; root-level instruction files stay excluded either
     *     way, because {@code --all} means "look wider", not "file the README".
     */
    public static List<Path> discover(Path root, boolean all) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!all && root.relativize(dir).getNameCount() == 1 && !DEFAULT_FOLDERS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                    return FileVisitResult.CONTINUE;
                }
                boolean atRoot = file.getParent().equals(root);
                if (atRoot && SKIP_ROOT_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.CONTINUE;
                }
                if (atRoot && !all) {
                    return FileVisitResult.CONTINUE;
                }
                out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(Path::compareTo);
        return out;
    }

    /** Read one source file into everything needed to file it. */
    public static Found examine(Path root, Path source) throws IOException {
        String body = Files.readString(source, StandardCharsets.UTF_8);
        String title = titleOf(body, source);
        return new Found(source, relative(root, source), title, slug(title), sha256(body));
    }

    static String relative(Path root, Path source) {
        Path r = root.toAbsolutePath().normalize();
        Path s = source.toAbsolutePath().normalize();
        // Stored in the note's frontmatter and compared by doctor on every later run, so it
        // cannot be spelled one way on Windows and another everywhere else.
        return com.osscli.AppPaths.slashes(s.startsWith(r) ? r.relativize(s).toString() : s.toString());
    }

    /**
     * The note's own title, which is what it should be filed under.
     *
     * <p>Naming the copy after the file gave every repro the same name: eight write-ups, eight
     * {@code README.md}, and a store holding {@code 2026-09-01-readme.md} where a reader wanted
     * {@code log4j-issue-4279-reproduction}. The first heading is what the author already chose to
     * call it; the parent directory is the fallback, because that is where the identity sits when
     * a file is called README.
     */
    static String titleOf(String body, Path source) {
        for (String line : body.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("# ")) {
                return t.substring(2).strip();
            }
        }
        String name = source.getFileName().toString().replaceFirst("(?i)\\.md$", "");
        if (name.equalsIgnoreCase("readme") && source.getParent() != null) {
            return source.getParent().getFileName().toString();
        }
        return name;
    }

    /** A stable, filesystem-safe name. Stable is the point: filing twice must overwrite, not accumulate. */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        if (s.length() > 80) {
            s = s.substring(0, 80).replaceAll("-+$", "");
        }
        return s.isBlank() ? "note" : s;
    }

    static String sha256(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * The note, carrying where it came from.
     *
     * <p>Merged into the note's own front matter when it has one. A second {@code ---} block
     * stacked on top of an existing one is not front matter any more — every parser downstream,
     * this project's included, reads the first block and treats the second as body text.
     */
    public static String withProvenance(String body, Map<String, String> fields) {
        Map<String, String> merged = new LinkedHashMap<>();
        String rest = body;
        if (body.startsWith("---")) {
            int end = body.indexOf("\n---", 3);
            if (end >= 0) {
                String block = body.substring(body.indexOf('\n') + 1, end);
                for (String line : block.split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                        merged.put(
                                line.substring(0, colon).strip(),
                                line.substring(colon + 1).strip());
                    }
                }
                int after = body.indexOf('\n', end + 1);
                rest = after < 0 ? "" : body.substring(after + 1);
            }
        }
        merged.putAll(fields);
        StringBuilder sb = new StringBuilder("---\n");
        merged.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        sb.append("---\n\n").append(rest.stripLeading());
        return sb.toString();
    }

    /** The front matter of a filed note, or an empty map when it has none. */
    public static Map<String, String> frontMatter(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!body.startsWith("---")) {
            return out;
        }
        int end = body.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        String block = body.substring(body.indexOf('\n') + 1, end);
        for (String line : block.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(" ") && !line.startsWith("\t")) {
                out.put(
                        line.substring(0, colon).strip(),
                        line.substring(colon + 1).strip());
            }
        }
        return out;
    }
}
