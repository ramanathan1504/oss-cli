package com.osscli.memory;

import com.osscli.AppPaths;
import com.osscli.retrieval.Corpus;
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
import java.util.Map;
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

    /**
     * What the built-in store can answer.
     *
     * <p>Public because a caller has to be able to <em>show</em> this list, not only test against
     * it: {@code oss memory} with no verb has nothing else to offer when no archive is attached.
     * Stated once, so the switch, the error text and the listing cannot disagree.
     */
    public static final List<String> VERBS = List.of("file", "search", "index", "map", "coverage", "harvest");

    private BuiltinMemory() {}

    /**
     * Pull your own public work on GitHub into the archive, as plain markdown.
     *
     * <p>This is what makes "install oss-cli and that is it" true for the half of the corpus that
     * is <em>yours</em>. A sibling repository did it in Python against DEVONthink; the notes it
     * wrote were always ordinary markdown in a folder, and the DEVONthink half was an index on top.
     * So the built-in writes the same files with no such dependency, and an archive extension still
     * takes over whenever one is attached.
     *
     * <p>Wider than {@code sync --me}, deliberately. That query is
     * {@code author:<you> type:pr is:merged} — the pull requests you wrote and got landed, which is
     * a fraction of the record. Most of what you learn happens on somebody else's change: the
     * comment you left, the review you gave, the issue you triaged. {@code involves:} catches all of
     * it.
     *
     * <p>One file per item, named so a second run rewrites rather than duplicates — the same rule
     * the review notes learned after six copies of one review accumulated in a real archive.
     */
    private static int harvest(List<String> args) throws IOException {
        String user = args.isEmpty() ? configuredUser() : args.get(0).strip();
        if (user == null || user.isBlank()) {
            System.err.println("error  whose work? oss memory harvest <github-username>");
            System.err.println("       or set one once: oss setup  (github.username)");
            return 2;
        }

        Path into = DIR.resolve("harvest");
        Files.createDirectories(into);

        List<com.osscli.model.Issue> found;
        try {
            // Everything you touched, not only what you authored: involves: covers author,
            // assignee, mentions and commenter in one query.
            found = new com.osscli.github.GitHubClient().searchIssuesAndPrs("involves:" + user + " sort:updated-desc");
        } catch (Exception e) {
            System.err.println("error  could not reach GitHub: " + e.getMessage());
            System.err.println("       harvest is the one verb here that needs the network.");
            return 1;
        }

        int written = 0;
        for (com.osscli.model.Issue issue : found) {
            Path note = into.resolve(harvestName(issue));
            Files.writeString(note, harvestNote(issue), StandardCharsets.UTF_8);
            written++;
        }

        System.out.printf("  harvested %d item(s) for %s into %s%n", written, user, into);
        System.out.println("  oss memory index      reads them into the corpus");
        return 0;
    }

    /**
     * A stable file name for one harvested item.
     *
     * <p>Stable because a second harvest must rewrite the note it already has. Timestamping instead
     * is how one review ended up in an archive six times, each copy embedded and each competing to
     * answer the same question.
     */
    static String harvestName(com.osscli.model.Issue issue) {
        return "gh-" + repositoryOf(issue).replace('/', '-') + "-" + issue.number() + ".md";
    }

    /**
     * Which repository an item belongs to, read from its own URL.
     *
     * <p>The search API returns the item, not the repository it came from — the only place the
     * owner and name appear is the {@code html_url}. Guessing from the configured default would put
     * somebody else's issue under your project's name.
     */
    static String repositoryOf(com.osscli.model.Issue issue) {
        String url = issue.html_url();
        if (url == null) {
            return "unknown";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("github\\.com/([^/]+/[^/]+)/").matcher(url);
        return m.find() ? m.group(1) : "unknown";
    }

    /** One harvested item, as the markdown a person would have written about it. */
    static String harvestNote(com.osscli.model.Issue issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ")
                .append(repositoryOf(issue))
                .append(" #")
                .append(issue.number())
                .append('\n');
        sb.append("## ")
                .append(issue.title() == null ? "(no title)" : issue.title())
                .append("\n\n");
        sb.append("- state: ").append(issue.state()).append('\n');
        if (issue.labels() != null && !issue.labels().isEmpty()) {
            sb.append("- labels: ")
                    .append(issue.labels().stream()
                            .map(com.osscli.model.Label::name)
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append('\n');
        }
        if (issue.html_url() != null) {
            sb.append("- link: ").append(issue.html_url()).append('\n');
        }
        sb.append('\n');
        if (issue.body() != null && !issue.body().isBlank()) {
            sb.append(issue.body().strip()).append('\n');
        }
        return sb.toString();
    }

    /** The username configured once, so harvest does not have to be told every time. */
    private static String configuredUser() {
        try {
            return com.osscli.storage.SqliteStorage.loadConfig("github.username");
        } catch (Exception e) {
            return null;
        }
    }

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
                case "map":
                    return map();
                case "coverage":
                    return coverage();
                case "harvest":
                    return harvest(args);
                default:
                    System.err.println("error  built-in memory has no verb \"" + verb + "\"");
                    System.err.println("       it knows: " + String.join(", ", VERBS));
                    System.err.println("       A different archive is a few lines in kb.json, not a checkout.");
                    return 2;
            }
        } catch (IOException e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    /** The verbs the built-in store can answer, so a caller can ask before falling back to it. */
    public static boolean supports(String verb) {
        return VERBS.contains(verb);
    }

    // ---------------------------------------------------------------------- map ---

    /**
     * Which notes touch which topic.
     *
     * <p>Topics are declared rather than inferred. A model deciding what a note is "about" turns a
     * count into an opinion and moves the number when nothing was written; a list of terms in
     * {@code kb.json} can be read, argued with and corrected.
     */
    private static int map() throws IOException {
        KnowledgePack pack = KnowledgePack.load();
        if (pack.topics().isEmpty()) {
            System.out.println("  no topics declared, so there is nothing to group by.");
            System.out.println();
            System.out.println("  kb.json:  {\"topics\": {\"log4j\": [\"log4j\", \"appender\"]}}");
            System.out.println("  " + AppPaths.BASE_DIR.resolve("kb.json"));
            return 0;
        }
        Map<String, List<String>> byTopic = Coverage.map(pack.archive(), pack.topics());
        System.out.println("  " + pack.archive());
        for (Map.Entry<String, List<String>> e : byTopic.entrySet()) {
            System.out.printf("%n  %-20s %d note(s)%n", e.getKey(), e.getValue().size());
            e.getValue().stream().limit(5).forEach(n -> System.out.println("      " + n));
            if (e.getValue().size() > 5) {
                System.out.println("      … and " + (e.getValue().size() - 5) + " more");
            }
        }
        return 0;
    }

    // ----------------------------------------------------------------- coverage ---

    /**
     * What the notes cover, against what the technology documents.
     *
     * <p>The yardstick has to come from outside the notes. Scoring an archive against itself can
     * only report what is in it, so a base with nothing on a subject reports full marks on the
     * subjects it does have and calls that coverage.
     */
    private static int coverage() throws IOException {
        KnowledgePack pack = KnowledgePack.load();
        if (pack.yardsticks().isEmpty()) {
            System.out.println("  no yardstick declared, so there is nothing to measure against.");
            System.out.println();
            System.out.println("  A yardstick is what a technology's own manual documents:");
            System.out.println("  kb.json:  {\"yardsticks\": {\"log4j\": [\"Appenders\", \"Layouts\", \"Lookups\"]}}");
            System.out.println("  " + AppPaths.BASE_DIR.resolve("kb.json"));
            return 0;
        }
        for (Map.Entry<String, List<String>> tech : pack.yardsticks().entrySet()) {
            List<Coverage.Area> areas = Coverage.score(pack.archive(), tech.getValue());
            long covered =
                    areas.stream().filter(a -> a.grade().equals("covered")).count();
            long thin = areas.stream().filter(a -> a.grade().equals("thin")).count();
            long nothing =
                    areas.stream().filter(a -> a.grade().equals("nothing")).count();
            System.out.printf(
                    "%n  %s — %d of %d covered · %d thin · %d nothing%n",
                    tech.getKey(), covered, areas.size(), thin, nothing);
            for (Coverage.Area a : areas) {
                System.out.printf(
                        "    %s  %-28s %3d note(s) %5d mention(s)  %s%n",
                        a.mark(), a.name(), a.notes(), a.mentions(), a.strongest());
            }
        }
        return 0;
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

        // With the model present this asks the same corpus 'pick' asks, rather than keeping a
        // private opinion about the same notes. Two commands ranking one set of notes by two
        // different methods is how they come to disagree about the user's own writing -- and it
        // read as the model doing nothing, since 'memory search' said "shared terms" no matter
        // what was installed.
        if (com.osscli.retrieval.Embeddings.isReady()) {
            Integer done = searchByMeaning(query, notes.size());
            if (done != null) {
                return done;
            }
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

    /**
     * Rank by meaning, through the shared corpus.
     *
     * <p>Returns null when the corpus cannot answer -- no model after all, or nothing of ours in it --
     * so the caller falls through to term search rather than reporting an empty result. An empty
     * answer and a missing capability look identical to the person reading them, and only one of
     * them means "there is nothing here".
     */
    private static Integer searchByMeaning(String query, int noteCount) {
        try {
            Corpus corpus = Corpus.load(m -> System.out.println("  " + m));
            if (!corpus.semantic()) {
                return null;
            }
            // Notes only. The corpus also holds review write-ups, and 'memory search' is asked about
            // what you filed -- widening it here would answer a question nobody asked.
            List<Corpus.Hit> hits = corpus.search(query, 8).stream()
                    .filter(h -> "note".equals(h.kind()))
                    .toList();
            if (hits.isEmpty()) {
                return null;
            }
            System.out.println("  " + hits.size() + " of " + noteCount + " note(s), by meaning");
            System.out.println();
            for (Corpus.Hit h : hits) {
                String file = h.id().startsWith("note:") ? h.id().substring(5) : h.id();
                System.out.printf("  %.2f  %s%n", h.score(), file);
                if (!h.title().isBlank()) {
                    System.out.println("        " + h.title());
                }
            }
            return 0;
        } catch (Exception e) {
            // Ranking by meaning is the better answer, not the only one.
            return null;
        }
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
