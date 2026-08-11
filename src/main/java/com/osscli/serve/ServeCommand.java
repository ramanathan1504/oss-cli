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
 * see what is attached; everything it does is equally available as {@code oss-cli ext …}. What
 * matters is that <b>the set of things this can run against is open, and grows from other people's
 * machines</b> -- someone writes one file in their own repository and their bench is in the list.
 *
 * <p>That is the whole attach story, and it is deliberately dull: anyone who has a Kafka setup, or a
 * Spring project, or anything else worth running against, writes that one file in their own
 * repository and pastes the path. Nothing is uploaded, nothing is copied, and the extension stays
 * where it is and keeps being an ordinary repository.
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
 *       what {@code oss-cli ext list} would say.
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

    /** launchd label and plist path. One agent, named for what it is. */
    private static final String LABEL = "com.osscli.serve";

    private static Path plistPath() {
        return Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", LABEL + ".plist");
    }

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
            System.err.println("       Another oss-cli may already be serving. Try --port <n>.");
            return 1;
        }

        server.createContext("/", this::handlePage);
        server.createContext("/api/extensions", this::handleList);
        server.createContext("/api/attach", this::handleAttach);
        server.createContext("/api/detach", this::handleDetach);
        server.createContext("/api/doc", this::handleDoc);
        server.setExecutor(null);
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println("oss-cli serving on " + url + "   (ctrl-c to stop)");
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

    private void handlePage(HttpExchange x) throws IOException {
        if (!"/".equals(x.getRequestURI().getPath())) {
            send(x, 404, "text/plain", "not found");
            return;
        }
        send(x, 200, "text/html; charset=utf-8", PAGE);
    }

    private void handleList(HttpExchange x) throws IOException {
        sendJson(x, 200, Map.of("extensions", snapshot()));
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

    /**
     * Serve one declared documentation page as raw Markdown.
     *
     * <p>Raw, and rendered in the browser: shipping a Markdown renderer in Java to display a file
     * that is already readable as text is a dependency and a parser to maintain, for a page whose
     * job is to let you read what an extension documents about itself.
     */
    private void handleDoc(HttpExchange x) throws IOException {
        Map<String, String> q = query(x);
        Extension ext = ExtensionRegistry.byName(q.get("ext")).orElse(null);
        if (ext == null) {
            sendJson(x, 404, Map.of("error", "no extension named \"" + q.get("ext") + "\""));
            return;
        }
        // Only paths the extension DECLARED, resolved and checked against its own root.
        Path doc = ext.docPath(q.get("path"));
        if (doc == null) {
            sendJson(x, 404, Map.of("error", "not a declared doc of " + ext.getName() + ": " + q.get("path")));
            return;
        }
        if (!Files.isRegularFile(doc)) {
            sendJson(x, 404, Map.of("error", "declared but missing on disk: " + doc));
            return;
        }
        send(x, 200, "text/plain; charset=utf-8", Files.readString(doc));
    }

    private Map<String, String> query(HttpExchange x) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = x.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
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
            m.put("docs", e.getDocs());
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
        if (Files.exists(plistPath()) || Files.exists(askedMarker())) {
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
            System.out.println("  Left as-is. Change your mind later with: oss-cli serve --install");
        }
        System.out.println();
    }

    private boolean doInstall() {
        Path jar = jarPath();
        if (jar == null) {
            System.err.println("error  could not locate the running jar, so the agent would not know what to start");
            return false;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // KeepAlive with a throttle: a crash loop retries once a minute rather than spinning.
        // RunAtLoad so it is there after a reboot without being started by hand.
        String plist = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"                 "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                    <string>%s</string><string>-jar</string><string>%s</string>
                    <string>serve</string><string>--no-open</string><string>--port</string><string>%d</string>
                  </array>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key><true/>
                  <key>ThrottleInterval</key><integer>60</integer>
                  <key>StandardOutPath</key><string>%s</string>
                  <key>StandardErrorPath</key><string>%s</string>
                </dict></plist>
                """
                .formatted(LABEL, java, jar,
                        port,
                        logFile("out"), logFile("err"));
        try {
            Files.createDirectories(plistPath().getParent());
            Files.writeString(plistPath(), plist);
            // bootout first so --install is a safe way to APPLY A CHANGE, not only a first-time
            // action: without it, editing the port and re-installing leaves the old agent running.
            run("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
            int rc = run("launchctl", "bootstrap", "gui/" + uid(), plistPath().toString());
            if (rc != 0) {
                System.err.println("error  launchctl refused the agent (exit " + rc + "): " + plistPath());
                return false;
            }
            System.out.println("  ✓ will start at login — " + plistPath());
            System.out.println("    logs: " + logFile("err"));
            System.out.println("    stop it with: oss-cli serve --uninstall");
            return true;
        } catch (IOException e) {
            System.err.println("error  could not write " + plistPath() + ": " + e.getMessage());
            return false;
        }
    }

    private Integer doUninstall() {
        run("launchctl", "bootout", "gui/" + uid() + "/" + LABEL);
        try {
            boolean removed = Files.deleteIfExists(plistPath());
            System.out.println(removed ? "  ✓ removed — it will not start at login" : "  nothing installed");
            // Clear the marker too: having uninstalled, being asked again next time is the
            // reasonable behaviour, not a question that can never return.
            Files.deleteIfExists(askedMarker());
            return 0;
        } catch (IOException e) {
            System.err.println("error  could not remove " + plistPath() + ": " + e.getMessage());
            return 1;
        }
    }

    /** The jar this JVM is running, so the agent starts the same build. */
    private Path jarPath() {
        try {
            Path p = Path.of(ServeCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String logFile(String which) {
        return com.osscli.AppPaths.BASE_DIR.resolve("logs").resolve("serve." + which + ".log").toString();
    }

    private static String uid() {
        return String.valueOf(ProcessHandle.current().pid() > 0 ? new com.sun.security.auth.module.UnixSystem().getUid() : 0);
    }

    private static int run(String... cmd) {
        try {
            return new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            return -1;
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
    private static final String PAGE =
            """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>oss-cli</title><style>
            :root{--bg:#fbfaf8;--fg:#1c1b19;--mut:#6b675f;--line:#e2ded6;--card:#fff;
                  --acc:#8a5a2b;--ok:#2f6f43;--bad:#a33;--code:#f3f0ea}
            @media(prefers-color-scheme:dark){:root{--bg:#151412;--fg:#e8e4dc;--mut:#9a948a;
                  --line:#2c2a26;--card:#1c1a17;--acc:#d9a066;--ok:#7fb08c;--bad:#e08585;--code:#232019}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);
                 font:15px/1.55 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
            .wrap{max-width:920px;margin:0 auto;padding:32px 20px 64px}
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
            .doclink{display:inline-block;margin:2px 8px 2px 0;padding:3px 9px;border:1px solid var(--line);
                     border-radius:14px;font-size:12.5px;cursor:pointer;background:var(--card)}
            .doclink:hover{border-color:var(--acc);color:var(--acc)}
            .doclink.on{background:var(--acc);color:var(--card);border-color:var(--acc)}
            .docsrc{font-size:11px;text-transform:uppercase;letter-spacing:.06em;color:var(--mut);
                    margin:8px 0 4px}
            .doc h1,.doc h2,.doc h3{margin:18px 0 8px;line-height:1.3}
            .doc h1{font-size:19px} .doc h2{font-size:16px} .doc h3{font-size:14px}
            .doc pre{background:var(--code);padding:10px 12px;border-radius:8px;overflow-x:auto}
            .doc table{border-collapse:collapse;margin:10px 0;display:block;overflow-x:auto}
            .doc td,.doc th{border:1px solid var(--line);padding:5px 9px;font-size:13px;text-align:left}
            .doc li{margin:3px 0}
            </style></head><body><div class="wrap">
            <h1>oss-cli</h1>
            <div class="sub">One core that knows. A <b>bench</b> runs something real; a <b>kb</b> remembers.</div>

            <div class="grp">palette</div>
            <div id="list"></div>

            <div class="grp">docs</div>
            <div class="card" id="docsnav"></div>
            <div class="card" id="docview" hidden><div id="docbody" class="doc"></div></div>

            <div class="grp">attach an extension</div>
            <div class="card">
              <div class="row">
                <input id="path" placeholder="/path/to/a/repo containing oss-ext.json" />
                <button id="go">Attach</button>
              </div>
              <div class="msg" id="msg"></div>
            </div>

            <div class="note">
              Attaching records a path — nothing is uploaded or copied, and the extension stays an
              ordinary repository. Detaching only forgets the path; it deletes nothing.<br><br>
              This page attaches and reports. It deliberately does not <em>run</em> anything: an
              outward write must be confirmed at a terminal, and a browser has none. Run verbs from
              the CLI — <code>oss-cli bench &lt;verb&gt;</code>, <code>oss-cli kb &lt;verb&gt;</code>.
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
                ${e.stale?'<div class="verbs bad">oss-ext.json changed on disk since it was attached — detach and attach again, or <code>oss-cli ext refresh '+esc(e.name)+'</code>. Dispatch is refused until then.</div>':''}
                <div class="verbs"><code>${esc(e.root)}</code></div>
                <div class="verbs">${e.verbs.length} verbs: ${e.verbs.map(esc).join(', ')}
                ${e.writes&&e.writes.length?' · <b>writes outward:</b> '+e.writes.map(esc).join(', '):''}</div>
              </div>`).join('');
            }
            // A deliberately small Markdown subset: headings, fences, tables, lists, inline
            // code, bold and links. Enough to read a README; not a parser to maintain.
            function md(t){
              const esc2=s=>s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
              const blocks=[]; t=t.replace(/```([\\s\\S]*?)```/g,(m,c)=>{
                blocks.push('<pre><code>'+esc2(c.replace(/^\\w*\\n/,''))+'</code></pre>');
                return '\\u0000'+(blocks.length-1)+'\\u0000';});
              const out=[]; let tbl=[], list=false;
              const flushT=()=>{ if(!tbl.length)return;
                const rows=tbl.filter(r=>!/^\\s*\\|[\\s:|-]+\\|\\s*$/.test(r));
                out.push('<table>'+rows.map((r,i)=>{
                  const cells=r.replace(/^\\||\\|$/g,'').split('|').map(c=>c.trim());
                  const tag=i===0?'th':'td';
                  return '<tr>'+cells.map(c=>'<'+tag+'>'+inline(c)+'</'+tag+'>').join('')+'</tr>';
                }).join('')+'</table>'); tbl=[];};
              const flushL=()=>{ if(list){out.push('</ul>');list=false;} };
              function inline(x){ return esc2(x)
                .replace(/`([^`]+)`/g,'<code>$1</code>')
                .replace(/\\*\\*([^*]+)\\*\\*/g,'<strong>$1</strong>')
                .replace(/\\[([^\\]]+)\\]\\(([^)]+)\\)/g,'<a href="$2">$1</a>'); }
              t.split('\\n').forEach(l=>{
                if(/^\\s*\\|.*\\|\\s*$/.test(l)){flushL();tbl.push(l.trim());return;} flushT();
                const h=l.match(/^(#{1,4})\\s+(.*)/);
                if(h){flushL();out.push('<h'+h[1].length+'>'+inline(h[2])+'</h'+h[1].length+'>');return;}
                const li=l.match(/^\\s*[-*]\\s+(.*)/);
                if(li){ if(!list){out.push('<ul>');list=true;} out.push('<li>'+inline(li[1])+'</li>'); return;}
                flushL();
                if(!l.trim()){out.push('');return;}
                out.push('<p>'+inline(l)+'</p>');
              });
              flushT(); flushL();
              return out.join('\\n').replace(/\\u0000(\\d+)\\u0000/g,(m,i)=>blocks[+i]);
            }
            function drawDocs(x){
              const nav=$('#docsnav'); const withDocs=(x||[]).filter(e=>e.docs&&e.docs.length);
              if(!withDocs.length){nav.innerHTML='<span class="sub">No attached extension declares docs yet '
                +'— add a <code>\"docs\"</code> list to its oss-ext.json.</span>';return}
              nav.innerHTML=withDocs.map(e=>'<div class="docsrc">'+esc(e.name)+' · '+esc(e.kind)+'</div>'
                +e.docs.map(d=>'<span class="doclink" data-ext="'+esc(e.name)+'" data-doc="'+esc(d)+'">'
                  +esc(d)+'</span>').join('')).join('');
            }
            document.addEventListener('click',e=>{
              const el=e.target; if(!el.classList||!el.classList.contains('doclink'))return;
              document.querySelectorAll('.doclink').forEach(n=>n.classList.remove('on'));
              el.classList.add('on');
              const v=$('#docview'), b=$('#docbody');
              v.hidden=false; b.textContent='loading…';
              fetch('api/doc?ext='+encodeURIComponent(el.dataset.ext)
                    +'&path='+encodeURIComponent(el.dataset.doc))
                .then(r=>r.ok?r.text():r.json().then(j=>{throw new Error(j.error)}))
                .then(t=>{b.innerHTML=md(t); v.scrollIntoView({behavior:'smooth',block:'start'});})
                .catch(err=>{b.innerHTML='<p class="bad">'+esc(String(err.message||err))+'</p>'});
            });
            function load(){fetch('api/extensions').then(r=>r.json())
              .then(d=>{draw(d.extensions);drawDocs(d.extensions)})}
            function attach(){
              const p=$('#path').value.trim(); if(!p)return;
              $('#msg').textContent='attaching…'; $('#msg').className='msg sub';
              fetch('api/attach',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({path:p})}).then(r=>r.json()).then(d=>{
                if(d.error){$('#msg').textContent=d.error;$('#msg').className='msg bad';return}
                $('#msg').textContent=(d.replaced?'updated ':'attached ')+d.name+' ('+d.kind+')';
                $('#msg').className='msg ok'; $('#path').value='';
                draw(d.extensions); drawDocs(d.extensions);
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
                  draw(d.extensions); drawDocs(d.extensions);});
            });
            load();
            </script></body></html>
            """;
}
