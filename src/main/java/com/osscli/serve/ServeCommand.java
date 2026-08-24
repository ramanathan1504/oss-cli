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
        server.createContext("/api/questions", this::handleQuestions);
        server.createContext("/api/waiting", this::handleWaiting);
        server.createContext("/api/suggestions", this::handleSuggestions);
        server.createContext("/api/state", this::handleState);
        server.createContext("/api/ask", this::handleAsk);
        server.setExecutor(askPool());
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println("oss serving on " + url + "   (ctrl-c to stop)");
        // Not "attach an extension" any more: the box that did that is gone from the page, and a
        // startup line advertising a field nobody can find is worse than no line at all.
        System.out.println("  reads only — anything that writes stays on the command line");
        if (!noOpen) {
            openBrowser(url);
        }
        // Non-null when this process has finished: either it handed the port to the service that
        // is now running in the background, or it tried to and could not. Both are reasons to stop,
        // and neither is a reason to park a JVM that is no longer listening.
        Integer done = offerAutostart(server);
        if (done != null) {
            return done;
        }
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
            send(
                    x,
                    200,
                    "text/html; charset=utf-8",
                    docPage(wanted, onThisMachine() + Markdown.toHtml(markdown), toc(markdown)));
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

    /**
     * The site's stylesheet, carried whole rather than approximated.
     *
     * <p>One constant, used by the board and by every document page, because the last two copies
     * drifted the moment they existed: the board was rebuilt on eight variables — a background, a
     * foreground, a muted grey and a line — while the manual it links to kept a cream palette from
     * an older design. Clicking "docs" left the product. Nothing was wrong with either page on its
     * own, and together they were two products.
     *
     * <p>The eight variables were also why the board read as text on a page. The site's own note
     * says it: <em>a border says "edge", a shadow says "above"</em> — with one flat surface and a
     * 1px rule there is no elevation, no sunken ground to alternate against, and one grey doing the
     * work of three. Those tokens are all here, at the site's values, so a card on the board is
     * literally the same card.
     */
    private static final String STYLE = """
            :root{
              --petrol-900:#040E13;--petrol-800:#08161D;--petrol-700:#102530;--petrol-600:#1B3B48;
              --brass:#D8B23A;--patina:#5FBFB0;--rust:#E08066;
              --bg:#07141A;--bg-raised:#0D202A;--bg-sunken:#040E13;
              --ink:#E6EFF0;--ink-soft:#A9BEC5;--ink-faint:#7B949C;
              --rule:#1A3540;--rule-soft:#122831;
              --accent:#D8B23A;--accent-bright:#E8C558;--accent-ink:#07141A;
              --link:#68C0B4;--glow:rgba(216,178,58,.10);
              --term-ink:#D6E4E6;--term-dim:#7A939C;
              --mono:ui-monospace,"SF Mono",SFMono-Regular,"JetBrains Mono","Cascadia Mono",Menlo,Consolas,monospace;
              --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;
              --measure:66ch;--gutter:clamp(1.25rem,4vw,2.5rem);--shell:min(1080px,100% - var(--gutter) * 2);
              --radius:12px;
              --lift-1:0 1px 2px rgba(2,8,11,.30),0 3px 10px -3px rgba(2,8,11,.35);
              --lift-2:0 2px 5px rgba(2,8,11,.34),0 16px 34px -14px rgba(2,8,11,.55);
            }
            @media (prefers-color-scheme:light){
              :root:not([data-theme="dark"]){
                --bg:#E4EBED;--bg-raised:#FFFFFF;--bg-sunken:#D3DEE1;
                --ink:#08161D;--ink-soft:#334A55;--ink-faint:#4C626C;
                --rule:#BFD0D4;--rule-soft:#D3DEE1;
                --accent:#7A5D0C;--accent-bright:#6B510A;--accent-ink:#FFFFFF;
                --link:#175A52;--glow:rgba(122,93,12,.09);
                --lift-1:0 1px 2px rgba(8,22,29,.08),0 3px 10px -3px rgba(8,22,29,.16);
                --lift-2:0 2px 5px rgba(8,22,29,.10),0 16px 34px -14px rgba(8,22,29,.28);
              }
            }
            :root[data-theme="light"]{
              --bg:#E4EBED;--bg-raised:#FFFFFF;--bg-sunken:#D3DEE1;
              --ink:#08161D;--ink-soft:#334A55;--ink-faint:#4C626C;
              --rule:#BFD0D4;--rule-soft:#D3DEE1;
              --accent:#6B510A;--accent-bright:#6B510A;--accent-ink:#FFFFFF;
              --link:#175A52;--glow:rgba(122,93,12,.09);
              --lift-1:0 1px 2px rgba(8,22,29,.08),0 3px 10px -3px rgba(8,22,29,.16);
              --lift-2:0 2px 5px rgba(8,22,29,.10),0 16px 34px -14px rgba(8,22,29,.28);
            }
            :root[data-theme="dark"]{
              --bg:#07141A;--bg-raised:#0D202A;--bg-sunken:#040E13;
              --ink:#E6EFF0;--ink-soft:#A9BEC5;--ink-faint:#7B949C;
              --rule:#1A3540;--rule-soft:#122831;
              --accent:#D8B23A;--accent-bright:#E8C558;--accent-ink:#07141A;
              --link:#68C0B4;--glow:rgba(216,178,58,.10);
            }
            *,*::before,*::after{box-sizing:border-box}
            html{-webkit-text-size-adjust:100%;scroll-behavior:smooth}
            body{margin:0;background:var(--bg);color:var(--ink);font-family:var(--sans);
                 font-size:17px;line-height:1.6;-webkit-font-smoothing:antialiased}
            h1,h2,h3{margin:0;text-wrap:balance;font-weight:600;letter-spacing:-.02em}
            p{margin:0}
            a{color:var(--link);text-decoration-thickness:1px;text-underline-offset:3px}
            a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible{
              outline:2px solid var(--accent);outline-offset:3px;border-radius:4px}
            code,kbd,pre{font-family:var(--mono)}
            .shell{width:var(--shell);margin-inline:auto}

            /* eyebrow labels -- uppercase mono with a brass tick, the site's way of naming
               a section without spending a heading on it */
            .eyebrow{font-family:var(--mono);font-size:.72rem;letter-spacing:.16em;
                     text-transform:uppercase;color:var(--ink-faint);margin-bottom:1rem;
                     display:flex;align-items:center;gap:.6rem}
            .eyebrow::before{content:"";width:1.6rem;height:1px;background:var(--accent);
                             opacity:.7;flex:none}

            /* ---------- app shell ----------
               A left rail and a main column, not a centred measure. This is a board for a tool
               that is open while you work, and it was laid out like a marketing page: an 860px
               column down the middle with the whole of a wide screen empty either side, and the
               only way to reach a section was to scroll for it. A sidebar gives the sections
               somewhere to be named and gives the rows the width they need to be rows. */
            .app{display:grid;grid-template-columns:248px minmax(0,1fr);min-height:100vh}
            .side{position:sticky;top:0;height:100vh;overflow-y:auto;padding:1.15rem 1rem 1.5rem;
                  background:var(--bg-sunken);border-right:1px solid var(--rule);
                  display:flex;flex-direction:column;gap:1.5rem}
            main{min-width:0;padding:1.6rem clamp(1.25rem,3vw,2.5rem) 4rem;max-width:1400px}

            .wordmark{display:flex;align-items:center;gap:.6rem;font-family:var(--mono);
                      font-weight:600;letter-spacing:-.03em;font-size:1.05rem;color:var(--ink);
                      text-decoration:none}
            .wordmark .glyph{width:26px;height:26px;border-radius:7px;background:var(--petrol-800);
                             display:grid;place-items:center;flex:none;
                             border:1px solid var(--petrol-600)}
            .wordmark .glyph svg{display:block}

            .navgrp{display:flex;flex-direction:column;gap:.1rem}
            .navlbl{font-family:var(--mono);font-size:.66rem;letter-spacing:.16em;
                    text-transform:uppercase;color:var(--ink-faint);margin:0 0 .5rem .5rem}
            .side a.nav{display:flex;align-items:center;gap:.55rem;padding:.4rem .55rem;
                        border-radius:7px;color:var(--ink-soft);text-decoration:none;
                        font-size:.89rem;border-left:2px solid transparent}
            .side a.nav:hover{background:var(--bg-raised);color:var(--ink)}
            .side a.nav.on{color:var(--ink);background:var(--bg-raised);
                           border-left-color:var(--accent)}
            .side a.nav .badge{margin-left:auto;font-family:var(--mono);font-size:.72rem;
                               color:var(--accent);font-variant-numeric:tabular-nums}
            .side .bottom{margin-top:auto;display:flex;flex-direction:column;gap:.7rem}
            .theme-btn{background:transparent;border:1px solid var(--rule);color:var(--ink-soft);
                       border-radius:7px;padding:.4rem .6rem;cursor:pointer;font:inherit;
                       font-size:.82rem;line-height:1;text-align:left}
            .theme-btn:hover{border-color:var(--ink-faint);color:var(--ink)}
            .sidenote{font-size:.72rem;color:var(--ink-faint);line-height:1.5}

            /* Stacked on a narrow screen: the rail becomes a strip that scrolls sideways rather
               than a drawer, because a drawer needs a button and a button needs a state. */
            @media (max-width:860px){
              .app{grid-template-columns:1fr}
              .side{position:static;height:auto;border-right:0;
                    border-bottom:1px solid var(--rule);gap:.9rem}
              .side .navgrp{flex-direction:row;flex-wrap:wrap;gap:.3rem}
              .navlbl{display:none}
              .side .bottom{margin-top:0;flex-direction:row;align-items:center;gap:1rem}
            }

            /* bands. The page alternates temperature as you scroll instead of holding one flat
               grey the whole way down, which is what made it read as a template. */
            .band{padding:clamp(2.25rem,5vw,3.25rem) 0}
            .band-sunken{background:var(--bg-sunken);border-block:1px solid var(--rule)}
            .band h2{font-size:clamp(1.35rem,2.6vw,1.7rem);letter-spacing:-.03em}
            .band .sub{margin-top:.85rem;max-width:var(--measure);color:var(--ink-soft)}

            /* cards */
            .card{background:var(--bg-raised);border:1px solid var(--rule);border-radius:11px;
                  padding:1.25rem 1.3rem;display:grid;gap:.55rem;align-content:start;
                  box-shadow:var(--lift-1);
                  transition:box-shadow .18s ease,transform .18s ease,border-color .18s ease}
            .card:hover{box-shadow:var(--lift-2);transform:translateY(-2px);
                        border-color:var(--ink-faint)}
            .card h3{font-size:1.02rem;letter-spacing:-.015em}
            .card p{color:var(--ink-soft);font-size:.93rem}
            .card .k{font-family:var(--mono);font-size:.74rem;letter-spacing:.1em;
                     text-transform:uppercase;color:var(--accent)}

            .foot{padding:2.25rem 0 3rem;color:var(--ink-faint);font-size:.88rem}
            .foot a{color:var(--ink-soft)}
            @media (prefers-reduced-motion:reduce){
              html{scroll-behavior:auto}
              *,*::before,*::after{transition-duration:.01ms !important}
            }
            """;

    /** The wordmark glyph, the same three strokes and dot the site and the favicon carry. */
    private static final String GLYPH = """
            <span class="glyph"><svg width="15" height="15" viewBox="0 0 32 32" aria-hidden="true">
            <path d="M8 12h5M8 17h9M8 22h6" stroke="#C9A227" stroke-width="2.6"
            stroke-linecap="round"/><circle cx="23" cy="12" r="2.4" fill="#4E9A8F"/></svg></span>
            """;

    /** The tab icon, byte for byte the site's. */
    private static final String FAVICON = "<link rel=\"icon\" href=\"data:image/svg+xml,"
            + "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
            + "<rect width='32' height='32' rx='7' fill='%230B1B24'/>"
            + "<path d='M8 12h5M8 17h9M8 22h6' stroke='%23C9A227' stroke-width='2.4'"
            + " stroke-linecap='round'/>"
            + "<circle cx='23' cy='12' r='2.4' fill='%234E9A8F'/></svg>\">";

    /**
     * The left rail, identical on the board and on every document.
     *
     * <p>Shared markup rather than two hand-written copies: the manual used to have a row of bare
     * text links where the board had nothing at all, so moving between them changed the furniture
     * as well as the colours. It also gives the board's sections somewhere to be named — they were
     * headings in one long scroll, which is a page, not a board.
     *
     * @param here the document being read, or {@code "board"}
     */
    private static String sidebar(String here) {
        boolean onBoard = "board".equals(here);
        StringBuilder b = new StringBuilder();
        b.append("<aside class=\"side\">")
                .append("<a class=\"wordmark\" href=\"/\">")
                .append(GLYPH)
                .append("oss</a>");

        // The board's own sections, as anchors. Only on the board: a link to #waiting from a
        // document scrolls that document to nothing.
        if (onBoard) {
            b.append("<div class=\"navgrp\"><p class=\"navlbl\">board</p>")
                    .append("<a class=\"nav on\" href=\"#waiting\">Waiting on you"
                            + "<span class=\"badge\" id=\"navcount\"></span></a>")
                    .append("<a class=\"nav\" href=\"#next\">Work on next</a>")
                    .append("<a class=\"nav\" href=\"#ask\">Ask about one thing</a>")
                    .append("<a class=\"nav\" href=\"#sweeps\">Sweeps</a>")
                    .append("<a class=\"nav\" href=\"#builtin\">Built in</a>")
                    .append("</div>");
        } else {
            b.append("<div class=\"navgrp\"><p class=\"navlbl\">board</p>")
                    .append("<a class=\"nav\" href=\"/\">Back to the board</a></div>");
        }

        b.append("<div class=\"navgrp\"><p class=\"navlbl\">manual</p>");
        for (String d : DOCS) {
            b.append("<a class=\"nav")
                    .append(d.equals(here) ? " on" : "")
                    .append("\" href=\"/docs/")
                    .append(d)
                    .append("\">")
                    .append(d.replace(".md", "").toLowerCase(java.util.Locale.ROOT))
                    .append("</a>");
        }
        b.append("</div>");

        b.append("<div class=\"bottom\">")
                .append("<button class=\"theme-btn\" id=\"theme-btn\" ")
                .append("title=\"Same choice as the site makes\">theme</button>")
                .append("<p class=\"sidenote\">Everything here reads. Anything that writes stays ")
                .append("at a terminal.</p></div></aside>");
        return b.toString();
    }

    /**
     * The toggle, and the pre-paint read that stops a chosen theme flashing.
     *
     * <p>Lifted from the site including the inline script in the head: without it a person who
     * chose light gets one dark frame on every navigation, which on a manual you click through is
     * a strobe rather than a detail.
     */
    private static final String THEME_BOOT = """
            <script>(function(){try{var s=localStorage.getItem('ubuos-theme');
            if(s==='dark'||s==='light'){document.documentElement.setAttribute('data-theme',s)}}
            catch(e){}})();</script>
            """;

    private static final String THEME_JS = """
            <script>(function(){
              var root=document.documentElement,btn=document.getElementById('theme-btn');
              if(!btn)return;
              btn.onclick=function(){
                var now=root.getAttribute('data-theme');
                if(!now){now=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}
                var next=now==='dark'?'light':'dark';
                root.setAttribute('data-theme',next);
                try{localStorage.setItem('ubuos-theme',next)}catch(e){}
              };
            })();</script>
            """;

    /**
     * One document, on the board's stylesheet rather than beside it.
     *
     * <p>This page used to carry a cream palette -- {@code #faf9f5} on {@code #141413} -- while the
     * board it links to is petrol and brass. Both were fine alone and clicking "docs" left the
     * product, which is the one thing a manual served from the same port must not do. There is no
     * palette here any more; there is {@link #STYLE}, and what is below is only the rules a
     * document needs that a board does not.
     */
    private static String docPage(String title, String body) {
        return docPage(title, body, "");
    }

    /**
     * A document, its shell, and the rail of its own headings.
     *
     * <p>The right half of every manual page was empty: the prose sat in a 66ch measure and the
     * rest of a wide screen held nothing. A measure is right for the prose and wrong for the page,
     * so the column keeps it and the space beside it now carries what a reader of a forty-section
     * reference actually wants — where they are and what else is in here. COMMANDS.md has thirteen
     * sections and no way to see them without scrolling the whole file.
     */
    private static String docPage(String title, String body, String toc) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"dark light\">"
                + FAVICON
                + "<title>" + title + " · oss</title>"
                + THEME_BOOT
                + "<style>" + STYLE + DOC_CSS + "</style></head><body><div class=\"app\">"
                + sidebar(title)
                + "<main class=\"doc\"><article>" + body
                + "<footer class=\"foot\">Served by this build from inside its own jar — the same "
                + "text ships with the binary, so it can never describe a version you do not have."
                + "</footer></article>" + toc + "</main></div>"
                + THEME_JS + "</body></html>";
    }

    /**
     * The document's own sections, as links into it.
     *
     * <p>Empty when a document has fewer than three, because a contents list of two is furniture.
     */
    private static String toc(String markdown) {
        java.util.List<String[]> heads = Markdown.headings(markdown);
        if (heads.size() < 3) {
            return "";
        }
        // COMMANDS.md has thirteen sections and forty-nine subsections, and all sixty-two in a
        // 15rem rail is a wall of links -- a second copy of the document rather than a way through
        // it. Past the threshold the rail keeps the sections only, which is what somebody scanning
        // for "where is triage documented" is actually looking for.
        long subs = heads.stream().filter(h -> "3".equals(h[0])).count();
        boolean sectionsOnly = subs > 25;
        StringBuilder b = new StringBuilder("<nav class=\"toc\"><p>on this page</p>");
        for (String[] h : heads) {
            if (sectionsOnly && "3".equals(h[0])) {
                continue;
            }
            b.append("<a class=\"h")
                    .append(h[0])
                    .append("\" href=\"#")
                    .append(h[1])
                    .append("\">")
                    .append(h[2])
                    .append("</a>");
        }
        return b.append("</nav>").toString();
    }

    /** What a document needs and a board does not: prose measure, headings, tables, code blocks. */
    private static final String DOC_CSS = """
            /* Content column plus a contents rail. The prose keeps a measure; tables, code and
               the rail use the width the shell gives them. */
            main.doc{display:grid;grid-template-columns:minmax(0,1fr) 15rem;
                     gap:clamp(1.5rem,4vw,3rem);align-items:start;max-width:none}
            main.doc>article{min-width:0;max-width:78ch}
            main.doc>article>p,main.doc>article>ul,main.doc>article>ol,
            main.doc>article>blockquote{max-width:var(--measure)}
            .toc{position:sticky;top:1.6rem;font-size:.83rem;border-left:1px solid var(--rule);
                 padding-left:1rem;max-height:calc(100vh - 3.2rem);overflow-y:auto}
            .toc p{font-family:var(--mono);font-size:.66rem;letter-spacing:.16em;
                   text-transform:uppercase;color:var(--ink-faint);margin-bottom:.6rem}
            .toc a{display:block;padding:.24rem 0;color:var(--ink-soft);text-decoration:none;
                   line-height:1.4}
            .toc a:hover{color:var(--accent)}
            .toc a.h3{padding-left:.85rem;font-size:.79rem;color:var(--ink-faint)}
            .toc a.h3:hover{color:var(--accent)}
            @media (max-width:1100px){
              main.doc{grid-template-columns:minmax(0,1fr)}
              .toc{display:none}
            }
            h1,h2,h3{line-height:1.25;margin:2.2rem 0 .7rem;letter-spacing:-.025em}
            h1{font-size:clamp(1.7rem,3.4vw,2.1rem);margin-top:.4rem}
            h2{font-size:1.32rem} h3{font-size:1.06rem}
            p,li{color:var(--ink-soft)}
            p{margin:.85rem 0}
            ul,ol{padding-left:1.3rem}
            li{margin:.35rem 0}
            strong{color:var(--ink)}
            code{background:var(--bg-sunken);border:1px solid var(--rule-soft);
                 padding:.08em .38em;border-radius:5px;font-size:.87em;color:var(--accent)}
            pre{background:var(--petrol-900);border:1px solid var(--petrol-600);
                border-radius:var(--radius);padding:1rem 1.15rem;overflow-x:auto;
                box-shadow:var(--lift-1);color:#D6E4E6;font-size:.85rem;line-height:1.6}
            pre code{background:none;border:0;padding:0;color:inherit;font-size:inherit}
            table{border-collapse:collapse;width:100%;font-size:.92rem}
            .tw{margin:1.5rem 0;overflow-x:auto;border:1px solid var(--rule);border-radius:10px;
                background:var(--bg-raised);box-shadow:var(--lift-1)}
            th,td{text-align:left;padding:.72rem .95rem;border-bottom:1px solid var(--rule);
                  vertical-align:top}
            thead th{font-family:var(--mono);font-size:.72rem;letter-spacing:.12em;
                     text-transform:uppercase;color:var(--ink-faint);font-weight:500;
                     background:var(--bg-sunken)}
            tbody tr:last-child td{border-bottom:0}
            hr{border:0;border-top:1px solid var(--rule);margin:2.2rem 0}
            blockquote{margin:1.2rem 0;padding:.1rem 0 .1rem 1.1rem;
                       border-left:2px solid var(--accent);color:var(--ink-soft)}
            /* What the documents mean on this particular machine. */
            .mine{background:var(--bg-raised);border:1px solid var(--rule);
                  border-left:2px solid var(--accent);border-radius:10px;
                  padding:.85rem 1.05rem;margin:0 0 1.75rem;font-size:.9rem;
                  color:var(--ink-soft);box-shadow:var(--lift-1)}
            .mine strong{color:var(--ink)}
            """;

    /**
     * Who is waiting on you, as data rather than as a picture of data.
     *
     * <p>The same {@link com.osscli.review.Waiting#read} call {@code oss hub} makes. The page used
     * to get this by running {@code hub} as a child process and printing its stdout into a grey box
     * — a terminal transcript in a browser, with the columns held apart by spaces and the pull
     * request numbers not clickable, because in text they are not links, they are digits.
     *
     * <p>Slow on purpose and slow honestly: three GitHub calls per recorded review, seven seconds
     * on a seventeen-row ledger. The page asks for it after it has painted, so the wait is a
     * section filling in rather than a blank window.
     */
    private void handleWaiting(HttpExchange x) throws IOException {
        String me = com.osscli.review.Waiting.me();
        com.osscli.review.Waiting.Result r =
                com.osscli.review.Waiting.read(null, me, com.osscli.review.Waiting.Progress.SILENT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("me", me);
        out.put("onYou", waitingRows(r.onYou(), me));
        out.put("onThem", waitingRows(r.onThem(), me));
        out.put("checked", r.checked());
        out.put("unreachable", r.unreachable());
        // Asked rather than assumed: "private, deleted, or no token" is three explanations, two of
        // them wrong every time, and it sends the reader hunting for a problem they do not have.
        out.put("why", r.unreachable() > 0 ? com.osscli.github.Reachability.whyUnreadable() : "");
        sendJson(x, 200, out);
    }

    private static List<Map<String, Object>> waitingRows(List<com.osscli.review.Waiting.Item> items, String me) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.osscli.review.Waiting.Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", i.row().repo);
            m.put("pr", i.row().pr);
            m.put("verdict", i.row().verdict);
            m.put("title", i.title());
            m.put("why", i.why(me));
            // The rule travels with the row. The page had "verdict && verdict !== 'none'" written
            // into its own script -- a second copy of what counts as reviewed, in another language,
            // free to drift from this one.
            m.put("hasVerdict", hasVerdict(i.row().verdict));
            m.put("pushed", i.pushed());
            m.put("merged", i.merged());
            m.put("state", i.state());
            // github.com because the ledger holds GitHub pull requests -- Waiting reads them
            // through the GitHub API and could not have recorded them from anywhere else. When a
            // second forge is supported the row will carry its own origin and this goes with it.
            m.put("url", "https://github.com/" + i.row().repo + "/pull/" + i.row().pr);
            out.add(m);
        }
        return out;
    }

    /**
     * What to work on next, as rows with a score and the reason attached.
     *
     * <p>The same {@link com.osscli.retrieval.Suggestions#read} call {@code oss pick} makes. A score
     * is a number and "because you wrote …" is a list; printing them and reading the print back is
     * how the page ended up showing forty-nine lines of which twenty-three were a counter.
     */
    private void handleSuggestions(HttpExchange x) throws IOException {
        try {
            com.osscli.retrieval.Suggestions.Result r = com.osscli.retrieval.Suggestions.read(
                    null, 10, false, com.osscli.retrieval.Suggestions.Progress.SILENT);
            List<Map<String, Object>> items = new ArrayList<>();
            for (com.osscli.retrieval.Suggestions.Item i : r.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("repo", i.repo());
                m.put("number", i.number());
                m.put("title", i.title());
                m.put("score", Math.round(i.score() * 100.0) / 100.0);
                m.put("because", i.because());
                m.put("pull", i.pull());
                m.put("url", "https://github.com/" + i.repo() + (i.pull() ? "/pull/" : "/issues/") + i.number());
                items.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("why", r.why().name());
            out.put("items", items);
            out.put("profileSize", r.profileSize());
            out.put("how", r.how());
            out.put("candidates", r.candidates());
            sendJson(x, 200, out);
        } catch (Exception e) {
            sendJson(x, 200, Map.of("why", "ERROR", "items", List.of(), "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * What is built in and what is attached, for the half of {@code run} and {@code memory} a
     * browser may honestly show.
     *
     * <p>The page deliberately does not run these — an outward write must be confirmed at a
     * terminal, and a browser has none. That was taken to mean the whole capability had to stay off
     * the board, which left a person with a memory holding fifty thousand indexed chunks looking at
     * a page that never mentioned it. Listing what a runner can do, and how much the memory holds,
     * writes nothing; the verbs that do are named as needing a terminal rather than hidden.
     */
    private void handleState(HttpExchange x) throws IOException {
        List<Map<String, Object>> exts = snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runners", ofKind(exts, "runner"));
        out.put("memories", ofKind(exts, "memory"));
        out.put("runnerVerbs", com.osscli.runner.BuiltinRunner.VERBS);
        out.put("memoryVerbs", com.osscli.memory.BuiltinMemory.VERBS);
        out.put("needsTerminal", Askable.WRITES);
        out.put("skills", skillRows());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("issues", one("SELECT count(*) FROM issues;"));
        counts.put("notes", one("SELECT count(*) FROM personal_chat_memory;"));
        counts.put("chunks", one("SELECT count(*) FROM personal_chat_chunk;"));
        counts.put("reviews", (long) com.osscli.review.ReviewLedger.read().size());
        counts.put("asked", one("SELECT count(*) FROM chat_turn;"));
        out.put("counts", counts);
        sendJson(x, 200, out);
    }

    private static List<Map<String, Object>> ofKind(List<Map<String, Object>> exts, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : exts) {
            if (kind.equals(e.get("kind"))) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The instructions {@code oss ask} works under.
     *
     * <p>Listed for the reason the extension list was: an instruction the reader cannot see is one
     * they cannot correct when an answer comes out wrong.
     */
    private static List<Map<String, Object>> skillRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (com.osscli.agent.Skill sk : com.osscli.agent.Skills.all()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", sk.name());
                m.put("summary", sk.summary() == null ? "" : sk.summary());
                m.put("when", sk.when());
                m.put("builtIn", sk.builtIn());
                out.add(m);
            }
        } catch (Exception e) {
            // A skills directory that cannot be read is not a reason for the board to fail.
            return List.of();
        }
        return out;
    }

    /*
     * handleRows was here, serving the ledger as flat rows for the page to hang buttons on.
     *
     * /api/waiting supersedes it: the same rows, plus whose move it is and why, from the call the
     * `hub` command makes. Two endpoints reading one ledger is two answers to "what have I
     * reviewed" -- and the flat one could not say which of them was waiting, which is the question
     * the page opens on.
     */

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
        lines.removeIf(ServeCommand::isProgress);
        return String.join("\n", lines).strip();
    }

    /**
     * A line that says a command is still working, which is never the answer.
     *
     * <p>{@link com.osscli.ui.Live} is the status line for anything slower than a second, and it is
     * right in a terminal, where it overwrites itself. Piped into this page it does not overwrite
     * anything: {@code hub} arrived as eighteen lines of "· 4 of 17" followed by the seven lines
     * that were the answer, and {@code pick} as twenty-three of forty-nine. The reply was below the
     * fold of a panel whose whole job is to show it, so the page looked like it had run something
     * and returned a progress log.
     *
     * <p>Anchored on the three shapes {@code Live} actually writes rather than on "looks like
     * noise". A tick is only dropped when it also carries the elapsed time that only a settled
     * status line has — {@code doctor} reports with ticks of its own, and eating those would turn a
     * fixed panel into an empty one.
     */
    private static boolean isProgress(String line) {
        String t = line.strip();
        if (t.startsWith("… ")) {
            return true;
        }
        if (t.startsWith("· ")) {
            return true;
        }
        return (t.startsWith("✓ ") || t.startsWith("✗ ")) && t.matches(".*\\(\\d+(\\.\\d+)?[a-z]{1,2}\\)$");
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

    /*
     * handleAttach and handleDetach were here.
     *
     * Both are gone with the box that called them. Nothing on the board needed an extension --
     * the runner and the memory are built in -- so the page opened on a text field asking for a
     * path to a repository that, for almost everybody looking at it, does not exist. `oss ext add
     * <path>` and `oss ext list` still do this on the command line, which is where a path is
     * something you can tab-complete rather than transcribe.
     *
     * It also takes the last two writes off this surface. Everything the browser can now reach
     * reads.
     */

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
     *
     * @return null to carry on serving in this terminal, or the exit code for a process that has
     *     handed the port to the background service and has nothing left to do
     */
    private Integer offerAutostart(HttpServer server) {
        if (Autostart.isInstalled() || Files.exists(askedMarker())) {
            return null;
        }
        Console console = System.console();
        if (console == null) {
            return null;
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
        if (!yes) {
            System.out.println("  Left as-is. Change your mind later with: oss serve --install");
            System.out.println();
            return null;
        }
        System.out.println();
        return handOver(server) ? 0 : 1;
    }

    /** How long the service gets to take the port before this reports that it did not. */
    private static final java.time.Duration HANDOVER = java.time.Duration.ofSeconds(20);

    /**
     * Give the port to the service, and do not claim success until the service has it.
     *
     * <p>Saying yes used to install a service that could not start. The order was: keep serving,
     * write the definition, let the platform launch it -- and the platform launched it into a port
     * this very process was still holding, so it failed to bind and its restart policy waited out
     * the throttle. The whole of that was invisible: the terminal said {@code ✓ starts at login},
     * the page kept working because <em>this</em> process was still answering, and the failure only
     * showed up as the page being gone for up to a minute after the terminal was closed. Which is
     * the one moment nobody is looking at a log.
     *
     * <p>So the port is released <em>first</em>, and this waits until something is answering on it
     * again before saying anything about success. If nothing does, that is said plainly: the
     * definition is installed and the port is free, which is the state the service's own next
     * restart can recover from -- taking the port back here would guarantee it never could.
     */
    private boolean handOver(HttpServer server) {
        if (!Autostart.supported()) {
            System.err.println("error  " + Autostart.unsupportedAdvice(port));
            return false;
        }
        String url = "http://localhost:" + port + "/";
        System.out.println("  handing this port to the background service…");
        // Zero seconds of grace: the only thing in flight is the board's own first `hub`, and the
        // service is about to answer it again anyway.
        server.stop(0);
        if (!doInstall()) {
            System.err.println("       nothing is serving now — start it again with:  oss serve");
            return false;
        }
        Autostart.startNow();
        if (answers(port, HANDOVER)) {
            System.out.println("  ✓ " + url + " is answering — you can close this terminal");
            System.out.println("    stop it with: oss serve --uninstall");
            return true;
        }
        System.err.println(
                "error  installed, but nothing answered on " + url + " within " + HANDOVER.toSeconds() + "s");
        System.err.println("       what it printed:  " + Autostart.errLog());
        System.err.println("       serve here instead:  oss serve --uninstall && oss serve");
        return false;
    }

    /**
     * Wait for <em>this</em> service to be the thing on the port.
     *
     * <p>A TCP connect would be satisfied by anything that binds, including the half-second in
     * which a process that is about to fail is still up. The title is what tells one surface of
     * this tool from another, and it is already how a port conflict is diagnosed.
     */
    static boolean answers(int port, java.time.Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        do {
            if (("\"" + TITLE + "\"").equals(whoIsOn(port))) {
                return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    /** The page's own title. {@link #titleOf} quotes what it finds, so this is compared quoted. */
    private static final String TITLE = "oss";

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

    /** What the board needs on top of the shared shell. */
    private static final String BOARD_CSS = """
            .sect{margin:0 0 2.6rem}
            .sect h2{font-size:1.12rem;letter-spacing:-.02em;margin:0}
            .sect .lede{color:var(--ink-soft);font-size:.9rem;margin-top:.3rem}
            .sect-hd{display:flex;align-items:baseline;gap:.9rem;flex-wrap:wrap;
                     margin-bottom:.9rem}
            .sect-hd .sp{flex:1}

            /* The one question, given the size of an answer rather than of a heading. */
            .headline{display:flex;align-items:center;gap:1.15rem;flex-wrap:wrap;
                      margin:0 0 1.2rem}
            .bignum{font-family:var(--mono);font-size:clamp(2.4rem,5vw,3.1rem);font-weight:600;
                    line-height:1;color:var(--accent);font-variant-numeric:tabular-nums}
            .bignum.clear{color:var(--patina)}
            .headline .said{color:var(--ink-soft);font-size:.98rem;max-width:46ch}

            /* Rows are rows: a surface, a grid with real columns, and a hover. Held apart by
               spaces in a monospace dump they were a picture of a table. */
            .rows{border:1px solid var(--rule);border-radius:11px;background:var(--bg-raised);
                  box-shadow:var(--lift-1);overflow:hidden}
            .row{padding:.85rem 1.1rem;border-bottom:1px solid var(--rule-soft);
                 transition:background .15s ease}
            .rows .row:last-child{border-bottom:0}
            .row:hover{background:var(--bg-sunken)}
            .row.you{border-left:2px solid var(--accent);padding-left:calc(1.1rem - 2px)}
            .rtop{display:flex;align-items:baseline;gap:.7rem;flex-wrap:wrap}
            .num{font-family:var(--mono);font-size:.84rem;font-weight:600;color:var(--accent);
                 text-decoration:none;font-variant-numeric:tabular-nums}
            .num:hover{text-decoration:underline}
            .repo{font-family:var(--mono);font-size:.78rem;color:var(--ink-faint)}
            .chip{font-family:var(--mono);font-size:.68rem;letter-spacing:.1em;
                  text-transform:uppercase;border:1px solid var(--rule);border-radius:999px;
                  padding:.12rem .55rem;color:var(--ink-faint)}
            .chip.take{color:var(--patina);border-color:var(--patina)}
            .chip.changes{color:var(--rust);border-color:var(--rust)}
            .why{margin-left:auto;font-family:var(--mono);font-size:.75rem;color:var(--ink-faint)}
            .rttl{margin-top:.32rem;font-size:.95rem;color:var(--ink);line-height:1.45}
            .rwhy{margin-top:.3rem;font-size:.82rem;color:var(--ink-faint)}
            .score{font-family:var(--mono);font-size:.84rem;font-weight:600;color:var(--patina);
                   font-variant-numeric:tabular-nums;min-width:2.6rem}
            .racts{display:flex;gap:.45rem;margin-top:.6rem;flex-wrap:wrap}

            /* Asking and doing must not look alike. Nothing reachable from this page writes any
               more, but a read that starts a command still takes seconds and still deserves to
               look different from a link that does not. */
            .ask{background:transparent;border:1px dashed var(--rule);color:var(--ink-faint);
                 border-radius:7px;padding:.3rem .65rem;font:inherit;font-family:var(--mono);
                 font-size:.74rem;cursor:pointer;transition:border-color .15s ease,color .15s ease}
            .ask:hover{border-color:var(--accent);color:var(--ink)}
            .ask[aria-busy="true"]{opacity:.55;cursor:progress}

            /* Command output wears the terminal chrome the site uses for the same thing --
               because that is what it is, and a grey box said "quotation" instead. */
            .term{background:var(--petrol-900);border:1px solid var(--petrol-600);
                  border-radius:var(--radius);box-shadow:var(--lift-2);overflow:hidden;
                  margin-top:.75rem}
            .term-bar{display:flex;align-items:center;gap:.4rem;padding:.5rem .75rem;
                      background:var(--petrol-700);border-bottom:1px solid var(--petrol-600)}
            .dot{width:9px;height:9px;border-radius:50%;flex:none}
            .term-cmd{font-family:var(--mono);font-size:.7rem;color:var(--term-dim);
                      margin-left:.55rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .term-body{margin:0;padding:.85rem 1.05rem;font-family:var(--mono);font-size:.76rem;
                       line-height:1.62;color:var(--term-ink);white-space:pre-wrap;
                       max-height:24rem;overflow:auto}

            .ctl{display:flex;align-items:center;gap:.6rem;flex-wrap:wrap;
                 padding:.7rem .9rem;border:1px solid var(--rule);border-radius:10px;
                 background:var(--bg-raised);box-shadow:var(--lift-1);margin-bottom:.6rem}
            .ctl .q{color:var(--ink-faint);font-size:.82rem;flex:1 1 16rem;min-width:0}
            select,input{padding:.42rem .6rem;border:1px solid var(--rule);border-radius:7px;
                         background:var(--bg-sunken);color:var(--ink);font:inherit;
                         font-size:.85rem}
            input{flex:0 1 12rem;min-width:7rem}
            select{min-width:11rem;font-family:var(--mono);font-size:.8rem}
            .go{padding:.42rem .85rem;border:1px solid var(--accent);border-radius:7px;
                background:var(--accent);color:var(--accent-ink);cursor:pointer;font:inherit;
                font-size:.83rem;font-weight:600}
            .go:hover{background:var(--accent-bright);border-color:var(--accent-bright)}
            .go[aria-busy="true"]{opacity:.6;cursor:progress}

            .cards{display:grid;gap:.9rem;grid-template-columns:repeat(auto-fit,minmax(15rem,1fr))}
            .stat{font-family:var(--mono);font-size:1.5rem;font-weight:600;color:var(--ink);
                  font-variant-numeric:tabular-nums;letter-spacing:-.02em}
            .verbs{display:flex;flex-wrap:wrap;gap:.3rem;margin-top:.15rem}
            .verb{font-family:var(--mono);font-size:.7rem;padding:.14rem .45rem;
                  border:1px solid var(--rule);border-radius:5px;color:var(--ink-soft);
                  background:var(--bg-sunken)}
            .verb.no{color:var(--ink-faint);border-style:dashed}
            .empty{color:var(--ink-faint);font-size:.88rem;padding:.9rem 1.1rem;
                   border:1px dashed var(--rule);border-radius:10px}
            details.more{margin-top:.7rem}
            details.more>summary{cursor:pointer;color:var(--ink-faint);font-size:.84rem;
                                 padding:.45rem .2rem;list-style:none}
            details.more>summary::-webkit-details-marker{display:none}
            details.more>summary::before{content:"▸ ";color:var(--accent)}
            details.more[open]>summary::before{content:"▾ "}
            details.more>summary:hover{color:var(--ink)}
            .foot{margin-top:3rem;padding-top:1.2rem;border-top:1px solid var(--rule);
                  color:var(--ink-faint);font-size:.82rem;line-height:1.65;max-width:var(--measure)}
            .foot code{background:var(--bg-sunken);border:1px solid var(--rule-soft);
                       padding:.06em .34em;border-radius:4px;color:var(--accent);font-size:.95em}
            """;

    private static final String BODY = """
            <main>

            <section class="sect" id="waiting">
              <div class="sect-hd"><h2>Waiting on you</h2><span class="sp"></span></div>
              <div class="headline">
                <div class="bignum" id="wn">…</div>
                <p class="said" id="wsaid">reading every recorded review</p>
              </div>
              <div id="wlist"></div>
              <div id="wthem"></div>
            </section>

            <section class="sect" id="next">
              <div class="sect-hd"><h2>Work on next</h2><span class="sp"></span>
                <p class="lede" id="pickhow"></p></div>
              <div id="picks"><div class="empty">scoring the backlog against what you have written…</div></div>
            </section>

            <section class="sect" id="ask">
              <div class="sect-hd"><h2>Ask about one thing</h2></div>
              <div id="oneof"></div>
            </section>

            <section class="sect" id="sweeps">
              <div class="sect-hd"><h2>Sweeps</h2></div>
              <div class="ctl">
                <select id="sweep"></select>
                <button class="go" id="sweepgo">Ask</button>
                <span class="q" id="sweepsays"></span>
              </div>
              <div id="askhost"></div>
            </section>

            <section class="sect" id="builtin">
              <div class="sect-hd"><h2>Built in</h2></div>
              <div class="cards" id="cards"></div>
              <div id="skills"></div>
            </section>

            <footer class="foot">
              <b>You do not need this page.</b> Every answer on it is one command, named in the
              hover of the control that asks it, and a terminal will give you the same one.<br><br>
              Nothing here writes. Verbs that change something — <code>oss run</code>,
              <code>oss memory file</code>, <code>oss sync</code> — are not on this page and will
              not be: an outward write is confirmed at a terminal, and a browser has none.
              Extensions attach the same way, with <code>oss ext add &lt;path&gt;</code>.<br><br>
              Running at login and would rather it were not?
              <code>oss serve --uninstall</code> stops that and removes nothing.
            </footer>

            </main></div><script>
            const $=s=>document.querySelector(s);
            function el(t,c,x){const n=document.createElement(t);if(c)n.className=c;
              if(x!=null)n.textContent=x;return n}
            function link(href,cls,text){const a=el('a',cls,text);a.href=href;a.target='_blank';
              a.rel='noopener noreferrer';return a}

            // The terminal chrome, built once. Three dots, the command it ran, and the output --
            // which is what this is, so it should look like it rather than like a quotation.
            function term(){
              const t=el('div','term'), bar=el('div','term-bar');
              ['#E08066','#D8B23A','#5FBFB0'].forEach(c=>{
                const d=el('span','dot'); d.setAttribute('style','background:'+c); bar.append(d)});
              const cmd=el('span','term-cmd','');
              bar.append(cmd);
              const body=el('pre','term-body','');
              t.append(bar,body);
              t.cmd=cmd; t.body=body;
              return t;
            }

            // The page never reimplements a command. A question runs one and shows what came back,
            // so the two cannot disagree -- and the rows above are the same call the command makes,
            // not a second reading of the ledger free to disagree with the first.
            function ask(q,arg,host,btn){
              let t=host.querySelector('.term');
              if(!t){t=term();host.appendChild(t)}
              if(btn){btn.setAttribute('aria-busy','true')}
              t.body.textContent='asking…'; t.cmd.textContent='';
              const url='api/ask?q='+encodeURIComponent(q)+(arg?'&arg='+encodeURIComponent(arg):'');
              return fetch(url).then(r=>r.json()).then(d=>{
                t.body.textContent=d.error?d.error:(d.output||d.note||'');
                t.cmd.textContent=d.runs||'';
              }).catch(e=>{t.body.textContent=String(e)})
               .finally(()=>{if(btn){btn.removeAttribute('aria-busy')}});
            }

            // ------------------------------------------------------------------- waiting ---
            function verdictClass(v){
              const t=String(v||'').toLowerCase();
              if(t==='take'||t==='approve'){return 'chip take'}
              if(t==='changes'||t==='reject'){return 'chip changes'}
              return 'chip';
            }
            function waitingRow(r,urgent){
              const it=el('div',urgent?'row you':'row');
              const top=el('div','rtop');
              top.append(link(r.url,'num','#'+r.pr), el('span','repo',r.repo),
                         el('span',verdictClass(r.verdict),r.verdict||'none'),
                         el('span','why',r.why||''));
              it.append(top, el('div','rttl', r.title||'(no title)'));
              const acts=el('div','racts');
              const seen=el('button','ask','seen this?');
              seen.title='Have I worked this out before? Searches your own notes and synced issues by meaning.';
              seen.onclick=()=>ask('search',r.repo+' '+r.pr,it,seen);
              acts.append(seen);
              if(r.hasVerdict){
                const since=el('button','ask','since I reviewed');
                since.title='What the author did after your verdict.';
                since.onclick=()=>ask('followup-one',String(r.pr),it,since);
                acts.append(since);
              }
              it.append(acts);
              return it;
            }
            function waiting(){
              return fetch('api/waiting').then(r=>r.json()).then(d=>{
                const n=d.onYou.length, num=$('#wn');
                num.textContent=n===0?'clear':String(n);
                num.className=n===0?'bignum clear':'bignum';
                $('#navcount').textContent=d.checked===0?'':String(n);
                let said=n===0
                  ? 'Nothing you looked at has moved since you looked at it.'
                  : (n===1?'One pull request has moved since your verdict.'
                          :n+' pull requests have moved since your verdict.');
                if(d.checked===0){
                  said='Nothing reviewed yet — record one with oss followup --record.';
                  num.textContent='—'; num.className='bignum';
                }
                if(d.unreachable){said+='  '+d.unreachable+' unreachable ('+d.why+').'}
                $('#wsaid').textContent=said;

                const list=$('#wlist'); list.textContent='';
                if(d.onYou.length){
                  const box=el('div','rows');
                  d.onYou.forEach(r=>box.append(waitingRow(r,true)));
                  list.append(box);
                }
                const them=$('#wthem'); them.textContent='';
                if(d.onThem.length){
                  const det=el('details','more');
                  det.append(el('summary',null,d.onThem.length+' not waiting on you'));
                  const box=el('div','rows');
                  d.onThem.forEach(r=>box.append(waitingRow(r,false)));
                  det.append(box);
                  them.append(det);
                }
              }).catch(e=>{$('#wsaid').textContent='could not read the ledger: '+e});
            }

            // ---------------------------------------------------------------- work next ---
            const PICK_EMPTY={
              NO_PROFILE:'Nothing to score against yet. File a note or record a review — reviews count for more, because reviewing something means you read it.',
              NOTHING_SYNCED:'No issues cached. Run oss sync.',
              NO_OVERLAP:'Nothing in the backlog overlaps what you have written about. That is a real answer: file a few notes, or widen with oss sync.'
            };
            function picks(){
              return fetch('api/suggestions').then(r=>r.json()).then(d=>{
                const host=$('#picks'); host.textContent='';
                if(!d.items||!d.items.length){
                  host.append(el('div','empty',PICK_EMPTY[d.why]||d.error||'nothing to suggest'));
                  return;
                }
                $('#pickhow').textContent='scored against '+d.profileSize+
                  ' thing(s) you have written or reviewed — '+d.how;
                const box=el('div','rows');
                d.items.forEach(i=>{
                  const it=el('div','row'), top=el('div','rtop');
                  top.append(el('span','score',i.score.toFixed(2)),
                             link(i.url,'num','#'+i.number), el('span','repo',i.repo));
                  it.append(top, el('div','rttl',i.title));
                  if(i.because&&i.because.length){
                    it.append(el('div','rwhy','because you wrote: '+i.because.join('  ·  ')));
                  }
                  box.append(it);
                });
                host.append(box);
              }).catch(e=>{$('#picks').textContent='could not score: '+e});
            }

            // ------------------------------------------------------------------ built in ---
            function verbList(host,verbs){
              const w=el('div','verbs');
              verbs.forEach(v=>w.append(el('span','verb',v)));
              host.append(w);
            }
            function builtin(){
              return fetch('api/state').then(r=>r.json()).then(d=>{
                const host=$('#cards'); host.textContent='';
                const c=d.counts;

                const run=el('div','card');
                run.append(el('p','k','runner'));
                if(d.runners.length){
                  d.runners.forEach(e=>{
                    run.append(el('h3',null,e.name));
                    verbList(run,e.verbs);
                    if(!e.reachable){run.append(el('p',null,'its path is gone — oss ext list'))}
                    else if(e.stale){run.append(el('p',null,'oss-ext.json changed on disk; dispatch is refused until oss ext refresh '+e.name))}
                  });
                }else{
                  run.append(el('h3',null,'built in'));
                  verbList(run,d.runnerVerbs);
                  run.append(el('p',null,'a pack needs nothing attached — oss run --pack <dir> list'));
                }
                host.append(run);

                const mem=el('div','card');
                mem.append(el('p','k','memory'));
                mem.append(el('div','stat',c.notes.toLocaleString()+' notes'));
                mem.append(el('p',null,c.chunks.toLocaleString()+' chunks indexed · '+
                  c.issues.toLocaleString()+' issues cached · '+c.reviews+' reviews recorded'));
                if(d.memories.length){
                  d.memories.forEach(e=>mem.append(el('p',null,'archive attached: '+e.name)));
                }else{
                  verbList(mem,d.memoryVerbs);
                }
                host.append(mem);

                // Named, not hidden. "Not on this page" with no list is indistinguishable from
                // "not in this tool", and somebody looking for `oss sync` needs to be told where
                // it went rather than left to conclude it does not exist.
                const t=el('div','card');
                t.append(el('p','k','needs a terminal'));
                const w=el('div','verbs');
                d.needsTerminal.forEach(v=>w.append(el('span','verb no',v)));
                t.append(w);
                t.append(el('p',null,'these change something, and an outward write is confirmed where you typed it'));
                host.append(t);

                const sk=$('#skills'); sk.textContent='';
                if(d.skills&&d.skills.length){
                  const det=el('details','more');
                  const mine=d.skills.filter(s=>!s.builtIn).length;
                  det.append(el('summary',null,d.skills.length+' skills oss ask works under'+
                    (mine?(' — '+mine+' yours'):'')));
                  const box=el('div','rows');
                  d.skills.forEach(s=>{
                    const it=el('div','row'), top=el('div','rtop');
                    top.append(el('span','num',s.name),
                               el('span','chip',s.builtIn?'built in':'yours'));
                    it.append(top);
                    if(s.summary){it.append(el('div','rttl',s.summary))}
                    if(s.when&&s.when.length){it.append(el('div','rwhy','when: '+s.when.join(', ')))}
                    box.append(it);
                  });
                  det.append(box); sk.append(det);
                }
              }).catch(e=>{$('#cards').textContent='could not read local state: '+e});
            }

            // ------------------------------------------------------------------ questions ---
            // hub and pick are drawn as rows above rather than offered as buttons: they are what
            // the page opens on, and a button asking a question the page has already answered is
            // a button that redraws the answer.
            const DRAWN=['hub','pick'];
            function questions(){
              return fetch('api/questions').then(r=>r.json()).then(d=>{
                const oneof=$('#oneof'), sweep=$('#sweep');
                const sweeps=[];
                d.questions.forEach(q=>{
                  if(DRAWN.includes(q.key)){return}
                  // A question that takes an argument gets its own row with a field and the
                  // sentence it answers. `triage` needs an issue number and used to open a browser
                  // prompt() -- a modal that interrupts first and explains second, and which left
                  // the page with no way to say that the question exists at all.
                  if(q.arg){
                    const wrap=el('div');
                    const row=el('div','ctl');
                    const b=el('button','go',q.key); b.title='runs:  '+q.runs;
                    const i=el('input');
                    i.placeholder=q.arg==='num'?'issue or PR number':'what are you looking for?';
                    const say=el('span','q',q.asks);
                    const go=()=>{const v=i.value.trim(); if(!v){i.focus();return}
                      ask(q.key,v,wrap,b)};
                    b.onclick=go;
                    i.addEventListener('keydown',e=>{if(e.key==='Enter')go()});
                    row.append(i,b,say); wrap.append(row); oneof.append(wrap);
                    return;
                  }
                  sweeps.push(q);
                  const o=document.createElement('option');
                  o.value=q.key; o.textContent=q.key; sweep.appendChild(o);
                });
                const says=()=>{
                  const q=sweeps.find(x=>x.key===sweep.value);
                  $('#sweepsays').textContent=q?q.asks:'';
                  // Doubled, because this page is a Java text block: a single \\n is an escape Java
                  // consumes, so what reached the browser was a real line break inside a quoted
                  // string -- an unterminated literal, a SyntaxError, and with it the whole script.
                  // Everything below the rail is drawn by that script, so the page rendered as its
                  // one piece of static markup and nothing else.
                  sweep.title=q?(q.asks+'\\n\\nruns:  '+q.runs):'';
                };
                // Named rather than left to the browser's default selection. A select with options
                // and no value set shows the first one but reports '' until something is chosen,
                // and the sentence beside it is drawn from that value -- so the control opened
                // labelled `duplicates` with no line saying what duplicates asks.
                if(sweeps.length){sweep.value=sweeps[0].key}
                sweep.onchange=says; says();
                $('#sweepgo').onclick=()=>ask(sweep.value,null,$('#askhost'),$('#sweepgo'));
              });
            }

            // The rail follows what you are reading, so a long board still says where you are.
            (function(){
              const links=[...document.querySelectorAll('.side a.nav[href^="#"]')];
              if(!links.length||!window.IntersectionObserver)return;
              const io=new IntersectionObserver(es=>{
                es.forEach(e=>{
                  if(!e.isIntersecting)return;
                  links.forEach(a=>a.classList.toggle('on',
                    a.getAttribute('href')==='#'+e.target.id));
                });
              },{rootMargin:'-15% 0px -70% 0px'});
              document.querySelectorAll('main section.sect').forEach(s=>io.observe(s));
            })();

            // Local reads first, so the page has something true on it within a frame; the two
            // that go to the network fill in behind. Fired together rather than chained: pick
            // scores fifteen thousand issues locally and hub waits on GitHub, and neither has
            // any reason to wait for the other.
            builtin();
            questions();
            waiting();
            picks();
            </script>
            """;

    private static final String PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"color-scheme\" content=\"dark light\">"
            + FAVICON
            + "<title>oss</title>"
            + THEME_BOOT
            + "<style>" + STYLE + BOARD_CSS + "</style></head>"
            + "<body><div class=\"app\">"
            + sidebar("board")
            + BODY
            + THEME_JS
            + "</body></html>";
}
