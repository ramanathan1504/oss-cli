package com.osscli.memory;

import com.osscli.AppPaths;
import com.osscli.retrieval.PassageSplitter;
import com.osscli.retrieval.TextIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A memory that is always there.
 *
 * <p>Remembering used to require attaching a memory extension first, so a fresh install could not
 * keep a single note until the user had cloned a second repository and wired it up. That is the
 * wrong order: the thing that makes the tool worth using should not be the thing you have to earn.
 *
 * <p>So this is the floor, not the ceiling. Markdown files in a directory, indexed by term. No
 * database, no server, no account, and no model — the archive extension still wins whenever one is
 * registered, and this only answers when nothing else does.
 *
 * <p>Search is deliberately term-based rather than semantic. A local model makes it better and is
 * used when present, but the promise is that someone with no AI at all still gets useful answers
 * out of their own writing. Nothing here degrades to nothing.
 */
public final class BuiltinMemory {

    /** Beside the database and the review ledger, for the same reason: it outlives every checkout. */
    public static final Path DIR = AppPaths.BASE_DIR.resolve("memory");

    private BuiltinMemory() {}

    /** Dispatch a verb. Returns a process exit code. */
    public static int run(String verb, List<String> args) {
        try {
            switch (verb == null ? "" : verb) {
                case "file":
                    return file(args);
                case "search":
                    return search(args);
                case "index":
                    return index();
                default:
                    System.err.println("error  built-in memory has no verb \"" + verb + "\"");
                    System.err.println("       it knows: file, search, index");
                    System.err.println("       Attach a memory extension for more: oss ext add <path>");
                    return 2;
            }
        } catch (IOException e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    // --------------------------------------------------------------------- file ---

    private static int file(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("error  which file? oss memory file <path.md> [more.md …]");
            return 2;
        }
        Files.createDirectories(DIR);
        int filed = 0;
        for (String a : args) {
            Path src = Path.of(a);
            if (!Files.isRegularFile(src)) {
                System.err.println("  skipped (not a file)  " + a);
                continue;
            }
            String body = Files.readString(src);
            // Dated and slugged, so the directory sorts chronologically and two notes with the same
            // title on different days do not collide. Filing the same file twice overwrites rather
            // than accumulating near-duplicates nobody will ever reconcile.
            String slug = slug(src.getFileName().toString());
            Path dst = DIR.resolve(LocalDate.now(ZoneOffset.UTC) + "-" + slug + ".md");
            Files.writeString(dst, body, StandardCharsets.UTF_8);
            System.out.println("  filed  " + dst.getFileName());
            filed++;
        }
        if (filed > 0) {
            System.out.println();
            System.out.println("  " + count() + " note(s) in " + DIR);
            System.out.println("  oss memory search \"<terms>\"");
        }
        return filed > 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------- search ---

    private static int search(List<String> args) throws IOException {
        String query = String.join(" ", args).trim();
        if (query.isEmpty()) {
            System.err.println("error  search for what? oss memory search \"<terms>\"");
            return 2;
        }
        List<Note> notes = load();
        if (notes.isEmpty()) {
            System.out.println("  nothing filed yet — oss memory file <path.md>");
            return 0;
        }

        TextIndex ix = new TextIndex();
        for (Note n : notes) {
            // Indexed by passage rather than by whole file: a long note matching one paragraph
            // should surface that paragraph, not rank badly because the rest is about something
            // else. PassageSplitter is the same one the retrieval path uses.
            List<String> passages = PassageSplitter.split(n.body);
            for (int i = 0; i < passages.size(); i++) {
                ix.add(n.name + "#" + i, n.title, passages.get(i));
            }
        }
        ix.build();

        List<TextIndex.Hit> hits = ix.search(query, 8);
        if (hits.isEmpty()) {
            System.out.println("  no note mentions those terms (" + notes.size() + " searched)");
            return 0;
        }
        System.out.println("  " + hits.size() + " of " + notes.size() + " note(s), by shared terms");
        System.out.println();
        for (TextIndex.Hit h : hits) {
            String file = h.id().substring(0, h.id().lastIndexOf('#'));
            System.out.printf("  %.2f  %s%n", h.score(), file);
            if (!h.title().isBlank()) {
                System.out.println("        " + h.title());
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------- index ---

    private static int index() throws IOException {
        List<Note> notes = load();
        int passages = 0;
        for (Note n : notes) {
            passages += PassageSplitter.split(n.body).size();
        }
        System.out.println("  " + notes.size() + " note(s), " + passages + " passage(s) in " + DIR);
        // Stated rather than implied: the index is built per search, so there is nothing to rebuild
        // and nothing that can go stale. Saying so stops anyone hunting for a refresh command.
        System.out.println("  The index is built as you search, so there is nothing to keep current.");
        return 0;
    }

    // --------------------------------------------------------------------- util ---

    private static final class Note {
        String name = "";
        String title = "";
        String body = "";
    }

    private static List<Note> load() throws IOException {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return out;
        }
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                Note n = new Note();
                n.name = p.getFileName().toString();
                n.body = Files.readString(p);
                n.title = title(n.body, n.name);
                out.add(n);
            }
        }
        return out;
    }

    /** The first heading, the frontmatter title, or the filename — in that order of preference. */
    private static String title(String body, String fallback) {
        for (String line : body.split("\n", 40)) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
            if (t.toLowerCase(Locale.ROOT).startsWith("title:")) {
                return t.substring(6).trim();
            }
        }
        return fallback.replaceAll("\\.md$", "").replace('-', ' ');
    }

    private static String slug(String filename) {
        String base = filename.replaceAll("\\.[A-Za-z0-9]+$", "").toLowerCase(Locale.ROOT);
        String s = base.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "note" : s;
    }

    private static long count() throws IOException {
        if (!Files.isDirectory(DIR)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".md")).count();
        }
    }
}
