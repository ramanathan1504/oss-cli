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
import java.util.concurrent.TimeUnit;
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
    public static final List<String> VERBS = List.of(
            "file",
            "track",
            "search",
            "index",
            "map",
            "coverage",
            "gaps",
            "harvest",
            "sessions",
            "contributions",
            "curriculum",
            "digest",
            "import",
            "schedule",
            "doctor");

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
        // The local transcripts are a separate source with a separate failure mode: they need no
        // network and no token, so they must not be behind the check for either.
        boolean wantsSessions = args.contains("--sessions");
        boolean onlySessions = wantsSessions && args.contains("--sessions-only");
        if (onlySessions) {
            return harvestSessions();
        }

        List<String> rest = args.stream().filter(a -> !a.startsWith("--")).toList();
        String user = rest.isEmpty() ? configuredUser() : rest.get(0).strip();
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
            // Recorded, so that a scheduled run failing every morning is visible to a command
            // somebody types rather than only to a log nobody opens.
            com.osscli.schedule.DailyJob.record(false, "could not reach GitHub: " + e.getMessage());
            // The local half still runs. It needs no network and no token, which the comment at
            // the top of this method has claimed since it was written -- while this return sat
            // above the only call that would have honoured it. Proven on this machine: the
            // scheduled run of 2026-08-28 recorded "no network", exited here, and the sessions
            // folder it should have written was empty. Every offline morning cost both halves
            // when it only ever needed to cost one.
            if (wantsSessions) {
                System.err.println();
                harvestSessions();
            }
            return 1;
        }

        com.osscli.github.GitHubClient gh = new com.osscli.github.GitHubClient();
        int written = 0;
        int withDiscussion = 0;
        int mine = 0;
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
                        // The same page, read twice for two different purposes. The note keeps the
                        // conversation; this keeps the half of it the user wrote, with their name
                        // still attached -- which the note cannot answer, because a note is prose.
                        // No extra request: this page was already fetched.
                        mine += com.osscli.storage.SqliteStorage.saveAuthoredComment(user, repo, issue.number(), c)
                                ? 1
                                : 0;
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
        if (mine > 0) {
            // Said out loud because it is the only thing here that is about the user rather than
            // about the repositories: this is the number `oss profile --me` can measure a voice from.
            System.out.printf("  %d comment(s) you wrote yourself, kept as yours — oss profile --me%n", mine);
        }

        System.out.printf("  harvested %d item(s) for %s into %s%n", written, user, into);
        if (result.truncated()) {
            // The number above is a page of the answer, and saying so is the difference between
            // "this is your record" and "this is the newest part of it". The first run of this
            // collected thirty of 1,218 and reported thirty.
            System.out.printf(
                    "  %d match in total — GitHub's search stops at %d, newest first%n",
                    result.totalAvailable(), com.osscli.github.GitHubClient.SEARCH_LIMIT);
        }
        if (wantsSessions) {
            System.out.println();
            harvestSessions();
        }
        com.osscli.schedule.DailyJob.record(true, written + " item(s) for " + user);
        embedWhatWasWritten();
        return 0;
    }

    /**
     * Turn what harvest just wrote into vectors, in the same run.
     *
     * <p>Otherwise the daily job is a machine that writes notes nobody can find. Only the embedding
     * step makes a note reachable by {@code chat}, {@code guide}, {@code pick} and {@code prompt},
     * and the scheduled command is {@code oss memory harvest} — so a laptop could harvest every
     * morning for a month and answer from none of it. Measured before this: 23 PR reviews on disk,
     * 19 embedded, the four newest invisible.
     *
     * <p>Only the built-in store, which is where harvest writes. The folders in {@code drive.paths}
     * stay {@code sync --me}'s job: they can be an archive that streams from the cloud, and a
     * background job is the worst possible place to start downloading 800 files.
     *
     * <p>Not acting unasked. It indexes the notes this command created, on the run that created
     * them, with the model that already ships — no network, no download, nothing fetched.
     */
    private static void embedWhatWasWritten() {
        com.osscli.retrieval.LocalEmbedder embedder =
                com.osscli.retrieval.Embeddings.ifPresent(m -> System.out.println("  " + m));
        if (embedder == null) {
            // The floor still holds: the notes are on disk and `memory search` finds them by term.
            // Saying which half you have beats implying you have both.
            System.out.println("  no embedding model, so these are searchable by term and not by meaning");
            System.out.println("  oss model --fetch     22 MB, once");
            return;
        }
        System.out.println("  indexing what was written…");
        com.osscli.retrieval.NoteIndexer.index(
                java.util.List.of(DIR.toString()), embedder, com.osscli.retrieval.Embeddings.MODEL);
        System.out.println("  indexed — chat, guide, pick and prompt can see them now");
    }

    // -------------------------------------------------------------- curriculum ---

    /**
     * Place every area of every subject into gap, backlog or covered.
     *
     * <p>{@code coverage} grades an area by how much the notes say about it, which conflates two
     * different situations. An area met forty times across three pull requests is not one you know
     * -- it is one you have run into, usually while fixing something else, with the understanding
     * spread across a transcript and a diff. An area with nothing at all is a different problem.
     *
     * <p><b>Nothing here ever marks anything covered.</b> That is a claim about having read
     * something and no count of mentions can establish it, which is why the move is done by hand
     * and why re-running this never moves a file back.
     */
    private static int curriculum(List<String> args) throws IOException {
        boolean dryRun = args.contains("--dry-run");
        KnowledgePack pack = KnowledgePack.load();
        if (pack.yardsticks().isEmpty()) {
            com.osscli.ui.Out.none("no yardstick declared, so there is nothing to be missing from");
            com.osscli.ui.Out.hint("kb.json", "list what each subject's own manual documents");
            return 0;
        }

        Path archive = pack.archive();
        List<com.osscli.knowledge.Curriculum.Item> items;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("measuring what you know")) {
            live.step("scoring " + pack.yardsticks().size() + " subject(s) against the archive");
            items = com.osscli.knowledge.Curriculum.place(archive, pack.yardsticks());
        }

        int written = 0;
        int respected = 0;
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("filing")) {
            for (com.osscli.knowledge.Curriculum.Item item : items) {
                live.step(item.subject() + " · " + item.area());
                if (dryRun) {
                    continue;
                }
                List<String> evidence = "backlog".equals(item.state())
                        ? com.osscli.knowledge.Curriculum.evidenceFor(archive, item.area(), 8)
                        : List.of();
                if (com.osscli.knowledge.Curriculum.write(archive, item, evidence)) {
                    written++;
                } else {
                    respected++;
                }
            }
        }

        com.osscli.ui.Out.gap();
        com.osscli.ui.Out.title("what you know, by subject");
        for (com.osscli.knowledge.Curriculum.Tally t :
                com.osscli.knowledge.Curriculum.tallies(archive, pack.yardsticks())) {
            com.osscli.ui.Out.item(String.format(
                    "%-18s %3d covered   %3d backlog   %3d gap   of %d",
                    t.subject(), t.covered(), t.backlog(), t.gap(), t.total()));
        }
        com.osscli.ui.Out.gap();
        if (dryRun) {
            com.osscli.ui.Out.ok(items.size() + " area(s) placed (dry run, nothing written)");
        } else {
            com.osscli.ui.Out.ok(written + " area note(s) written under " + archive.resolve("Reference/coverage"));
        }
        if (respected > 0) {
            com.osscli.ui.Out.note(respected + " already marked covered — left exactly where you put them");
        }
        // These are notes like any other and have to be findable like any other. A reading list
        // you cannot search is a folder you open once. Embedding them means `oss ask` can answer
        // "what have I not learned about rollover" from the same index that answers everything
        // else, rather than from a folder somebody has to remember to look in.
        if (!dryRun && written > 0) {
            embedNotes(archive.toString());
        }
        com.osscli.ui.Out.hints(List.of(
                new String[] {"read one, then move it to covered/", "the move is the record"},
                new String[] {"oss memory curriculum", "re-run any time; it never moves your work back"}));
        return 0;
    }

    // ------------------------------------------------------------ contributions ---

    /**
     * A note for every change of yours that reached a release branch.
     *
     * <p>The archive was full of what was discussed and held almost nothing about what landed.
     * Forty commits across {@code 2.x} and {@code main} -- the work that survived review, the thing
     * a PMC or an employer would actually look at -- existed only as lines in somebody else's git
     * history, with the review that shaped each one scattered across three GitHub endpoints.
     *
     * <p>Each note carries the commit, the diffstat, the files, the description, the timeline, and
     * every remark anybody made, including the ones pinned to lines of the diff -- which is where
     * review actually happens and which "fetch the comments" misses entirely.
     *
     * <p><b>Read-only against the repository.</b> Every git command is a read and every GitHub call
     * is a GET. Nothing is cloned, fetched into a working tree, pushed, commented on or opened, and
     * nothing written here is ever sent anywhere.
     */
    private static int contributions(List<String> args) throws IOException {
        List<String> rest = args.stream().filter(a -> !a.startsWith("--")).toList();
        Path checkout = Path.of(rest.isEmpty() ? System.getProperty("user.dir", ".") : rest.get(0));
        boolean offline = args.contains("--offline");
        boolean dryRun = args.contains("--dry-run");

        if (!Files.isDirectory(checkout.resolve(".git"))) {
            System.err.println("error  " + checkout + " is not a git checkout");
            System.err.println("       oss memory contributions ~/src/owner-name");
            return 2;
        }

        String name = configuredUser();
        if (name == null || name.isBlank()) {
            System.err.println("error  whose commits? set one once: oss setup  (github.username)");
            return 2;
        }

        String repo = com.osscli.knowledge.Contributions.remoteOf(checkout);
        List<com.osscli.knowledge.Contributions.Landing> landed =
                com.osscli.knowledge.Contributions.landed(checkout, name);
        if (landed.isEmpty()) {
            com.osscli.ui.Out.none(
                    "no commits of yours on " + String.join(", ", com.osscli.knowledge.Contributions.RELEASE_BRANCHES));
            com.osscli.ui.Out.hint("git fetch origin", "the release branches have to be here to be read");
            return 0;
        }
        com.osscli.ui.Out.title(landed.size() + " landed change(s) in " + repo);

        KnowledgePack pack = KnowledgePack.load();
        Path into = pack.archive().resolve("Projects");
        com.osscli.github.GitHubClient gh = offline ? null : new com.osscli.github.GitHubClient();

        int written = 0;
        int noConversation = 0;
        java.util.Map<String, Integer> byTopic = new java.util.TreeMap<>();
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("reading your history")) {
            for (com.osscli.knowledge.Contributions.Landing l : landed) {
                live.step("PR " + l.pr() + " — " + l.subject());
                com.osscli.knowledge.Contributions.Conversation talk = null;
                if (gh != null && l.pr() > 0 && !repo.isBlank()) {
                    talk = com.osscli.knowledge.Contributions.conversationOn(gh, repo, l.pr());
                }
                if (talk == null || talk.remarks().isEmpty()) {
                    noConversation++;
                }
                com.osscli.knowledge.Contributions.Diffstat stat =
                        com.osscli.knowledge.Contributions.diffstat(checkout, l.sha());

                String title = talk != null && !talk.title().isBlank() ? talk.title() : l.subject();
                com.osscli.knowledge.SessionNotes.Scored topic = com.osscli.knowledge.SessionNotes.topicOf(
                        com.osscli.knowledge.Contributions.textOf(l, talk), repo, pack.topics());

                com.osscli.knowledge.Contribution.Landed c = new com.osscli.knowledge.Contribution.Landed(
                        repo,
                        l.pr(),
                        title,
                        l.sha(),
                        l.branch(),
                        talk != null && !talk.mergedAt().isBlank() ? talk.mergedAt() : l.date(),
                        talk == null ? "" : talk.body(),
                        stat.files(),
                        stat.insertions(),
                        stat.deletions(),
                        l.message(),
                        talk == null ? List.of() : talk.remarks(),
                        talk == null ? List.of() : talk.timeline(),
                        l.coAuthored());

                if (!dryRun) {
                    Path folder = into.resolve(topic.topic()).resolve("contributions");
                    Files.createDirectories(folder);
                    Files.writeString(
                            folder.resolve(com.osscli.knowledge.Contributions.nameFor(c)),
                            com.osscli.knowledge.Contribution.noteFor(c, topic.topic()),
                            StandardCharsets.UTF_8);
                }
                byTopic.merge(topic.topic(), 1, Integer::sum);
                written++;
            }
        }

        com.osscli.ui.Out.gap();
        com.osscli.ui.Out.ok(written + " contribution note(s)" + (dryRun ? " (dry run, nothing written)" : ""));
        byTopic.forEach((topic, n) -> com.osscli.ui.Out.kv(topic, String.valueOf(n)));
        if (noConversation > 0) {
            // Said out loud: a change that merged unopposed is a real fact about the change, and a
            // silent section could equally mean the fetch failed.
            com.osscli.ui.Out.note(noConversation + " merged with nothing said on them"
                    + (offline ? " — --offline, so GitHub was never asked" : ""));
        }
        if (!dryRun) {
            embedNotes(into.toString());
        }
        return 0;
    }

    // ----------------------------------------------------------------- sessions ---

    /**
     * File every CLI transcript on this machine under what it was about.
     *
     * <p>Distinct from {@code harvest --sessions}, which writes a UUID-named dump into the built-in
     * store and stops. That store had never received one: the scheduled job runs {@code harvest}
     * with no flags, so the local half was gated behind a flag nobody passes and the folder was
     * empty. Meanwhile the archive it should have been feeding had 541 of its 837 notes filed under
     * the name of the program that produced them -- claude-code, claude-web, ai-studio -- which is
     * three folders for one subject and the reason none of it reads as a knowledge base.
     *
     * <p>This writes into the subject tree instead, beside the pull-request notes on the same
     * subject, with the tool recorded in the frontmatter where an attribute belongs.
     *
     * <p><b>Cheap on the hour.</b> A ledger of size-and-time means an ordinary run opens the two
     * transcripts that changed and none of the other 237. {@code --all} forgets it.
     */
    private static int sessions(List<String> args) throws IOException {
        boolean all = args.contains("--all");
        boolean dryRun = args.contains("--dry-run");
        boolean quiet = args.contains("--quiet");
        // Off unless asked for. A model call per session, hourly, against either a metered
        // subscription or this laptop's CPU, is how a background job becomes the reason somebody
        // uninstalls the tool.
        boolean enrich = args.contains("--enrich");
        // A command-line tool writes the better paragraph and it is the one that costs a
        // subscription -- this machine ran out of credit mid-afternoon once already. So the local
        // model is the default even when a tool is installed, and reaching for one is a second,
        // separate decision. Which tool is not decided here: Enrichment takes whatever this
        // install prefers and is actually present, so the archive never depends on one vendor.
        boolean allowClaude = args.contains("--cli") || args.contains("--claude");
        int limit = limitIn(args);

        KnowledgePack pack = KnowledgePack.load();
        Path archive = pack.archive();
        if (pack.topics().isEmpty()) {
            com.osscli.ui.Out.warn("no topics in kb.json, so everything files under \"general\"");
            com.osscli.ui.Out.hint("kb.json", "give each subject the terms that identify it");
        }

        Path home = Path.of(System.getProperty("user.home", "."));
        List<Path> transcripts = Sessions.discover(home, pack.transcripts());
        if (transcripts.isEmpty()) {
            com.osscli.ui.Out.none("no CLI transcripts under ~/.claude, ~/.codex or ~/.gemini");
            return 0;
        }

        if (all) {
            SessionLedger.forget();
        }
        SessionLedger ledger = SessionLedger.load();
        List<String> skipProjects = excludedProjects(pack);

        int filed = 0;
        int unchanged = 0;
        int excluded = 0;
        int silent = 0;
        int machine = 0;
        java.util.Map<String, Integer> byTopic = new java.util.TreeMap<>();
        List<Path> written = new ArrayList<>();

        for (Path file : transcripts) {
            if (!ledger.changed(file)) {
                unchanged++;
                continue;
            }
            String project = com.osscli.knowledge.SessionNotes.projectOf(file);
            // A transcript from a temporary directory is this tool talking to itself: an ask, a
            // subagent's scratchpad, a one-off script. Filed as knowledge they produced notes
            // called "Reply with exactly: OK" -- a question asked of the tool, not work done with
            // it.
            if (com.osscli.knowledge.SessionNotes.ranInATempDirectory(project)) {
                machine++;
                ledger.mark(file);
                continue;
            }
            if (isExcluded(project, skipProjects)) {
                excluded++;
                // Marked anyway. An excluded transcript that stays unmarked is re-examined every
                // hour for ever, which is the cost the ledger exists to remove.
                ledger.mark(file);
                continue;
            }
            Sessions.Session session = Sessions.read(file);
            // The directory is not the whole answer. A session started from the home folder, or
            // handed to a subagent with its own scratchpad, edits this repository all afternoon
            // while its transcript sits under a path that names neither. Twelve of them came
            // through the directory check on the first run and filed themselves under "java" and
            // "ai-ml" -- documentation work on the tool itself, indistinguishable in the archive
            // from the Java notes it was supposed to be kept out of. The files it opened say what
            // it was working on and cannot be wrong about it.
            if (isExcluded(String.join(" ", session.touchedPaths()), skipProjects)) {
                excluded++;
                ledger.mark(file);
                continue;
            }
            if (!session.worthKeeping()) {
                silent++;
                ledger.mark(file);
                continue;
            }
            // A subagent's transcript is this tool prompting a model, not a person working
            // something out. Twenty-four of them shared two first turns and therefore two file
            // names, and quietly overwrote each other down to two notes.
            if (com.osscli.knowledge.SessionNotes.isAgentPrompt(session.raw())) {
                machine++;
                ledger.mark(file);
                continue;
            }

            final String text = String.join("\n", session.turns());
            final com.osscli.knowledge.SessionNotes.Scored topic =
                    com.osscli.knowledge.SessionNotes.topicOf(text + "\n" + project, project, pack.topics());
            final String fallbackName = project.isBlank() ? "session " + session.id() : project + " session";
            String title = com.osscli.knowledge.SessionNotes.titleOf(session.raw(), fallbackName);

            com.osscli.knowledge.Enrichment.Summary summary =
                    new com.osscli.knowledge.Enrichment.Summary("", com.osscli.knowledge.Enrichment.By.NONE);
            if (!dryRun && enrich && filed < limit) {
                // Wrapped in the same progress line every other model call in this tool uses.
                // A summariser working through forty sessions in silence is indistinguishable
                // from one that has hung, which is the complaint that put Live in here at all.
                try (com.osscli.ui.Live live = com.osscli.ui.Live.start("summarising")) {
                    live.step(title);
                    summary = com.osscli.knowledge.Enrichment.summarise(title, topic.topic(), text, allowClaude);
                }
                // Re-titled now the summary exists, and only when the transcript held no good name
                // of its own. "why chnaged scraping count is greter than 1" is exactly what was
                // asked and is not something anybody will ever find again.
                title = com.osscli.knowledge.SessionNotes.titleOf(
                        session.raw(), fallbackName, summary.present() ? summary.text() : null);
            }
            final String finalTitle = title;
            Path note = com.osscli.knowledge.SessionNotes.fileInWithoutClobbering(
                    archive, topic.topic(), dayOf(session), finalTitle, session.id());
            // Reassigned below when the session names a pull request or an issue.

            if (!dryRun) {
                // One subject, one note.
                //
                // A note per session fragmented the record: four days on one issue produced five
                // files, each a fifth of the story and none of them the place to look. When a
                // session names a pull request or an issue, that reference is the file and this
                // session becomes a dated section in it.
                String reference = com.osscli.knowledge.SessionNotes.referenceIn(session.raw());
                if (reference != null) {
                    note = com.osscli.knowledge.SessionLog.pathFor(archive, topic.topic(), reference);
                    com.osscli.knowledge.SessionLog.append(
                            note,
                            reference,
                            topic.topic(),
                            project,
                            session.id(),
                            com.osscli.knowledge.SessionLog.sectionFor(
                                    dayOf(session),
                                    finalTitle,
                                    summary,
                                    com.osscli.knowledge.SessionNotes.bodyOf(com.osscli.knowledge.SessionNotes.noteFor(
                                            session,
                                            topic,
                                            project,
                                            finalTitle,
                                            com.osscli.knowledge.SessionNotes.touched(session.touchedPaths()),
                                            new com.osscli.knowledge.Enrichment.Summary(
                                                    "", com.osscli.knowledge.Enrichment.By.NONE)))));
                } else {
                    Files.createDirectories(note.getParent());
                    Files.writeString(
                            note,
                            com.osscli.knowledge.SessionNotes.noteFor(
                                    session,
                                    topic,
                                    project,
                                    finalTitle,
                                    com.osscli.knowledge.SessionNotes.touched(session.touchedPaths()),
                                    summary),
                            StandardCharsets.UTF_8);
                }
                ledger.mark(file);
                written.add(note);
            }
            byTopic.merge(topic.topic(), 1, Integer::sum);
            filed++;
            if (!quiet) {
                com.osscli.ui.Out.item(topic.topic() + com.osscli.ui.Out.faint("  ·  " + title));
            }
        }

        if (!dryRun) {
            ledger.save();
        }

        com.osscli.ui.Out.gap();
        if (filed == 0) {
            com.osscli.ui.Out.ok("nothing new — " + unchanged + " transcript(s) already filed");
        } else {
            com.osscli.ui.Out.ok(
                    filed + " session(s) filed by subject" + (dryRun ? " (dry run, nothing written)" : ""));
            byTopic.forEach((topic, n) -> com.osscli.ui.Out.kv(topic, String.valueOf(n)));
        }
        if (unchanged > 0 && filed > 0) {
            com.osscli.ui.Out.note(unchanged + " unchanged since the last run, not reopened");
        }
        if (excluded > 0) {
            com.osscli.ui.Out.note(excluded + " skipped as tool-building rather than knowledge — kb.json \"exclude\"");
        }
        if (silent > 0) {
            com.osscli.ui.Out.note(silent + " held no prose worth keeping — tool calls only");
        }
        if (machine > 0) {
            com.osscli.ui.Out.note(
                    machine + " were subagent runs — a prompt this tool wrote, not a question you asked");
        }

        if (enrich && filed > limit) {
            com.osscli.ui.Out.note((filed - limit) + " past the --limit of " + limit + " were filed without a summary");
            com.osscli.ui.Out.hint(
                    "oss memory sessions --all --enrich --limit 200", "do the rest when you are away from the machine");
        }
        if (!enrich && filed > 0) {
            com.osscli.ui.Out.hint("oss memory sessions --enrich", "add a paragraph saying what each one settled");
        }

        // Pruning runs whether or not anything was written, and that is the whole point.
        //
        // It was inside the write branch, so a run that filed nothing pruned nothing -- and a
        // deletion is exactly the case that writes nothing. 229 junk notes were removed from the
        // archive, the next tick found no new transcripts, and 294 rows for files nobody could
        // open stayed in the index answering searches. A stat per path costs nothing; skipping it
        // costs an index that is quietly wrong in the one situation it most needs to be right.
        pruneMovedNotes(false);

        if (!written.isEmpty()) {
            // The one expensive thing on the hourly path, and the only place battery matters.
            //
            // A tick that finds nothing costs 0.89 CPU-seconds; embedding a folder of notes costs
            // minutes of every core. Nobody is waiting for it, so on battery it waits for mains --
            // which is different from a command somebody typed, where the cost was accepted by the
            // act of typing it.
            if (quiet && com.osscli.schedule.Power.onBattery()) {
                com.osscli.ui.Out.note(com.osscli.schedule.Power.deferred("indexing"));
            } else {
                embedNotes(archive.resolve("Projects").toString());
            }
        }
        return 0;
    }

    /**
     * How many sessions may be summarised in one run.
     *
     * <p>Small by default, because the first backfill on this machine had 134 sessions to file and
     * a model call each. Left uncapped that is either a bill or an afternoon of fan noise, arrived
     * at by typing one word.
     */
    static int limitIn(List<String> args) {
        int at = args.indexOf("--limit");
        if (at >= 0 && at + 1 < args.size()) {
            try {
                return Math.max(0, Integer.parseInt(args.get(at + 1)));
            } catch (NumberFormatException e) {
                // A limit that will not parse must not silently become "no limit".
                System.err.println("error  --limit wants a number, got \"" + args.get(at + 1) + "\"");
                return 0;
            }
        }
        return 20;
    }

    /**
     * Which checkouts produce sessions that are not knowledge.
     *
     * <p>Building the tool that files the notes generates transcripts about filing notes. They are
     * real work and they are not the subject anybody wants their archive to be about, and left in
     * they dominate it -- this repository alone accounts for the largest transcripts on the machine.
     * Declared in {@code kb.json} rather than hard-coded, because whose work is incidental is a
     * judgement about a person's life, not a property of the software.
     */
    static List<String> excludedProjects(KnowledgePack pack) {
        List<String> configured = pack.excluded();
        return configured.isEmpty() ? List.of() : configured;
    }

    static boolean isExcluded(String project, List<String> excluded) {
        if (project == null || project.isBlank()) {
            return false;
        }
        String p = project.toLowerCase(java.util.Locale.ROOT);
        for (String skip : excluded) {
            if (p.contains(skip.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The day a session belongs to, so notes sort by when the work happened. */
    static String dayOf(Sessions.Session session) {
        String when = session.when();
        if (when != null && when.length() >= 10 && when.charAt(4) == '-') {
            return when.substring(0, 10);
        }
        return java.time.LocalDate.now().toString();
    }

    /** Turn a folder of freshly written notes into vectors, in the run that wrote them. */
    private static void embedNotes(String folder) {
        com.osscli.retrieval.LocalEmbedder embedder = com.osscli.retrieval.Embeddings.ifPresent(m -> {});
        if (embedder == null) {
            com.osscli.ui.Out.note("no embedding model, so these are searchable by term and not by meaning");
            com.osscli.ui.Out.hint("oss model --fetch", "22 MB, once");
            return;
        }
        com.osscli.ui.Out.note("indexing what was written…");
        com.osscli.retrieval.NoteIndexer.index(
                java.util.List.of(folder), embedder, com.osscli.retrieval.Embeddings.MODEL);
        // Adding is only half of keeping an index true.
        //
        // 89 notes were deleted from the archive and their rows stayed in the index, still
        // scoring, still answering. Asked whether one issue was in the store, the honest answer
        // came back with five hits that were files nobody could open -- and they were the junk
        // notes that had just been removed for being junk. Nothing was wrong with the delete; the
        // index simply had no idea it had happened, and would not until somebody ran another
        // command and thought to look.
        //
        // A stat per indexed path is cheap. Not noticing is not.
        pruneMovedNotes(false);
        com.osscli.ui.Out.ok("indexed — ask, chat, guide, pick and prompt can see them now");
    }

    /**
     * Collect the sessions the CLIs on this machine already wrote.
     *
     * <p>Separate from the GitHub half on purpose: this one needs no network, no token and no
     * account, so a machine with none of those still gets something out of {@code harvest}. It is
     * also the half nobody else has — GitHub holds the outcome of the work, these hold the working.
     */
    static int harvestSessions() throws IOException {
        Path home = Path.of(System.getProperty("user.home", "."));
        List<Path> transcripts = Sessions.discover(home);
        if (transcripts.isEmpty()) {
            System.out.println("  no local CLI sessions found under ~/.claude, ~/.codex or ~/.gemini");
            return 0;
        }

        Path into = DIR.resolve("sessions");
        Files.createDirectories(into);
        int considered = Math.min(transcripts.size(), Sessions.MAX_SESSIONS);
        int written = 0;
        int empty = 0;
        for (Path file : transcripts.subList(0, considered)) {
            Sessions.Session session = Sessions.read(file);
            if (!session.worthKeeping()) {
                empty++;
                continue;
            }
            Files.writeString(
                    into.resolve(Sessions.nameFor(session)), Sessions.noteFor(session), StandardCharsets.UTF_8);
            written++;
        }

        System.out.printf("  wrote %d session note(s) into %s%n", written, into);
        if (empty > 0) {
            System.out.printf("  %d transcript(s) held no prose worth keeping — tool calls only%n", empty);
        }
        if (transcripts.size() > considered) {
            // Said out loud, because this is the shape of the bug the GitHub search had: a cap
            // reported as a total reads as completeness.
            System.out.printf(
                    "  %d transcript(s) found in all — the newest %d were read%n", transcripts.size(), considered);
        }
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
        return "gh-" + windowsSafe(repositoryOf(issue).replace('/', '-')) + "-" + issue.number() + ".md";
    }

    /**
     * A filename Windows will actually accept, built from data GitHub sent.
     *
     * <p>{@code harvest} names each note after the repository, and the repository comes off the
     * API. On Windows this threw before it wrote anything:
     *
     * <pre>
     * java.nio.file.InvalidPathException: Illegal char &lt;:&gt; at index 6: gh-??:?-...-35.md
     * </pre>
     *
     * <p>Real repository names cannot contain a colon, which is why this survived — but a filename
     * assembled from a remote value and handed to the filesystem unexamined is the same defect that
     * cost this project a release once already, and it was found here only because the suite
     * finally ran on Windows.
     *
     * <p><b>Not {@code slug()}</b>, which also lowercases: a thousand harvested notes already exist
     * under names like {@code gh-opensearch-project-OpenSearch-4174.md}, and lowercasing would make
     * the next harvest write a second copy of every one of them beside the first. This changes
     * nothing for any name that was already legal.
     */
    private static String windowsSafe(String name) {
        // The set Windows refuses outright, plus the control characters no filesystem wants.
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "-");
        // A trailing dot or space is silently dropped by Windows, so a name ending in one refers to
        // a different file than the one asked for -- worse than being refused.
        cleaned = cleaned.replaceAll("[. ]+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
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

    /**
     * Print a warning only when there is one.
     *
     * <p>An archive that streams from the cloud is skipped rather than waited for, and the number
     * skipped is the difference between "your archive has nothing on Lookups" and "we could not
     * read your archive". Silence there would let the first be read for the second.
     */
    private static String alreadySaid = "";

    private static void say(String warning) {
        // Once. `coverage` scores per technology, so a kb.json naming three would otherwise print
        // the same paragraph three times, which reads as three separate problems.
        if (!warning.isBlank() && !warning.equals(alreadySaid)) {
            alreadySaid = warning;
            System.out.println();
            System.out.println(warning);
        }
    }

    /** Dispatch a verb. Returns a process exit code. */
    public static int run(String verb, List<String> args) {
        try {
            switch (verb == null ? "" : verb) {
                case "file":
                    return file(args);
                case "track":
                    return track(args);
                case "search":
                    return search(args);
                case "index":
                    return index(args);
                case "map":
                    return map();
                case "coverage":
                    return coverage();
                case "harvest":
                    return harvest(args);
                case "sessions":
                    return sessions(args);
                case "contributions":
                    return contributions(args);
                case "curriculum":
                    return curriculum(args);
                case "digest":
                    return digest(args);
                case "import":
                    return importExport(args);
                case "gaps":
                    return gaps();
                case "schedule":
                    return schedule(args);
                case "doctor":
                    return doctor();
                default:
                    System.err.println("error  built-in memory has no verb \"" + verb + "\"");
                    System.err.println("       it knows: " + String.join(", ", VERBS));
                    // `oss memory sync --me` is the line this is here for: sync is a command of
                    // its own and --me is its flag, so listing memory's verbs was a correct
                    // answer that sent the reader hunting for a verb that will never exist.
                    String elsewhere = com.osscli.cli.NearMiss.elsewhere("memory", verb, args);
                    if (elsewhere != null) {
                        System.err.println("       \"" + verb + "\" is a command of its own: " + elsewhere);
                    }
                    System.err.println("       A different archive is a few lines in kb.json, not a checkout.");
                    return 2;
            }
        } catch (IOException e) {
            System.err.println("error  " + e.getMessage());
            return 1;
        }
    }

    /**
     * Notes in the archive that no configured folder covers.
     *
     * <p>{@code drive.paths} lists folders; {@code kb.json}'s archive names a tree. A note in the
     * tree but under no listed folder is read by nothing, embedded by nothing, and found by
     * nothing — and until this check it was reported by nothing either.
     */
    private static long notesOutsideTheIndexedFolders(Path archive) {
        if (!Files.isDirectory(archive)) {
            return 0;
        }
        List<Path> indexed = new ArrayList<>();
        indexed.add(DIR);
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("drive.paths");
            if (configured != null) {
                for (String folder : configured.split(",")) {
                    if (!folder.isBlank()) {
                        indexed.add(Path.of(folder.trim()));
                    }
                }
            }
        } catch (Exception e) {
            // No configuration readable: everything in the archive is outside it, which is what
            // the count will then say.
        }
        try (Stream<Path> walk = Files.walk(archive)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> indexed.stream().noneMatch(p::startsWith))
                    .count();
        } catch (IOException e) {
            return 0;
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
        // Last, not first. Sixteen topics of listing scroll a warning off the top of the terminal,
        // and a caveat nobody sees is the same as a caveat nobody wrote.
        say(Coverage.lastWarning());
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
            say(Coverage.lastWarning());
            // "touched", never "covered". Writing about something forty times is meeting it;
            // only you can say you have learned it, and `curriculum` is where that is recorded.
            long touched =
                    areas.stream().filter(a -> a.grade().equals("touched")).count();
            long thin = areas.stream().filter(a -> a.grade().equals("thin")).count();
            long nothing =
                    areas.stream().filter(a -> a.grade().equals("nothing")).count();
            System.out.printf(
                    "%n  %s — %d of %d touched · %d thin · %d never mentioned%n",
                    tech.getKey(), touched, areas.size(), thin, nothing);
            for (Coverage.Area a : areas) {
                System.out.printf(
                        "    %s  %-28s %3d note(s) %5d mention(s)  %s%n",
                        a.mark(), a.name(), a.notes(), a.mentions(), a.strongest());
            }
        }
        return 0;
    }

    // --------------------------------------------------------------------- gaps ---

    /**
     * Write down what the notes do not cover.
     *
     * <p>{@code coverage} prints the scorecard and it scrolls away. The list that matters is the
     * short one — the areas scoring nothing — and it is only useful if it survives the terminal:
     * filed as a note, it is retrievable, it goes into the corpus, and next month's run can be
     * compared against it.
     *
     * <p>Nothing here is a judgement about the reader. An area with no notes is a thing not written
     * down yet, which is the only claim the count can support.
     */
    private static int gaps() throws IOException {
        KnowledgePack pack = KnowledgePack.load();
        if (pack.yardsticks().isEmpty()) {
            System.out.println("  no yardstick declared, so nothing can be missing from it.");
            System.out.println();
            System.out.println("  A yardstick is what a technology's own manual documents:");
            System.out.println("  kb.json:  {\"yardsticks\": {\"log4j\": [\"Appenders\", \"Layouts\", \"Lookups\"]}}");
            System.out.println("  " + AppPaths.BASE_DIR.resolve("kb.json"));
            return 0;
        }
        Path into = DIR.resolve("gaps");
        Files.createDirectories(into);
        for (Map.Entry<String, List<String>> tech : pack.yardsticks().entrySet()) {
            List<Coverage.Area> areas = Coverage.score(pack.archive(), tech.getValue());
            say(Coverage.lastWarning());
            Path note = into.resolve("gaps-" + slug(tech.getKey()) + ".md");
            Files.writeString(note, gapNote(tech.getKey(), areas), StandardCharsets.UTF_8);
            long missing =
                    areas.stream().filter(a -> a.grade().equals("nothing")).count();
            long thin = areas.stream().filter(a -> a.grade().equals("thin")).count();
            System.out.printf(
                    "  %-16s %d of %d documented areas have nothing, %d are thin -> %s%n",
                    tech.getKey(), missing, areas.size(), thin, note.getFileName());
        }
        System.out.println("  oss memory index      makes them searchable with everything else");
        return 0;
    }

    /**
     * The gap report for one technology.
     *
     * <p>Package-private so a test reads the real one rather than restating its rules and then
     * agreeing with itself.
     */
    static String gapNote(String tech, List<Coverage.Area> areas) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(tech).append(" — what is not written down\n\n");
        sb.append("Measured ")
                .append(LocalDate.now(ZoneOffset.UTC))
                .append(" against the areas declared in kb.json.\n");
        sb.append("An area with no notes is a thing not written down yet — that is the whole claim.\n\n");

        List<String> nothing = areas.stream()
                .filter(a -> a.grade().equals("nothing"))
                .map(Coverage.Area::name)
                .toList();
        List<Coverage.Area> thin =
                areas.stream().filter(a -> a.grade().equals("thin")).toList();
        List<Coverage.Area> touched =
                areas.stream().filter(a -> a.grade().equals("touched")).toList();

        sb.append("## Nothing at all (").append(nothing.size()).append(")\n\n");
        if (nothing.isEmpty()) {
            sb.append("Every declared area has at least one note.\n");
        } else {
            nothing.forEach(n -> sb.append("- ").append(n).append('\n'));
        }

        sb.append("\n## Thin — one or two notes (").append(thin.size()).append(")\n\n");
        if (thin.isEmpty()) {
            sb.append("None.\n");
        } else {
            thin.forEach(a -> sb.append("- ")
                    .append(a.name())
                    .append(" — ")
                    .append(a.notes())
                    .append(" note(s), strongest: ")
                    .append(a.strongest().isBlank() ? "—" : a.strongest())
                    .append('\n'));
        }

        // "Touched", not "Covered". This section lists what your notes talk about, which is not
        // the same claim as having learned it -- that one is yours to make, in `curriculum`.
        sb.append("\n## Touched — your notes return to these (")
                .append(touched.size())
                .append(")\n\n");
        if (touched.isEmpty()) {
            sb.append("None yet.\n");
        } else {
            touched.forEach(a -> sb.append("- ")
                    .append(a.name())
                    .append(" — ")
                    .append(a.notes())
                    .append(" notes, ")
                    .append(a.mentions())
                    .append(" mentions\n"));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- schedule ---

    /**
     * Offer to run the harvest daily — and only when asked.
     *
     * <p>The harvest is worth having on a clock: what it collects accumulates whether or not anyone
     * remembers to type it, and the value of a knowledge base is a function of how little you have
     * to think about feeding it. But a job that installed itself the first time the tool ran would
     * be the same broken promise as an unrequested download, so this is a verb.
     */
    private static int schedule(List<String> args) throws IOException {
        boolean install = args.contains("--install");
        boolean uninstall = args.contains("--uninstall");
        if (install && uninstall) {
            System.err.println("error  --install and --uninstall are opposites; pick one");
            return 2;
        }

        int hour = com.osscli.schedule.DailyJob.DEFAULT_HOUR;
        int minute = com.osscli.schedule.DailyJob.DEFAULT_MINUTE;
        int at = args.indexOf("--at");
        if (at >= 0) {
            if (at + 1 >= args.size()) {
                System.err.println("error  --at needs a time: oss memory schedule --install --at 09:15");
                return 2;
            }
            int[] parsed = parseTime(args.get(at + 1));
            if (parsed == null) {
                System.err.println(
                        "error  --at wants HH:MM in 24-hour time, e.g. 09:15 — got \"" + args.get(at + 1) + "\"");
                return 2;
            }
            hour = parsed[0];
            minute = parsed[1];
        }

        // Two schedules, because they are two jobs with two failure modes: the daily one talks to
        // GitHub and the hourly one only reads files already on this disk. One flag picks which.
        boolean hourly = args.contains("--hourly");

        if (uninstall) {
            if (hourly) {
                boolean hadHourly = com.osscli.schedule.SessionJob.uninstall();
                System.out.println(
                        hadHourly ? "  removed the hourly session filing" : "  there was no hourly filing installed");
                return 0;
            }
            boolean had = com.osscli.schedule.DailyJob.uninstall();
            System.out.println(had ? "  removed the daily harvest" : "  there was no daily harvest installed");
            return 0;
        }
        if (!install) {
            return scheduleStatus();
        }

        Path jar = ownJar();
        if (hourly) {
            String saidHourly = com.osscli.schedule.SessionJob.install(
                    Path.of(System.getProperty("java.home"), "bin", "java"), jar);
            if (saidHourly == null) {
                System.err.println("  could not install it — " + com.osscli.schedule.DailyJob.unsupportedAdvice());
                return 1;
            }
            System.out.printf("  %s%n", saidHourly);
            System.out.println("  it will run  oss memory sessions  every hour");
            System.out.println("  the first tick is an hour from now — run it once yourself to see it work");
            System.out.println();
            // Offered, never installed. Another program's settings file is not this one's to edit,
            // and the schedule is the floor that catches everything a hook cannot: the other tools,
            // and any session that ended while the hook was wrong.
            System.out.println("  to have a session filed the moment it ends rather than within the hour,");
            System.out.println("  add this to ~/.claude/settings.json yourself:");
            System.out.println();
            for (String line : com.osscli.schedule.SessionJob.hookFor(
                            com.osscli.schedule.Platforms.launcher() == null
                                    ? "oss"
                                    : com.osscli.schedule.Platforms.launcher().toString())
                    .split("\n")) {
                System.out.println("    " + line);
            }
            System.out.println();
            System.out.println("  oss memory schedule --uninstall --hourly   removes the schedule");
            return 0;
        }
        String said = com.osscli.schedule.DailyJob.install(
                Path.of(System.getProperty("java.home"), "bin", "java"), jar, hour, minute);
        if (said == null) {
            // Refused rather than half-done. Writing a definition into a directory nothing reads
            // and calling it installed is the failure mode this whole file exists to avoid.
            System.err.println("  could not install it — " + com.osscli.schedule.DailyJob.unsupportedAdvice());
            return 1;
        }
        System.out.printf("  %s%n", said);
        System.out.printf("  it will run  oss memory harvest  every day at %02d:%02d%n", hour, minute);
        System.out.println("  oss memory doctor     tells you whether it is working");
        System.out.println("  oss memory schedule --uninstall   removes it");
        return 0;
    }

    /** {@code HH:MM}, or null when it is not one. */
    static int[] parseTime(String raw) {
        if (raw == null || !raw.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        String[] parts = raw.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        // 24:00 and 09:70 parse as numbers and are not times. launchd accepts them and then never
        // fires, which is indistinguishable from an install that did not happen.
        return (h > 23 || m > 59) ? null : new int[] {h, m};
    }

    private static int scheduleStatus() {
        boolean installed = com.osscli.schedule.DailyJob.isInstalled();
        System.out.printf("  daily harvest : %s%n", installed ? "installed" : "not installed");
        if (installed) {
            System.out.printf("  definition    : %s%n", com.osscli.schedule.DailyJob.descriptor());
            System.out.printf(
                    "  loaded        : %s%n",
                    com.osscli.schedule.DailyJob.running()
                            ? "yes"
                            : "no — the file is there but the system is not holding it");
        } else {
            System.out.println("  oss memory schedule --install            every day at 09:15");
            System.out.println("  oss memory schedule --install --at 07:00 or whenever suits you");
        }

        boolean hourly = com.osscli.schedule.SessionJob.isInstalled();
        System.out.printf("  hourly filing : %s%n", hourly ? "installed" : "not installed");
        if (hourly) {
            System.out.printf("  definition    : %s%n", com.osscli.schedule.SessionJob.descriptor());
            System.out.printf(
                    "  loaded        : %s%n",
                    com.osscli.schedule.SessionJob.running()
                            ? "yes"
                            : "no — the file is there but the system is not holding it");
        } else {
            System.out.println("  oss memory schedule --install --hourly   files CLI transcripts by subject");
        }
        return 0;
    }

    /** Where this program is, for a scheduler that needs an absolute answer. */
    private static Path ownJar() {
        try {
            return Path.of(BuiltinMemory.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (Exception e) {
            // Only used when no launcher is on PATH; a missing answer there is reported by install.
            return Path.of("oss.jar");
        }
    }

    // ------------------------------------------------------------------- doctor ---

    /**
     * Is the memory actually working — not "is it configured".
     *
     * <p>{@code oss doctor} answers everything about the install and nothing about this: whether the
     * archive is reachable, whether the scheduled harvest is succeeding, whether the job is even
     * loaded. A sibling tool's daily job failed for four days into a log nobody read, and the reason
     * nobody read it is that no command asked.
     */
    /** One line of the health report: what was checked, how it came out, and what to do about it. */
    public record Check(String name, Status status, String detail, String advice) {

        public enum Status {
            OK("  ok  "),
            WARN(" warn "),
            FAIL(" fail ");

            private final String label;

            Status(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }
    }

    /**
     * The health of the memory, as values rather than as printed lines.
     *
     * <p>Returned rather than printed so a test can call <em>this</em> instead of restating the
     * rules and then agreeing with itself. Every rule below is one that is easy to get backwards,
     * and getting one backwards means telling somebody their working install is broken or their
     * broken one is fine.
     */
    public static List<Check> health(KnowledgePack pack) {
        List<Check> out = new ArrayList<>();
        Path archive = pack.archive();
        boolean reachable = Files.isDirectory(archive);
        // A fresh install has no ~/.oss-cli/memory yet, and that is not a fault -- it is what
        // "nothing filed yet" looks like. Reporting it as a failure would greet every new user with
        // a red line about a folder they were never asked to make. A folder somebody DID name in
        // kb.json and that is missing is a different matter: for a synced archive it means the
        // archive has not downloaded, and that is worth acting on.
        boolean configured = !archive.equals(KnowledgePack.DEFAULT_ARCHIVE);
        if (reachable) {
            out.add(new Check("archive", Check.Status.OK, archive.toString(), ""));
            if (configured) {
                // Two folders, one command set, and until now the report named only one of them.
                // `map`, `coverage`, `gaps` and `digest` measure the archive kb.json points at;
                // `file`, `harvest`, `import` and `search` keep their working copies here, which is
                // also what `sync --me` embeds. Both are true and the pair is not guessable, so
                // doctor says it rather than leaving somebody to find out by filing a note and
                // looking for it in the wrong place.
                out.add(new Check(
                        "filed here",
                        Check.Status.OK,
                        DIR + " — " + countNotes(DIR) + " note(s)",
                        "kb.json's archive is what map/coverage/gaps/digest measure;"
                                + " what you file lives here and is what sync --me embeds"));
            }
            long notes = countNotes(archive);
            out.add(new Check(
                    "notes",
                    notes > 0 ? Check.Status.OK : Check.Status.WARN,
                    notes + " markdown file(s)",
                    notes > 0 ? "" : "oss memory file <path.md>   puts the first one there"));
        } else if (configured) {
            out.add(new Check(
                    "archive",
                    Check.Status.FAIL,
                    archive.toString(),
                    "kb.json names this folder and it is not there right now — "
                            + "a synced archive that has not downloaded looks exactly like this"));
        } else {
            out.add(new Check(
                    "archive",
                    Check.Status.WARN,
                    archive.toString(),
                    "nothing filed yet — this folder appears when you file the first note: "
                            + "oss memory file <path.md>"));
        }

        // A filed copy is a copy, and the source keeps moving. Nothing used to compare the two, so
        // a note filed in the morning and edited three commits later went on answering searches
        // with the morning's text -- and looked healthy doing it. Only tracked notes carry the
        // digest this reads, so an untracked store reports nothing here rather than a warning it
        // cannot act on.
        out.addAll(driftChecks());

        long items = countNotes(DIR.resolve("harvest")) + countNotes(DIR.resolve("sessions"));
        out.add(new Check(
                "harvested",
                items > 0 ? Check.Status.OK : Check.Status.WARN,
                items + " item(s)",
                items > 0 ? "" : "oss memory harvest --sessions"));

        // Written is not the same as searchable, and nothing said which you had.
        //
        // Only `sync --me` turns a note into a vector. `harvest` writes markdown -- so does `file`,
        // so does an archive extension, so does a Claude Code session filing a PR review into the
        // archive -- and until the embedding step runs, none of it reaches `chat`, `guide`, `pick`
        // or `prompt`. The daily job runs `memory harvest` and stops there, so a machine can
        // harvest every morning for a month and answer from none of it.
        //
        // Measured on this store when the check was written: 23 PR reviews on disk, 19 embedded.
        // The four newest -- the ones you would actually ask about -- were invisible to every
        // command that answers.
        // Counted against what sync actually READS, not against the whole archive.
        //
        // The first version of this check compared rows in personal_chat_memory to every .md under
        // kb.json's archive, and warned for ever: `sync --me` walks the folders in drive.paths and
        // the built-in store, and kb.json's archive is a different set. Measured after a full sync
        // with zero read failures, it still said "1691 of 1825", which is a check that cannot be
        // satisfied -- and a warning that never clears is one people learn to skip.
        //
        // The gap it was groping at is real and worth naming precisely: 153 notes sit in the
        // archive but outside every folder drive.paths lists -- the archive root, Personal/, Blog/
        // -- so nothing indexes them and nothing said so.
        long outside = notesOutsideTheIndexedFolders(archive);
        if (outside > 0) {
            out.add(new Check(
                    "not indexed",
                    Check.Status.WARN,
                    outside + " note(s) in the archive are outside drive.paths",
                    "nothing reads those folders, so nothing can find them — oss setup, "
                            + "or point drive.paths at the archive itself"));
        }

        String last = com.osscli.schedule.DailyJob.lastRun();
        if (last == null) {
            out.add(new Check("last run", Check.Status.WARN, "never recorded", ""));
        } else {
            String[] parts = last.split("\t", 3);
            boolean ok = parts.length > 0 && "ok".equals(parts[0]);
            out.add(new Check(
                    "last run",
                    ok ? Check.Status.OK : Check.Status.FAIL,
                    (parts.length > 1 ? parts[1] : "?") + (parts.length > 2 ? " · " + parts[2] : ""),
                    ok ? "" : com.osscli.schedule.DailyJob.errLog().toString()));
        }

        boolean installed = com.osscli.schedule.DailyJob.isInstalled();
        boolean running = installed && com.osscli.schedule.DailyJob.running();
        if (!installed) {
            out.add(new Check("schedule", Check.Status.WARN, "not installed", "oss memory schedule --install"));
        } else if (running) {
            out.add(new Check(
                    "schedule",
                    Check.Status.OK,
                    "loaded, " + com.osscli.schedule.DailyJob.descriptor().getFileName(),
                    ""));
        } else {
            // The gap between "the file is on disk" and "the system is holding it" is exactly where
            // a dead agent hides: a check for the file alone reports everything as fine.
            out.add(new Check(
                    "schedule",
                    Check.Status.FAIL,
                    "installed but NOT loaded",
                    com.osscli.schedule.DailyJob.descriptor().toString()));
        }
        return out;
    }

    /**
     * Is the memory actually working — not "is it configured".
     *
     * <p>{@code oss doctor} answers everything about the install and nothing about this: whether the
     * archive is reachable, whether the scheduled harvest is succeeding, whether the job is even
     * loaded. A sibling tool's daily job failed for four days into a log nobody read, and the reason
     * nobody read it is that no command asked.
     */
    private static int doctor() {
        System.out.println();
        System.out.println("  oss memory doctor");
        System.out.println("  ─────────────────────────────────────────────────────────────");
        List<Check> checks = health(KnowledgePack.load());
        for (Check c : checks) {
            System.out.printf("  [%s] %s — %s%n", c.status().label(), c.name(), c.detail());
            if (!c.advice().isBlank()) {
                System.out.println("           " + c.advice());
            }
        }
        System.out.println("  ─────────────────────────────────────────────────────────────");
        // Reported, not thrown. A health check that exits non-zero on a warning turns "you have not
        // filed anything yet" into a failed command in somebody's shell prompt.
        long bad = checks.stream().filter(c -> c.status() == Check.Status.FAIL).count();
        System.out.println(bad == 0 ? "  Nothing is broken." : "  " + bad + " thing(s) need attention.");
        return 0;
    }

    /**
     * Tracked notes measured against the files they were taken from.
     *
     * <p>Three states worth telling apart, because the fix differs: the source is gone (the copy is
     * now the only record and nothing will ever refresh it), the source has changed (one command
     * fixes it), or everything agrees. A store with no tracked notes produces no check at all —
     * silence is the honest report when there is nothing to compare.
     */
    static List<Check> driftChecks() {
        return driftChecks(DIR);
    }

    /** The same rules against a named folder, so a test can build a store and check the verdict. */
    static List<Check> driftChecks(Path dir) {
        List<Path> tracked = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.list(dir)) {
            for (Path note : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .toList()) {
                Map<String, String> front;
                try {
                    front = PackNotes.frontMatter(Files.readString(note, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    continue;
                }
                String source = front.get("source");
                String repo = front.get("repo");
                String sha = front.get("sha256");
                if (source == null || repo == null || sha == null) {
                    continue;
                }
                tracked.add(note);
                Path origin = Path.of(repo).resolve(source);
                if (!Files.isRegularFile(origin)) {
                    missing.add(source);
                    continue;
                }
                try {
                    if (!PackNotes.sha256(Files.readString(origin, StandardCharsets.UTF_8)).equals(sha)) {
                        stale.add(source);
                    }
                } catch (IOException e) {
                    missing.add(source);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        if (tracked.isEmpty()) {
            return List.of();
        }
        List<Check> out = new ArrayList<>();
        if (missing.isEmpty() && stale.isEmpty()) {
            out.add(new Check("tracked", Check.Status.OK, tracked.size() + " note(s) match their sources", ""));
            return out;
        }
        if (!stale.isEmpty()) {
            out.add(new Check(
                    "tracked",
                    Check.Status.WARN,
                    stale.size() + " of " + tracked.size() + " changed since filing: " + String.join(", ", stale),
                    "oss memory track   refiles them from source"));
        }
        if (!missing.isEmpty()) {
            out.add(new Check(
                    "tracked",
                    Check.Status.FAIL,
                    missing.size() + " source(s) gone: " + String.join(", ", missing),
                    "the filed copy is now the only record — move it somewhere permanent, "
                            + "or delete it if the finding went with the branch"));
        }
        return out;
    }

    /** Markdown files under a folder, or zero when it is not there. Never throws for an absent one. */
    static long countNotes(Path folder) {
        if (!Files.isDirectory(folder)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .count();
        } catch (IOException e) {
            return 0L;
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
            Path measured = KnowledgePack.load().archive();
            if (!measured.equals(DIR)) {
                // Said here because this is the moment somebody wonders where their note went.
                // kb.json names an archive, and it is a fair reading that filing puts notes in it.
                System.out.println("  (kb.json's archive — " + measured + " — is what coverage measures;");
                System.out.println("   what you file lives above, and sync --me embeds it)");
            }
        }
        return filed > 0 ? 0 : 1;
    }

    // -------------------------------------------------------------------- track ---

    /**
     * File every note a repository carries, and keep the copies pointed at their sources.
     *
     * <p>{@code file} is the one-off; this is the repeat. It walks a checkout, files what it finds
     * under the note's own title rather than its filename, and stamps each copy with the path,
     * commit and digest it came from. Running it again is the whole point: unchanged notes are
     * skipped, changed ones are refreshed, and {@code doctor} can say which sources have moved
     * ahead of their copies in between.
     *
     * <p>Idempotent by construction — the name comes from the title, so the second run overwrites
     * the first rather than leaving a dated pair for somebody to reconcile.
     */
    private static int track(List<String> args) throws IOException {
        boolean all = args.contains("--all");
        boolean dry = args.contains("--dry-run");
        Path root = args.stream()
                .filter(a -> !a.startsWith("--"))
                .findFirst()
                .map(Path::of)
                .orElse(Path.of(""))
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("error  not a directory: " + root);
            return 2;
        }
        List<Path> sources = PackNotes.discover(root, all);
        if (sources.isEmpty()) {
            System.out.println("  no notes found under " + root);
            if (!all) {
                System.out.println("  looked in: " + String.join(", ", PackNotes.DEFAULT_FOLDERS));
                System.out.println("  oss memory track --all    every markdown outside the build folders");
            } else if (!PackNotes.looksTrackable(root)) {
                System.out.println("  (this is not a pack or a git checkout — is it the folder you meant?)");
            }
            return 0;
        }

        Files.createDirectories(DIR);
        String commit = headCommit(root);
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (Path source : sources) {
            PackNotes.Found found = PackNotes.examine(root, source);
            Path dst = DIR.resolve(found.slug() + ".md");
            String was = Files.isRegularFile(dst)
                    ? PackNotes.frontMatter(Files.readString(dst, StandardCharsets.UTF_8))
                            .getOrDefault("sha256", "")
                    : null;
            if (found.sha().equals(was)) {
                unchanged++;
                continue;
            }
            if (!dry) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("title", found.title());
                fields.put("source", found.relative());
                fields.put("repo", root.toString());
                if (!commit.isBlank()) {
                    fields.put("commit", commit);
                }
                fields.put("sha256", found.sha());
                fields.put("tracked", LocalDate.now(ZoneOffset.UTC).toString());
                Files.writeString(
                        dst,
                        PackNotes.withProvenance(Files.readString(source, StandardCharsets.UTF_8), fields),
                        StandardCharsets.UTF_8);
            }
            System.out.printf("  %-9s %s%n", was == null ? "filed" : "refreshed", found.relative());
            if (was == null) {
                added++;
            } else {
                updated++;
            }
        }

        System.out.println();
        System.out.printf(
                "  %d note(s) — %d new, %d refreshed, %d already current%n",
                sources.size(), added, updated, unchanged);
        if (dry) {
            System.out.println("  --dry-run, nothing written");
            return 0;
        }
        if (added + updated > 0) {
            System.out.println("  indexing what changed…");
            indexTheArchive();
        }
        System.out.println("  oss memory search \"<terms>\"     oss memory doctor   checks them against their sources");
        return 0;
    }

    /**
     * The commit a tracked copy was taken at, or empty when there is no git here.
     *
     * <p>Forgiving on purpose: a note filed out of a plain folder is still worth having, and
     * failing the whole walk because one checkout has no {@code .git} would make the provenance
     * stamp the enemy of the thing it documents.
     */
    private static String headCommit(Path root) {
        try {
            Process p = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(false)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .strip();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
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
        // Ranked by passage, reported by note. Printing every passage listed the same file three
        // times at three scores under a heading that said "note(s)" -- which reads as three
        // separate pieces of writing when it is one, and pushes the other matches off the list.
        Map<String, TextIndex.Hit> best = new LinkedHashMap<>();
        for (TextIndex.Hit h : hits) {
            String file = h.id().substring(0, h.id().lastIndexOf('#'));
            TextIndex.Hit seen = best.get(file);
            if (seen == null || h.score() > seen.score()) {
                best.put(file, h);
            }
        }
        System.out.println("  " + best.size() + " of " + notes.size() + " note(s), by shared terms");
        System.out.println();
        for (Map.Entry<String, TextIndex.Hit> e : best.entrySet()) {
            System.out.printf("  %.2f  %s%n", e.getValue().score(), e.getKey());
            if (!e.getValue().title().isBlank()) {
                System.out.println("        " + e.getValue().title());
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
            // Same rule as the term path: one line per note, at its best passage.
            Map<String, Corpus.Hit> best = new LinkedHashMap<>();
            for (Corpus.Hit h : hits) {
                String file = h.id().startsWith("note:") ? h.id().substring(5) : h.id();
                Corpus.Hit seen = best.get(file);
                if (seen == null || h.score() > seen.score()) {
                    best.put(file, h);
                }
            }
            System.out.println("  " + best.size() + " of " + noteCount + " note(s), by meaning");
            System.out.println();
            for (Map.Entry<String, Corpus.Hit> e : best.entrySet()) {
                System.out.printf("  %.2f  %s%n", e.getValue().score(), e.getKey());
                if (!e.getValue().title().isBlank()) {
                    System.out.println("        " + e.getValue().title());
                }
            }
            return 0;
        } catch (Exception e) {
            // Ranking by meaning is the better answer, not the only one.
            return null;
        }
    }

    // -------------------------------------------------------------------- index ---

    private static int index(List<String> args) throws IOException {
        List<Note> notes = load();
        int passages = 0;
        for (Note n : notes) {
            passages += PassageSplitter.split(n.body).size();
        }
        System.out.println("  " + notes.size() + " note(s), " + passages + " passage(s) in " + DIR);
        // Stated rather than implied: the term index is built per search, so there is nothing to
        // rebuild and nothing that can go stale. Saying so stops anyone hunting for a refresh
        // command.
        System.out.println("  The index is built as you search, so there is nothing to keep current.");
        // The vector index is the opposite, and that difference is the whole of this block. It is
        // written once per note and never revisited, so a note that moves stays in it at its old
        // path for ever -- and a folder rename produces two copies of every note in it, identical
        // text, both answering. Nothing had ever removed anything from it.
        pruneMovedNotes(args.contains("--forget-missing"));
        indexTheArchive();
        return 0;
    }

    /**
     * Embed every note in every configured folder.
     *
     * <p>This command used to count notes and say the index needed no maintenance, which was true
     * of the term index and false of the vector one. Measured on a real store afterwards: 965 notes
     * in the archive and 269 of them in no index at all -- every digest at the root, everything
     * under {@code Blog} and {@code Reference}. Searchable by term, invisible to anything that
     * answers by meaning, and nothing said so.
     *
     * <p>The gap existed because each writer indexed only what it had just written. Nothing ever
     * swept the whole archive except {@code sync --me}, which is a different command that people
     * run for a different reason.
     *
     * <p>Cheap on a second run: {@code NoteIndexer} skips a file whose content and embedding model
     * are already stored, so this costs a walk and a hash for everything that has not changed.
     */
    private static void indexTheArchive() {
        List<String> folders = new ArrayList<>();
        for (Path root : com.osscli.retrieval.StaleNotes.configuredRoots()) {
            if (Files.isDirectory(root) && !folders.contains(root.toString())) {
                folders.add(root.toString());
            }
        }
        if (folders.isEmpty()) {
            return;
        }
        com.osscli.retrieval.LocalEmbedder embedder = com.osscli.retrieval.Embeddings.ifPresent(m -> {});
        if (embedder == null) {
            com.osscli.ui.Out.note("no embedding model, so these are searchable by term and not by meaning");
            com.osscli.ui.Out.hint("oss model --fetch", "22 MB, once");
            return;
        }
        try (com.osscli.ui.Live live = com.osscli.ui.Live.start("indexing your notes")) {
            live.step(String.join(", ", folders));
            com.osscli.retrieval.NoteIndexer.index(folders, embedder, com.osscli.retrieval.Embeddings.MODEL);
        }
        com.osscli.ui.Out.ok("every configured folder is embedded — ask, chat, guide, pick and prompt can see them");
    }

    /**
     * Drop index entries for notes that are genuinely gone.
     *
     * <p>Never for notes that are merely unreachable. A missing file and an unmounted folder look
     * identical from here, and this archive spent a year in iCloud where unreachable is an ordinary
     * afternoon. Deleting hundreds of notes because a folder was slow to appear would be a
     * permanent answer to a temporary problem, so an absent folder protects everything under it.
     */
    private static void pruneMovedNotes(boolean forgetMissing) {
        try {
            List<String> indexed = com.osscli.storage.SqliteStorage.indexedNotePaths();
            com.osscli.retrieval.StaleNotes.Sweep sweep =
                    com.osscli.retrieval.StaleNotes.sweep(indexed, com.osscli.retrieval.StaleNotes.configuredRoots());
            if (!sweep.gone().isEmpty()) {
                int forgotten = com.osscli.retrieval.StaleNotes.forget(sweep.gone());
                com.osscli.ui.Out.ok(forgotten + " note(s) that had moved or been deleted were dropped from the index");
            }

            // Everything outside every configured folder. Normally untouchable, because "not under
            // a folder I know about" is what an unmounted disk looks like. But an archive that has
            // genuinely moved leaves its whole previous location behind, and those rows answer
            // searches for ever with text from files nobody can open.
            List<String> outside = com.osscli.retrieval.StaleNotes.outside(indexed, sweep);
            if (outside.isEmpty()) {
                return;
            }
            if (!forgetMissing) {
                com.osscli.ui.Out.note(
                        outside.size()
                                + " indexed note(s) are missing and sit outside every configured folder — an archive that moved");
                com.osscli.ui.Out.hint(
                        "oss memory index --forget-missing", "drop them once you are sure the move was intended");
                return;
            }
            int forgotten = com.osscli.retrieval.StaleNotes.forget(outside);
            com.osscli.ui.Out.ok(forgotten + " note(s) from a previous archive location were dropped from the index");
        } catch (java.sql.SQLException e) {
            // A tidy-up that fails is worth a line, not a failed command.
            com.osscli.ui.Out.warn("could not check the index for moved notes: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------------- util ---

    private static final class Note {
        String name = "";
        String title = "";
        String body = "";
    }

    /**
     * Every note in the store, however deep.
     *
     * <p><b>Walk, not list.</b> This listed one level, and everything that writes a note writes it
     * into a subfolder — {@code harvest/}, {@code sessions/}, {@code imported/}, {@code gaps/}. So
     * {@code search} found none of them: a harvest could collect a thousand items, report a
     * thousand items, and the next search would answer "nothing filed yet". The compounding loop
     * this whole tool is built on was open at the join.
     *
     * <p>The name carries the folder, so two notes with the same file name in different folders are
     * two notes rather than one silently winning.
     */
    private static List<Note> load() throws IOException {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(DIR)) {
            for (Path p : s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                Note n = new Note();
                n.name = DIR.relativize(p).toString();
                try {
                    n.body = Files.readString(p);
                } catch (IOException e) {
                    // One unreadable note must not cost the other nine hundred. A synced archive
                    // always has one that has not downloaded, and that read fails with a timeout.
                    continue;
                }
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
