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
package com.osscli.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import com.osscli.ext.ExtensionRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * OSS-CLI as a service you install once and attach things to.
 *
 * <p>Install it once; it runs locally and stays running; you add capabilities to it without
 * rebuilding or restarting anything. The palette entries are extensions -- a {@code bench} that
 * runs something real, a {@code kb} that remembers -- and adding one means pasting the path of a
 * directory that contains an {@code oss-ext.json}.
 *
 * <p>The point is not the web page. The page is only the least ceremonious way to paste a path and
 * see what is attached; everything it does is equally available as {@code oss ext …}. What
 * matters is that <b>the set of things this can run against is open, and grows from other people's
 * machines</b> -- someone writes one file in their own repository and their bench is in the list.
 *
 * <p>That is the whole attach story, and it is deliberately dull: anyone who has a Kafka setup, or a
 * Spring project, or anything else worth running against, writes that one file in their own
 * repository and pastes the path. Nothing is uploaded, nothing is copied, and the extension stays
 * where it is and keeps being an ordinary repository.
 *
 * <p><b>Why the board is here and not in a bench.</b> It was in one, and it was right to be: the
 * review ledger, the pull-request state and the triage results all lived in a Log4j bench, and a
 * page there was the only way to see them. The core had none of it, so a board in the core would
 * have had nothing to draw.
 *
 * <p>That stopped being true. The ledger moved here, and the corpus grew to fifteen thousand issues
 * across ten repositories — at which point the bench's page was reading a smaller copy of what this
 * already held, three repositories where the core had ten. A board over one project's playground
 * can only ever see that playground.
 *
 * <p>So it moved, by the rule that moved follow-up before it: <i>being inside a Log4j bench meant a
 * capability that works against any repository could only be reached by attaching that bench.</i>
 * What stays in a bench is what a bench can uniquely answer — does this actually work, on a real
 * JVM, across versions and configurations. No amount of corpus replaces running the thing. And
 * nothing that posts came across, because a browser has no terminal to confirm an outward write at.
 *
 * <p>Three decisions worth stating, because each is a thing this deliberately does NOT do:
 *
 * <ul>
 *   <li><b>It binds to loopback only.</b> Not a configuration option. This process can start other
 *       programs on the machine, which is exactly the thing that must not be reachable from a
 *       network, and a bind address that can be widened is one that eventually is.
 *   <li><b>It never dispatches a verb.</b> The palette attaches, detaches and reports; it does not
 *       run anything. A browser has no terminal, and an outward write must be confirmed at one --
 *       so rather than build a path that could only ever be refused, running stays on the CLI where
 *       the confirmation can actually happen.
 *   <li><b>It holds no state of its own.</b> Every request re-reads the registry from disk, so a
 *       change made on the CLI shows up on the next reload and the page can never disagree with
 *       what {@code oss ext list} would say.
 * </ul>
 */
@Command(
        name = "serve",
        mixinStandardHelpOptions = true,
        description = "Run OSS-CLI as a local service with a palette of attached extensions")
public class ServeCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 1504 by default: fixed, so a service you install once is one you return to by typing the
     * same address rather than looking it up. */
    @Option(names = "--port", description = "Port to listen on (default: ${DEFAULT-VALUE})")
    int port = 1504;

    @Option(names = "--no-open", description = "Do not open a browser")
    boolean noOpen;

    @Option(names = "--install", description = "Keep it running: start at login and restart if it dies")
    boolean install;

    @Option(names = "--uninstall", description = "Stop starting it at login")
    boolean uninstall;

    /**
     * Remembers that the "keep this running?" question was already asked.
     *
     * <p>Asked once, not once per start. A prompt that reappears every time is one people learn to
     * dismiss without reading, and this one has a real answer either way.
     */
    private static Path askedMarker() {
        return com.osscli.AppPaths.BASE_DIR.resolve("serve-autostart-asked");
    }

    @Override
    public Integer call() throws Exception {
        if (uninstall) {
            return doUninstall();
        }
        if (install) {
            return doInstall() ? 0 : 1;
        }
        HttpServer server;
        try {
            // Loopback explicitly, rather than the wildcard address: this process can start other
            // programs, so it must not be reachable from anywhere but this machine.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            System.err.println("error  could not listen on port " + port + ": " + e.getMessage());
            // "Another instance may already be serving" was a guess, and on the machine this was
            // found on it was the wrong one: `oss run hub` defaults to this same port, so the thing
            // holding it was another surface of this tool rather than a second copy of this one.
            // Asking what is there costs one request to loopback and turns a guess into a fact.
            String occupant = whoIsOn(port);
            if (occupant != null) {
                System.err.println("       " + occupant + " is already on http://localhost:" + port + "/");
                System.err.println("       Leave it, or serve this alongside it: --port " + (port + 1));
            } else {
                System.err.println("       Something else holds that port. Try --port " + (port + 1) + ".");
            }
            return 1;
        }

        server.createContext("/", this::handlePage);
        server.createContext("/docs", this::handleDocs);
        server.createContext("/api/extensions", this::handleList);
        server.createContext("/api/questions", this::handleQuestions);
        server.createContext("/api/rows", this::handleRows);
        server.createContext("/api/ask", this::handleAsk);
        server.createContext("/api/attach", this::handleAttach);
        server.createContext("/api/detach", this::handleDetach);
        server.setExecutor(askPool());
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println("oss serving on " + url + "   (ctrl-c to stop)");
        System.out.println("  attach an extension: paste the path of a repo containing oss-ext.json");
        if (!noOpen) {
            openBrowser(url);
        }
        offerAutostart();
        // The HttpServer runs on its own threads; park this one rather than returning, which would
        // exit the JVM and take the server with it.
        Thread.currentThread().join();
        return 0;
    }

    // ---------------------------------------------------------------- handlers ---

    /**
     * What is answering on a port, named from its own page.
     *
     * <p>Only ever loopback, and only after this process has already failed to bind it: the port is
     * in use by something on this machine and the question is what. A title is enough to tell one
     * surface of this tool from another -- {@code oss run hub} serves "oss run hub" and this serves
     * "oss" -- and enough to say "something else" honestly when it is neither.
     */
    static String whoIsOn(int port) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net
                    .URI
                    .create("http://localhost:" + port + "/")
                    .toURL()
                    .openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            String body;
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] head = in.readNBytes(4096);
                body = new String(head, java.nio.charset.StandardCharsets.UTF_8);
            }
            return titleOf(body);
        } catch (Exception e) {
            // Not answering, not HTTP, or refusing us. Either way there is nothing to name.
            return null;
        }
    }

    /** The document title, as a name for whatever is serving. */
    static String titleOf(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "<title>\\s*([^<]{1,60}?)\\s*</title>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html == null ? "" : html);
        return m.find() ? "\"" + m.group(1) + "\"" : null;
    }

    private void handlePage(HttpExchange x) throws IOException {
        if (!"/".equals(x.getRequestURI().getPath())) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        send(x, 200, "text/html; charset=utf-8", PAGE);
    }

    /**
     * The documentation, on the same page as the board.
     *
     * <p>Read from inside the jar rather than from disk. The board has to work from a Homebrew
     * install and from an unpacked archive, and neither carries this checkout — the distribution
     * copies README.md beside the jar and nothing else, so reading from the filesystem would give a
     * page that is complete on the machine that built it and half empty everywhere it ships.
     */
    private static final java.util.List<String> DOCS =
            java.util.List.of("README.md", "COMMANDS.md", "OFFLINE.md", "SETUP.md", "CONTRIBUTING.md");

    private void handleDocs(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String wanted = path.length() > "/docs/".length() ? path.substring("/docs/".length()) : "README.md";
        if (!DOCS.contains(wanted)) {
            // The list, not a 404 with nothing in it: somebody who mistyped one is one link away.
            send(x, 404, "text/html; charset=utf-8", docPage("Not a document", "<p>Try: " + docLinks() + "</p>"));
            return;
        }
        try (java.io.InputStream in = ServeCommand.class.getResourceAsStream("/docs/" + wanted)) {
            if (in == null) {
                send(
                        x,
                        500,
                        "text/html; charset=utf-8",
                        docPage(wanted, "<p>This build does not carry " + wanted + ".</p>"));
                return;
            }
            String markdown = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            send(x, 200, "text/html; charset=utf-8", docPage(wanted, onThisMachine() + Markdown.toHtml(markdown)));
        }
    }

    /**
     * What the documents mean on this particular machine.
     *
     * <p>The files themselves ship generic and stay that way — they say {@code owner/name} because
     * a worked example naming somebody's project reads as "this tool is for that project", which is
     * this repository's oldest rule. But a reader looking at {@code owner/name} still has to
     * translate it, and the machine already knows what it would be for them.
     *
     * <p>So the substitution happens here, at render time, from their own store: the same document
     * says something different to each reader without a personal word ever being written into it.
     * Nothing is shown when nothing is synced, because a panel of zeroes teaches nobody anything.
     */
    private static String onThisMachine() {
        java.util.List<String> repos;
        try {
            repos = com.osscli.storage.SqliteStorage.loadMonitoredRepositories();
        } catch (Exception e) {
            return "";
        }
        if (repos.isEmpty()) {
            return "<div class=\"mine\"><strong>Nothing synced yet.</strong> Where these pages say "
                    + "<code>owner/name</code>, that will be whatever you add: "
                    + "<code>oss sync --add owner/name</code>.</div>";
        }
        StringBuilder b = new StringBuilder("<div class=\"mine\"><strong>On this machine</strong> — where these "
                + "pages say <code>owner/name</code>, yours are: ");
        for (int i = 0; i < Math.min(3, repos.size()); i++) {
            b.append(i > 0 ? ", " : "").append("<code>").append(repos.get(i)).append("</code>");
        }
        if (repos.size() > 3) {
            b.append(" and ").append(repos.size() - 3).append(" more");
        }
        b.append(". ").append(counts()).append("</div>");
        return b.toString();
    }

    /** The reader's own numbers, so the pages describe their corpus rather than an imagined one. */
    private static String counts() {
        StringBuilder b = new StringBuilder();
        long issues = one("SELECT count(*) FROM issues;");
        long notes = one("SELECT count(*) FROM personal_chat_memory;");
        long asked = one("SELECT count(*) FROM chat_turn;");
        if (issues > 0) {
            b.append(issues).append(" issues");
        }
        if (notes > 0) {
            b.append(b.length() > 0 ? " · " : "").append(notes).append(" notes");
        }
        if (asked > 0) {
            b.append(b.length() > 0 ? " · " : "").append(asked).append(" questions asked here");
        }
        return b.toString();
    }

    private static long one(String sql) {
        try (java.sql.Connection conn = com.osscli.storage.DatabaseManager.getConnection();
                java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String docLinks() {
        StringBuilder b = new StringBuilder();
        for (String d : DOCS) {
            b.append("<a href=\"/docs/")
                    .append(d)
                    .append("\">")
                    .append(d.replace(".md", ""))
                    .append("</a> ");
        }
        return b.toString();
    }

    /** One document, wearing the same palette as the board it is served beside. */
    private static String docPage(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + " · oss</title><style>"
                + ":root{--bg:#faf9f5;--fg:#141413;--soft:#5b5a55;--rule:#e3e1d9;--acc:#8a6d1f;--card:#fff}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#12120f;--fg:#eceae1;--soft:#a5a294;"
                + "--rule:#2b2a24;--acc:#d9ba6a;--card:#1a1a16}}"
                + "*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);margin:0;"
                + "font:16px/1.65 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:2rem 1.25rem 5rem}"
                + ".w{max-width:46rem;margin:0 auto}nav{display:flex;flex-wrap:wrap;gap:.9rem;"
                + "border-bottom:1px solid var(--rule);padding-bottom:.9rem;margin-bottom:2rem;font-size:.9rem}"
                + "nav a{color:var(--acc);text-decoration:none}nav a:hover{text-decoration:underline}"
                + "h1,h2,h3{line-height:1.25;margin:2rem 0 .6rem}h1{font-size:1.9rem}h2{font-size:1.35rem}"
                + "h3{font-size:1.05rem}p,li{color:var(--fg)}code{background:var(--card);border:1px solid var(--rule);"
                + "padding:.08em .35em;border-radius:3px;font-size:.88em;"
                + "font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
                + "pre{background:var(--card);border:1px solid var(--rule);border-left:3px solid var(--acc);"
                + "border-radius:3px;padding:.9rem 1rem;overflow-x:auto}pre code{background:none;border:0;padding:0}"
                + "table{border-collapse:collapse;width:100%;display:block;overflow-x:auto}"
                + "th,td{border-bottom:1px solid var(--rule);padding:.5rem .7rem;text-align:left;vertical-align:top}"
                + "th{font-size:.78rem;text-transform:uppercase;letter-spacing:.06em;color:var(--soft)}"
                + "hr{border:0;border-top:1px solid var(--rule);margin:2rem 0}a{color:var(--acc)}"
                + ".mine{background:var(--card);border:1px solid var(--rule);border-left:3px solid var(--acc);"
                + "border-radius:3px;padding:.7rem .9rem;margin:0 0 1.5rem;font-size:.9rem}"
                + "</style></head><body><div class=\"w\"><nav><a href=\"/\">← board</a>" + docLinks()
                + "</nav>" + body + "</div></body></html>";
    }

    private void handleList(HttpExchange x) throws IOException {
        sendJson(x, 200, Map.of("extensions", snapshot()));
    }

    /**
     * The reviewed pull requests, as rows a page can hang a question on.
     *
     * <p>Read from the same ledger {@code oss followup} and {@code oss hub} read, not from a second
     * copy of the logic — one implementation, so the page and the command cannot drift apart.
     *
     * <p>Rows rather than text because the question goes <b>where it is asked</b>: "seen this?"
     * belongs on every row, and "since I reviewed" only where a verdict exists, since anywhere else
     * it would answer about nothing.
     */
    private void handleRows(HttpExchange x) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.osscli.review.ReviewLedger.Row r : com.osscli.review.ReviewLedger.read()) {
            rows.add(Map.of(
                    "repo", r.repo,
                    "pr", r.pr,
                    "verdict", r.verdict,
                    "reviewed", r.reviewed,
                    "author", r.author,
                    "posted", r.posted,
                    "note", r.note,
                    // A verdict is what makes "since I reviewed" answerable at all.
                    "hasVerdict", hasVerdict(r.verdict)));
        }
        sendJson(x, 200, Map.of("rows", rows));
    }

    /**
     * Whether a row was actually reviewed.
     *
     * <p>The ledger writes {@code "none"} for a row that has been recorded but not judged, so a
     * blank check alone would draw "since I reviewed" on rows where there is no verdict for
     * "since" to be measured from — a button that answers about nothing.
     */
    static boolean hasVerdict(String verdict) {
        return verdict != null && !verdict.isBlank() && !"none".equals(verdict.strip());
    }

    /** The questions this page can ask, with the sentence each one answers. */
    private void handleQuestions(HttpExchange x) throws IOException {
        sendJson(x, 200, Map.of("questions", questionsPayload()));
    }

    /**
     * The questions as the page receives them.
     *
     * <p>{@code runs} is the command spelled the way somebody would type it, because it is shown in
     * the hover beside what the button asks — a reader can then run the same thing in a terminal
     * and get the same answer, which is the claim the whole page rests on.
     */
    static List<Map<String, Object>> questionsPayload() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Askable.Question q : Askable.all()) {
            out.add(Map.of(
                    "key",
                    q.key(),
                    "asks",
                    q.asks(),
                    "runs",
                    "oss " + String.join(" ", q.argv()),
                    "arg",
                    q.arg() == null ? "" : q.arg()));
        }
        return List.copyOf(out);
    }

    /**
     * Run one question and return what came back.
     *
     * <p>The page never reimplements a command; it runs one and shows the output, so the two cannot
     * disagree. Only keys on {@link Askable} are runnable, and nothing on that table writes — which
     * is what makes dispatching from a browser defensible at all, given a browser has no terminal
     * to confirm an outward write at.
     */
    /**
     * The command's output without the lines it prints before it starts.
     *
     * <p>Every command opens with "Initializing local SQLite database connection...", which is
     * reasonable in a terminal and is noise in a browser: it was the first line of every answer on
     * this page, above the answer, on a page whose whole job is to show what came back.
     *
     * <p>Only leading lines, and only the ones a command prints about itself. Anything that appears
     * once the command is actually working is the answer and is left alone.
     */
    static String withoutStartupChatter(String output) {
        if (output == null) {
            return "";
        }
        List<String> lines = new ArrayList<>(List.of(output.split("\n", -1)));
        while (!lines.isEmpty()) {
            String first = lines.get(0).strip();
            boolean chatter = first.isEmpty()
                    || first.startsWith("Initializing local SQLite")
                    || first.startsWith("Upgrading database schema");
            if (!chatter) {
                break;
            }
            lines.remove(0);
        }
        return String.join("\n", lines).strip();
    }

    private void handleAsk(HttpExchange x) throws IOException {
        Map<String, String> params = query(x.getRequestURI().getRawQuery());
        Askable.Question q = Askable.byKey(params.get("q"));
        if (q == null) {
            // Not "unknown question": the page posts a key, and anything not on the table must not
            // become a command line.
            sendJson(x, 400, Map.of("error", "not a question this page can ask"));
            return;
        }

        List<String> argv = new ArrayList<>(ownExecutable());
        argv.addAll(q.argv());
        if (q.needsArgument()) {
            String given = params.getOrDefault("arg", "").strip();
            if (given.isEmpty()) {
                sendJson(x, 400, Map.of("error", "that question needs " + q.arg()));
                return;
            }
            argv.add(given);
        }

        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(q.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                sendJson(x, 200, Map.of("output", "", "note", "gave up after " + q.timeoutSeconds() + "s"));
                return;
            }
            String shown = withoutStartupChatter(out);
            sendJson(
                    x,
                    200,
                    Map.of(
                            "output",
                            shown,
                            "note",
                            shown.isBlank() ? q.empty() : "",
                            "runs",
                            "oss " + String.join(" ", q.argv())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(x, 500, Map.of("error", "interrupted"));
        }
    }

    /**
     * How to invoke this program again.
     *
     * <p>The running jar, not whatever {@code oss} the PATH offers: a page served by this build must
     * ask this build, or the answers belong to a different version from the one being looked at.
     */
    static List<String> ownExecutable() {
        try {
            String jar = java.nio.file.Path.of(ServeCommand.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
            String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString();
            if (jar.endsWith(".jar")) {
                return List.of(javaBin, "-jar", jar);
            }
        } catch (Exception e) {
            // Running from classes rather than a jar, or a code source we cannot resolve. The
            // installed command is the honest fallback; it may be a different build, which is why
            // it is the fallback rather than the first choice.
            System.err.println("  (could not locate the running jar: " + e.getMessage() + ")");
        }
        return List.of("oss");
    }

    /** Query parameters, decoded, with no assumption that any of them are present. */
    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(
                    java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private void handleAttach(HttpExchange x) throws IOException {
        try {
            Map<String, Object> req = readJson(x);
            String raw = String.valueOf(req.getOrDefault("path", "")).trim();
            if (raw.isEmpty()) {
                sendJson(x, 400, Map.of("error", "no path given"));
                return;
            }
            // The path came from a text box, not a shell, so ~ was never expanded.
            String path = raw.startsWith("~") ? System.getProperty("user.home") + raw.substring(1) : raw;
            Extension ext = ExtensionRegistry.readManifest(Path.of(path));
            boolean replaced = ExtensionRegistry.add(ext);
            sendJson(
                    x,
                    200,
                    Map.of(
                            "ok", true,
                            "name", ext.getName(),
                            "kind", ext.kind().lower(),
                            "replaced", replaced,
                            "extensions", snapshot()));
        } catch (RuntimeException e) {
            // The manifest reader's messages name the field or the file, which is what someone
            // pasting a wrong path needs; passing them through beats a generic failure.
            sendJson(x, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleDetach(HttpExchange x) throws IOException {
        try {
            Map<String, Object> req = readJson(x);
            String name = String.valueOf(req.getOrDefault("name", "")).trim();
            boolean removed = !name.isEmpty() && ExtensionRegistry.remove(name);
            // Detaching only forgets a path. Nothing under that path is touched, which is worth
            // saying on the page too -- "remove" reads like deletion.
            sendJson(
                    x,
                    removed ? 200 : 404,
                    Map.of(
                            "ok",
                            removed,
                            "error",
                            removed ? "" : "no extension named \"" + name + "\"",
                            "extensions",
                            snapshot()));
        } catch (RuntimeException e) {
            sendJson(x, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------ helpers ---

    /** The registry as the page wants it, re-read from disk every time. */
    private List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Extension e : ExtensionRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            m.put("kind", e.kind().lower());
            m.put("description", e.getDescription() == null ? "" : e.getDescription());
            m.put("root", e.getRoot());
            m.put("verbs", new ArrayList<>(e.getVerbs().keySet()));
            m.put("writes", e.getWrites());
            m.put("reachable", ExtensionRunner.isReachable(e));
            m.put("stale", ExtensionRegistry.isStale(e));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            byte[] body = in.readAllBytes();
            return body.length == 0 ? Map.of() : MAPPER.readValue(body, Map.class);
        }
    }

    private void sendJson(HttpExchange x, int code, Object payload) throws IOException {
        send(x, code, "application/json; charset=utf-8", MAPPER.writeValueAsString(payload));
    }

    private void send(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    /**
     * Ask, once, whether this should keep running -- and only when someone is there to answer.
     *
     * <p>Installing an agent that starts at login is not a thing to do on someone's behalf: it
     * outlives the terminal they typed into, survives reboots, and is invisible afterwards. So it is
     * offered rather than assumed, and never in a non-interactive run, where "no answer" would
     * otherwise be read as consent.
     */
    private void offerAutostart() {
        if (Autostart.isInstalled() || Files.exists(askedMarker())) {
            return;
        }
        Console console = System.console();
        if (console == null) {
            return;
        }
        System.out.println();
        System.out.println("  This stops when you close this terminal.");
        System.out.print("  Keep it running — start at login, restart if it dies? [y/N] ");
        String answer = console.readLine();
        boolean yes = answer != null && answer.trim().matches("(?i)y|yes");
        try {
            Files.createDirectories(askedMarker().getParent());
            // Record the question either way, so declining is respected rather than re-asked.
            Files.writeString(askedMarker(), (yes ? "installed" : "declined") + "\n");
        } catch (IOException ignored) {
            // Not being able to remember the answer is not a reason to fail the serve.
        }
        if (yes) {
            doInstall();
        } else {
            System.out.println("  Left as-is. Change your mind later with: oss serve --install");
        }
        System.out.println();
    }

    private boolean doInstall() {
        Path jar = jarPath();
        if (jar == null) {
            System.err.println("error  could not locate the running jar, so the service would not know what to start");
            return false;
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        try {
            String what = Autostart.install(java, jar, port);
            if (what == null) {
                System.err.println("error  " + Autostart.unsupportedAdvice(port));
                return false;
            }
            System.out.println("  ✓ starts at login — " + what);
            System.out.println("    stop it with: oss serve --uninstall");
            return true;
        } catch (IOException e) {
            System.err.println("error  could not install: " + e.getMessage());
            return false;
        }
    }

    private Integer doUninstall() {
        try {
            boolean had = Autostart.uninstall();
            System.out.println(had ? "  ✓ removed — it will not start at login" : "  nothing installed");
            // Clearing the marker means the question is asked again next time, which is the
            // reasonable behaviour after an explicit uninstall.
            Files.deleteIfExists(askedMarker());
            return 0;
        } catch (IOException e) {
            System.err.println("error  could not remove: " + e.getMessage());
            return 1;
        }
    }

    /** The jar this JVM is running, so the agent starts the same build. */
    private Path jarPath() {
        try {
            Path p = Path.of(ServeCommand.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return p.toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            String[] cmd = os.contains("mac")
                    ? new String[] {"open", url}
                    : os.contains("win")
                            ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                            : new String[] {"xdg-open", url};
            new ProcessBuilder(cmd).start();
        } catch (Exception ignored) {
            // Not being able to open a browser is not a reason to fail to serve.
        }
    }

    // --------------------------------------------------------------------- page ---
    // Self-contained: no CDN, no build step, and it renders with the registry it
    // fetches rather than one baked in at start.
    /**
     * Threads to answer on, because the default is one and one is not enough here.
     *
     * <p>{@code setExecutor(null)} hands every request to the single dispatcher thread, in order.
     * That is fine for a page of static HTML and wrong for this one: the board fires {@code hub} as
     * it loads, {@code hub} shells out to a real command that takes tens of seconds against ten
     * repositories, and until it returns <em>nothing else on the page responds</em> — not another
     * question, not the page itself on a second tab. Measured: the page did not load at all while
     * one ask was in flight, which reads as a hung service rather than as a slow command.
     *
     * <p>Small and bounded on purpose. Every ask starts a child {@code oss} process, so an unbounded
     * pool would let a browser with a heavy hand start a dozen JVMs on somebody's laptop. Six is
     * more than the page can usefully have in flight — it has one output panel per section — and
     * the queue behind it is the backpressure.
     */
    private static java.util.concurrent.ExecutorService askPool() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "oss-serve-" + n.incrementAndGet());
            // Daemon, so ctrl-c stops the service rather than leaving the JVM up on idle threads.
            t.setDaemon(true);
            return t;
        });
    }

    /** The page as it will be served, so a test can assert on what leads it. */
    static String page() {
        return PAGE;
    }

    private static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>oss</title><style>
            /* The site's palette, its fonts, and its way of choosing between them -- not a
               third of any of the three. The colours were already the site's; the rest was
               not, and matching two of three is what makes two pages look like relatives
               rather than the same product.

               The site is dark by default with light under the media query, and an explicit
               data-theme beats both. This page did the opposite -- light by default -- so a
               machine set to light showed a light board beside a dark manual, and a person
               who had chosen a theme on the site got no say here at all. Same three states,
               same order, same values, same localStorage key. */
            :root{--sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;
                  --mono:ui-monospace,"SF Mono",SFMono-Regular,"JetBrains Mono","Cascadia Mono",Menlo,Consolas,monospace;
                  --bg:#07141A;--fg:#E6EFF0;--mut:#7B949C;--line:#1A3540;--card:#0D202A;
                  --acc:#D8B23A;--ok:#5FBFB0;--bad:#E08066;--code:#040E13}
            @media(prefers-color-scheme:light){:root:not([data-theme="dark"]){
                  --bg:#E4EBED;--fg:#08161D;--mut:#4C626C;--line:#BFD0D4;--card:#FFFFFF;
                  --acc:#7A5D0C;--ok:#175A52;--bad:#7E3320;--code:#D3DEE1}}
            :root[data-theme="light"]{--bg:#E4EBED;--fg:#08161D;--mut:#4C626C;--line:#BFD0D4;
                  --card:#FFFFFF;--acc:#6B510A;--ok:#175A52;--bad:#7E3320;--code:#D3DEE1}
            :root[data-theme="dark"]{--bg:#07141A;--fg:#E6EFF0;--mut:#7B949C;--line:#1A3540;
                  --card:#0D202A;--acc:#D8B23A;--ok:#5FBFB0;--bad:#E08066;--code:#040E13}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 var(--sans)}
            .theme{position:absolute;top:28px;right:20px;border:1px solid var(--line);
                   background:none;color:var(--mut);border-radius:6px;padding:4px 10px;
                   font:inherit;font-size:12.5px;cursor:pointer}
            .theme:hover{border-color:var(--acc);color:var(--acc)}
            .wrap{max-width:920px;margin:0 auto;padding:32px 20px 64px;position:relative}
            h1{font-size:20px;margin:0 0 2px}
            .sub{color:var(--mut);font-size:13px}
            .grp{font-size:11px;text-transform:uppercase;letter-spacing:.08em;color:var(--mut);
                 margin:28px 0 10px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:10px;
                  padding:14px 16px;margin-bottom:10px}
            .row{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap}
            .nm{font-weight:600}
            .kind{font-size:11px;text-transform:uppercase;letter-spacing:.06em;color:var(--acc);
                  border:1px solid var(--line);border-radius:20px;padding:1px 8px}
            .ok{color:var(--ok)} .bad{color:var(--bad)}
            code{background:var(--code);padding:1px 5px;border-radius:4px;font-size:12.5px}
            .verbs{color:var(--mut);font-size:12.5px;margin-top:6px}
            input{flex:1;min-width:240px;padding:8px 10px;border:1px solid var(--line);
                  border-radius:8px;background:var(--card);color:var(--fg);font-size:14px}
            button{padding:8px 14px;border:1px solid var(--line);border-radius:8px;
                   background:var(--code);color:var(--fg);cursor:pointer;font-size:14px}
            button:hover{border-color:var(--acc);color:var(--acc)}
            .x{border:0;background:none;color:var(--mut);cursor:pointer;font-size:12.5px;padding:0}
            .x:hover{color:var(--bad)}
            .msg{margin-top:10px;font-size:13px;min-height:18px}
            .note{color:var(--mut);font-size:12.5px;margin-top:26px;border-top:1px solid var(--line);
                  padding-top:14px}
            .doc h1{font-size:19px} .doc h2{font-size:16px} .doc h3{font-size:14px}
            /* Asking and doing must not look alike. Everything else on this page changes
               something; these only read, and the dashed outline is the difference a reader can
               see before they click rather than after. */
            .asks{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}
            .ask{background:none;border:1px dashed var(--line);color:var(--mut);border-radius:6px;
                 padding:6px 11px;font:inherit;font-size:13px;cursor:pointer}
            .ask:hover{border-color:var(--acc);color:var(--fg)}
            .ask[aria-busy="true"]{opacity:.55;cursor:progress}
            .out{white-space:pre-wrap;font-family:var(--mono);
                 font-size:12.5px;line-height:1.55;color:var(--fg);background:var(--card);
                 border:1px solid var(--line);border-radius:8px;padding:12px 14px;margin-top:4px;
                 max-height:420px;overflow:auto}
            .ranby{color:var(--mut);font-size:12px;margin:6px 0 0}
            /* Extensions are the least of what this page is for now that the runner and the
               memory are both built in. Open on demand, so a page whose subject is the board does
               not open on a box asking for a path nobody needs to paste. */
            .ext{margin-top:28px;border-top:1px solid var(--line);padding-top:14px}
            .ext summary{font-size:11px;text-transform:uppercase;letter-spacing:.08em;
                         color:var(--mut);cursor:pointer;list-style:none}
            .ext summary::-webkit-details-marker{display:none}
            .ext summary:hover{color:var(--acc)}
            /* A question that needs a number gets a field beside it, not a browser dialog: a
               modal says nothing about what it wants until after it has interrupted you. */
            .one{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:7px 0}
            .one input{flex:0 1 130px;min-width:90px;padding:6px 9px;font-size:13px}
            .one .q{color:var(--mut);font-size:12.5px;flex:1 1 320px}
            .rw{display:flex;align-items:baseline;gap:10px;padding:8px 0;
                border-bottom:1px solid var(--line);flex-wrap:wrap}
            .rw .pr{font-family:var(--mono);font-size:12.5px;color:var(--mut)}
            .rw .rp{font-size:13px}
            .rw .vd{font-size:11.5px;letter-spacing:.04em;text-transform:uppercase;
                    border:1px solid var(--line);border-radius:999px;padding:1px 8px;color:var(--mut)}
            .rw .sp{flex:1}
            </style></head><body><div class="wrap">
            <a class="theme" href="/docs" title="Every document this build carries">docs</a>
            <button class="theme" id="theme-btn" title="Same choice as the site makes">theme</button>
            <h1>oss</h1>
            <div class="sub">One core that knows. A <b>runner</b> runs something real; a <b>memory</b> remembers.</div>

            <div class="grp">board</div>
            <div class="asks" id="board"></div>
            <div id="rows"></div>
            <div class="out" id="boardout">reading the ledger…</div>
            <p class="ranby" id="boardran"></p>

            <div class="grp">ask about one thing</div>
            <div id="oneof"></div>

            <div class="grp">sweeps</div>
            <div class="asks" id="asks"></div>
            <div class="out" id="askout" hidden></div>
            <p class="ranby" id="askran"></p>

            <details class="ext">
              <summary id="extsum">extensions</summary>
              <div id="list"></div>
              <div class="card">
                <div class="row">
                  <input id="path" placeholder="/path/to/a/repo containing oss-ext.json" />
                  <button id="go">Attach</button>
                </div>
                <div class="msg" id="msg"></div>
              </div>
            </details>

            <div class="note">
              Attaching records a path — nothing is uploaded or copied, and the extension stays an
              ordinary repository. Detaching only forgets the path; it deletes nothing.<br><br>
              This page attaches and reports. It deliberately does not <em>run</em> anything: an
              outward write must be confirmed at a terminal, and a browser has none. Run verbs from
              the CLI — <code>oss run &lt;verb&gt;</code>, <code>oss memory &lt;verb&gt;</code>.<br><br>
              <b>You do not need this page.</b> Everything on it is two commands —
              <code>oss ext add &lt;path&gt;</code> and <code>oss ext list</code> — and a
              <b>pack</b> needs neither, because it has nothing to attach:
              <code>oss run --pack &lt;dir&gt; &lt;verb&gt;</code>. If you have it running at login
              and would rather not, <code>oss serve --uninstall</code> stops that and removes
              nothing.
            </div>
            </div><script>
            const $=s=>document.querySelector(s);
            function esc(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
            function draw(x){
              if(!x||!x.length){$('#list').innerHTML=
                '<div class="card sub">Nothing attached yet. Paste a repo path below.</div>';return}
              $('#list').innerHTML=x.map(e=>`<div class="card">
                <div class="row"><span class="nm">${esc(e.name)}</span>
                  <span class="kind">${esc(e.kind)}</span>
                  <span class="${e.reachable?(e.stale?'bad':'ok'):'bad'}">${!e.reachable?'MISSING':(e.stale?'STALE':'reachable')}</span>
                  <span style="flex:1"></span>
                  <button class="x" data-detach="${esc(e.name)}">detach</button></div>
                <div class="sub">${esc(e.description||'')}</div>
                ${e.stale?'<div class="verbs bad">oss-ext.json changed on disk since it was attached — detach and attach again, or <code>oss ext refresh '+esc(e.name)+'</code>. Dispatch is refused until then.</div>':''}
                <div class="verbs"><code>${esc(e.root)}</code></div>
                <div class="verbs">${e.verbs.length} verbs: ${e.verbs.map(esc).join(', ')}
                ${e.writes&&e.writes.length?' · <b>writes outward:</b> '+e.writes.map(esc).join(', '):''}</div>
              </div>`).join('');
            }
            // The page never reimplements a command. Every button below runs one and shows the
            // output, so the two cannot disagree -- and nothing reachable from here writes.
            const BOARD=['hub','pick'];
            function ask(q,arg,outEl,ranEl,btn){
              const url='api/ask?q='+encodeURIComponent(q)+(arg?'&arg='+encodeURIComponent(arg):'');
              if(btn){btn.setAttribute('aria-busy','true')}
              outEl.hidden=false; outEl.textContent='asking…';
              return fetch(url).then(r=>r.json()).then(d=>{
                outEl.textContent=d.error?d.error:(d.output||d.note||'');
                if(ranEl){ranEl.textContent=d.runs?('ran  '+d.runs):''}
              }).catch(e=>{outEl.textContent=String(e)})
               .finally(()=>{if(btn){btn.removeAttribute('aria-busy')}});
            }
            // The question goes where it is asked. "Seen this?" belongs on every row; "since I
            // reviewed" only where a verdict exists, because anywhere else it answers about nothing.
            function rows(){
              return fetch('api/rows').then(r=>r.json()).then(d=>{
                const host=document.getElementById('rows');
                host.textContent='';
                d.rows.forEach(r=>{
                  const el=document.createElement('div'); el.className='rw';
                  const pr=document.createElement('span'); pr.className='pr'; pr.textContent='#'+r.pr;
                  const rp=document.createElement('span'); rp.className='rp'; rp.textContent=r.repo;
                  const vd=document.createElement('span'); vd.className='vd'; vd.textContent=r.verdict;
                  const sp=document.createElement('span'); sp.className='sp';
                  el.append(pr,rp,vd,sp);

                  const seen=document.createElement('button');
                  seen.className='ask'; seen.textContent='Seen this?';
                  seen.title='Have I worked this out before? Searches your own notes and synced issues by meaning.';
                  seen.onclick=()=>ask('search',r.repo+' '+r.pr,
                      document.getElementById('boardout'),document.getElementById('boardran'),seen);
                  el.append(seen);

                  if(r.hasVerdict){
                    const since=document.createElement('button');
                    since.className='ask'; since.textContent='Since I reviewed';
                    since.title='What the author did after your verdict.';
                    since.onclick=()=>ask('followup-one',String(r.pr),
                        document.getElementById('boardout'),document.getElementById('boardran'),since);
                    el.append(since);
                  }
                  host.append(el);
                });
              }).catch(()=>{});
            }
            function questions(){
              return fetch('api/questions').then(r=>r.json()).then(d=>{
                const board=document.getElementById('board'), asks=document.getElementById('asks'),
                      oneof=document.getElementById('oneof');
                d.questions.forEach(q=>{
                  const onBoard=BOARD.includes(q.key);
                  const out=()=>document.getElementById(onBoard?'boardout':'askout');
                  const ran=()=>document.getElementById(onBoard?'boardran':'askran');
                  // A question that takes an argument gets its own row with a field and the
                  // sentence it answers. `triage` needs an issue number and used to open a browser
                  // prompt() -- a modal that interrupts first and explains second, and which left
                  // the page with no way to say that the question exists at all.
                  if(q.arg){
                    const row=document.createElement('div'); row.className='one';
                    const b=document.createElement('button');
                    b.className='ask'; b.textContent=q.key; b.title='runs:  '+q.runs;
                    const i=document.createElement('input');
                    i.placeholder=q.arg==='num'?'issue or PR number':'what are you looking for?';
                    const say=document.createElement('span'); say.className='q'; say.textContent=q.asks;
                    const go=()=>{const v=i.value.trim(); if(!v){i.focus();return}
                      ask(q.key,v,document.getElementById('askout'),
                          document.getElementById('askran'),b)};
                    b.onclick=go;
                    i.addEventListener('keydown',e=>{if(e.key==='Enter')go()});
                    row.append(b,i,say); oneof.append(row);
                    return;
                  }
                  const b=document.createElement('button');
                  b.className='ask'; b.textContent=q.key; b.title=q.asks+'\n\nruns:  '+q.runs;
                  b.onclick=()=>ask(q.key,null,out(),ran(),b);
                  (onBoard?board:asks).appendChild(b);
                });
                // The board is what the page opens on, so it answers without being asked.
                rows();
                return ask('hub',null,document.getElementById('boardout'),
                           document.getElementById('boardran'),null);
              });
            }
            function load(){fetch('api/extensions').then(r=>r.json())
              .then(d=>{draw(d.extensions);
                const n=(d.extensions||[]).length;
                // Say the count in the summary. Collapsed and unlabelled, "extensions" gives no
                // reason to open it and no way to know whether anything is attached.
                document.getElementById('extsum').textContent=
                  n?('extensions — '+n+' attached'):'extensions — none attached (nothing here needs one)';
              })}
            function attach(){
              const p=$('#path').value.trim(); if(!p)return;
              $('#msg').textContent='attaching…'; $('#msg').className='msg sub';
              fetch('api/attach',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({path:p})}).then(r=>r.json()).then(d=>{
                if(d.error){$('#msg').textContent=d.error;$('#msg').className='msg bad';return}
                $('#msg').textContent=(d.replaced?'updated ':'attached ')+d.name+' ('+d.kind+')';
                $('#msg').className='msg ok'; $('#path').value='';
                draw(d.extensions);
              }).catch(e=>{$('#msg').textContent=String(e);$('#msg').className='msg bad'});
            }
            $('#go').onclick=attach;
            $('#path').addEventListener('keydown',e=>{if(e.key==='Enter')attach()});
            document.addEventListener('click',e=>{
              const n=e.target.getAttribute&&e.target.getAttribute('data-detach'); if(!n)return;
              fetch('api/detach',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({name:n})}).then(r=>r.json()).then(d=>{
                  $('#msg').textContent=d.ok?('detached '+n):(d.error||'could not detach');
                  $('#msg').className='msg '+(d.ok?'ok':'bad');
                  draw(d.extensions);});
            });
            // The site's toggle, the site's key. A person who set the manual to light and
            // opened the board should not have to set it twice -- and on a machine whose
            // system theme disagrees with their choice, the stored answer is the one they
            // actually gave.
            (function(){
              const root=document.documentElement, btn=document.getElementById('theme-btn');
              try{const saved=localStorage.getItem('ubuos-theme');
                  if(saved){root.setAttribute('data-theme',saved)}}catch(e){}
              btn.onclick=function(){
                let now=root.getAttribute('data-theme');
                if(!now){now=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}
                const next=now==='dark'?'light':'dark';
                root.setAttribute('data-theme',next);
                try{localStorage.setItem('ubuos-theme',next)}catch(e){}
              };
            })();
            load();
            questions();
            </script></body></html>
            """;
}
