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
import java.util.LinkedHashMap;
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
    public static final List<String> VERBS =
            List.of("file", "search", "index", "map", "coverage", "harvest", "digest", "import");

    private BuiltinMemory() {}

    /**
     * Read a data export from a chat product into the archive.
     *
     * <p>The half of the record that has no local files. {@code harvest} fetches what GitHub holds;
     * ChatGPT, Claude.ai and AI Studio keep nothing on this machine, so the only route in is the
     * export their owner downloads. This takes that folder and turns it into ordinary markdown
     * beside everything else.
     *
     * <p>This sentence used to say {@code harvest} could also collect the local Claude Code, codex
     * and gemini session files. It cannot -- {@code harvest} queries the GitHub search API and
     * nothing else. Those transcripts are on disk and worth reading, but until something actually
     * reads them, {@code import} pointed at the session folder is the honest answer.
     *
     * <p><b>Secrets are redacted, not dropped.</b> A real export of 111 conversations carried AWS
     * keys, GitHub tokens, a bearer token and {@code password=} strings in seven of them -- and the
     * troubleshooting around each was worth keeping. So the text survives with {@code [REDACTED:...]}
     * where the secret was, and the original download is never modified.
     *
     * <p>Anything that is not text is skipped and counted rather than silently ignored: an export is
     * mostly screenshots by volume, and a reader told "412 files, 68 imported" knows the rest were
     * images, where silence would read as loss.
     */
    private static int importExport(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("error  which export? oss memory import <folder>");
            System.err.println("       the folder a chat product's data export unpacked into");
            return 2;
        }
        Path from = Path.of(args.get(0).strip());
        if (!Files.isDirectory(from)) {
            System.err.println("error  not a folder: " + from);
            return 2;
        }

        Path into = DIR.resolve("imported");
        Files.createDirectories(into);

        int imported = 0;
        int skipped = 0;
        int redacted = 0;
        int unreadable = 0;
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!isText(file)) {
                    skipped++;
                    continue;
                }
                String raw;
                try {
                    raw = Files.readString(file, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    // Told apart from "not text" on purpose. A cloud-backed folder answers a read
                    // with a timeout rather than bytes: the file is a placeholder whose contents
                    // are still in the cloud. Counting that as unreadable and moving on reports
                    // "nothing here" for an export that is entirely there -- 638 files, zero
                    // imported, on a synced folder whose contents had never been downloaded.
                    unreadable++;
                    skipped++;
                    continue;
                } catch (Exception e) {
                    // Not decodable as text after all. Counted, not announced one file at a time.
                    skipped++;
                    continue;
                }
                if (raw.isBlank()) {
                    skipped++;
                    continue;
                }
                com.osscli.util.Redactor.Result scrubbed = com.osscli.util.Redactor.redact(raw);
                if (scrubbed.redactedAnything()) {
                    redacted++;
                }
                Files.writeString(into.resolve(importedName(from, file)), scrubbed.text(), StandardCharsets.UTF_8);
                imported++;
            }
        }

        System.out.printf("  imported %d, skipped %d -> %s%n", imported, skipped, into);
        if (cloudBacked(imported, unreadable)) {
            // Loud, because this is the difference between "your export is empty" and "your export
            // has not been downloaded", and only one of those is worth acting on.
            System.out.println();
            System.out.printf(
                    "  %d file(s) could not be read at all — this folder streams from the cloud%n", unreadable);
            System.out.println("  and its contents are still there rather than here. Make them local first:");
            System.out.println("    open the folder in Finder and use 'Download Now',");
            System.out.println("    or copy it somewhere ordinary and import that.");
        }
        if (redacted > 0) {
            System.out.printf("  %d carried a secret, redacted in the copy -- the original is untouched%n", redacted);
            System.out.println("  Removing them here does not revoke them; rotate anything real.");
        }
        System.out.println("  oss memory index      reads them into the corpus");
        return 0;
    }

    /**
     * Whether a run failed because the folder was never downloaded.
     *
     * <p>Not "some files failed": every read failing and nothing landing is the signature of a
     * placeholder folder, where a handful of failures among successes is just a handful of bad
     * files. The distinction decides whether the advice is worth printing at all.
     */
    static boolean cloudBacked(int imported, int unreadable) {
        return imported == 0 && unreadable > 0;
    }

    /** Extensions that are certainly not prose, so they are not opened at all. */
    private static final List<String> BINARY =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".zip", ".mov", ".mp4", ".class", ".jar");

    /**
     * Whether a file in an export is worth trying to read.
     *
     * <p>An exclusion, not an allow-list, and that is the whole lesson of running this against a
     * real export: 638 files, of which the conversations and pastes have <b>no extension at all</b>
     * -- "Paste July 01, 2026 - 11:21PM", 45 KB of readable text. An allow-list of .md, .txt, .json
     * and .html imported zero of them and reported it as "not text", which was wrong about 179
     * files and right for the wrong reason about 418 screenshots.
     *
     * <p>So anything not obviously binary is opened, and whether it decodes decides the rest. The
     * caller counts what would not decode -- a cloud-synced export also contains placeholders whose
     * bytes were never downloaded, which look like files and read like nothing.
     */
    static boolean isText(Path file) {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return BINARY.stream().noneMatch(n::endsWith);
    }

    /**
     * A stable, flat name for an imported file.
     *
     * <p>Flattened because an export nests deeply and a note archive does not, and stable because a
     * second import of the same export must rewrite rather than double the corpus -- the rule the
     * review notes learned after six copies of one review accumulated in a real archive.
     */
    static String importedName(Path root, Path file) {
        String rel = root.relativize(file).toString();
        String flat = rel.replaceAll("[/\\\\]", "-").replaceAll("[^A-Za-z0-9._-]", "_");
        return flat.endsWith(".md") ? flat : flat + ".md";
    }

    /**
     * What you actually worked out on a topic, rather than where you discussed it.
     *
     * <p>{@code map} counts notes; this reads them. It pulls the problem and the resolution out of
     * each note that carries them and puts the public record first, so the result is a page you can
     * read top to bottom and come away knowing what was solved.
     */
    private static int digest(List<String> args) throws IOException {
        KnowledgePack pack = KnowledgePack.load();
        if (pack.topics().isEmpty()) {
            System.out.println("  no topics declared, so there is nothing to digest.");
            System.out.println();
            System.out.println("  kb.json:  {\"topics\": {\"log4j\": [\"log4j\", \"appender\"]}}");
            System.out.println("  " + AppPaths.BASE_DIR.resolve("kb.json"));
            return 0;
        }

        List<String> wanted = args.isEmpty() ? List.copyOf(pack.topics().keySet()) : args;
        for (String topic : wanted) {
            List<String> terms = pack.topics().get(topic);
            if (terms == null) {
                System.out.println("  no topic called \"" + topic + "\" in kb.json");
                continue;
            }
            List<Digest.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, String> note : notesMatching(terms).entrySet()) {
                Map<String, String> sections = Digest.sectionsOf(note.getValue());
                if (!sections.isEmpty()) {
                    entries.add(new Digest.Entry(note.getKey(), Digest.originOf(note.getKey()), sections));
                }
            }
            Path out = pack.archive().resolve(topic + "-digest.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, Digest.render(topic, entries), StandardCharsets.UTF_8);
            System.out.printf("  %-16s %d note(s) with something in them -> %s%n", topic, entries.size(), out);
        }
        return 0;
    }

    /**
     * Indexed notes whose text carries any of these terms.
     *
     * <p>Read from the corpus rather than by walking the archive: the notes are already there with
     * their content, and a cloud-synced folder answers a full-text walk in minutes where the
     * database answers in milliseconds — the same reason the harvest writes files and the index
     * reads them.
     */
    private static Map<String, String> notesMatching(List<String> terms) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String term : terms) {
            out.putAll(com.osscli.storage.SqliteStorage.notesContaining(term));
        }
        return out;
    }

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

        com.osscli.github.GitHubClient.Found result;
        List<com.osscli.model.Issue> found;
        try {
            // Everything you touched, not only what you authored: involves: covers author,
            // assignee, mentions and commenter in one query.
            result = new com.osscli.github.GitHubClient().search("involves:" + user + " sort:updated-desc");
            found = result.items();
        } catch (Exception e) {
            System.err.println("error  could not reach GitHub: " + e.getMessage());
            System.err.println("       harvest is the one verb here that needs the network.");
            return 1;
        }

        com.osscli.github.GitHubClient gh = new com.osscli.github.GitHubClient();
        int written = 0;
        int withDiscussion = 0;
        for (com.osscli.model.Issue issue : found) {
            List<String> discussion = new ArrayList<>();
            String repo = repositoryOf(issue);
            if (!"unknown".equals(repo) && issue.comments() > 0) {
                try {
                    // Two pages. A thread past two hundred comments is one nobody reads to the end,
                    // and fetching every one of them costs the harvest its pace.
                    for (Map<String, Object> c :
                            gh.getPaged("/repos/" + repo + "/issues/" + issue.number() + "/comments", 2)) {
                        discussion.add(comment(c));
                    }
                } catch (Exception e) {
                    // One unreachable thread must not end the harvest. The note is still worth
                    // writing, and says so by simply carrying no Why section.
                    System.err.println("  (could not read the thread on " + repo + " #" + issue.number() + ")");
                }
            }
            if (!discussion.isEmpty()) {
                withDiscussion++;
            }
            Path note = into.resolve(harvestName(issue));
            Files.writeString(note, harvestNote(issue, discussion), StandardCharsets.UTF_8);
            written++;
        }
        System.out.printf("  %d of them carried a conversation worth keeping%n", withDiscussion);

        System.out.printf("  harvested %d item(s) for %s into %s%n", written, user, into);
        if (result.truncated()) {
            // The number above is a page of the answer, and saying so is the difference between
            // "this is your record" and "this is the newest part of it". The first run of this
            // collected thirty of 1,218 and reported thirty.
            System.out.printf(
                    "  %d match in total — GitHub's search stops at %d, newest first%n",
                    result.totalAvailable(), com.osscli.github.GitHubClient.SEARCH_LIMIT);
        }
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
    static String harvestNote(com.osscli.model.Issue issue, List<String> discussion) {
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

        sb.append("## The Problem (What & Where)\n\n");
        sb.append(
                        issue.body() == null || issue.body().isBlank()
                                ? "(filed with no description)"
                                : issue.body().strip())
                .append("\n\n");

        // The conversation, in order. A comment stranded without the thread around it is the thing
        // this exists to prevent: the reasoning is in the exchange, not in any one message.
        if (discussion != null && !discussion.isEmpty()) {
            sb.append("## The \"Why\" (Review Discussions)\n\n");
            for (String line : discussion) {
                sb.append(line).append("\n\n");
            }
        }

        sb.append("## The Solution (How)\n\n");
        sb.append(
                        "closed".equalsIgnoreCase(issue.state())
                                ? "Closed. The thread above carries how it was resolved."
                                : "Still open at the time this was harvested.")
                .append('\n');
        return sb.toString();
    }

    /**
     * One comment, as a line of the conversation.
     *
     * <p>Author and time kept, because "who said this and when" is most of what makes an old thread
     * readable a year later.
     */
    static String comment(Map<String, Object> raw) {
        Object user = raw.get("user");
        String who = user instanceof Map<?, ?> m && m.get("login") != null ? String.valueOf(m.get("login")) : "someone";
        String when = raw.get("created_at") == null ? "" : String.valueOf(raw.get("created_at"));
        String body =
                raw.get("body") == null ? "" : String.valueOf(raw.get("body")).strip();
        return "### @" + who + (when.isEmpty() ? "" : " — " + when) + "\n\n" + body;
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
                case "digest":
                    return digest(args);
                case "import":
                    return importExport(args);
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
