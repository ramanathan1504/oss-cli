package com.osscli.serve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import com.osscli.ext.ExtensionRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * <p>The model is Node-RED's: one long-running process on a known port, and a palette of
 * capabilities you add without rebuilding or restarting anything. Here the palette entries are
 * extensions -- a {@code bench} that runs something real, a {@code kb} that remembers -- and adding
 * one means pasting the path of a directory that contains an {@code oss-ext.json}.
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

    /** 1504 by default. Fixed and memorable, the way :1880 is for Node-RED — a service you
     * install once is one you return to by typing the same address, not by looking it up. */
    @Option(names = "--port", description = "Port to listen on (default: ${DEFAULT-VALUE})")
    int port = 1504;

    @Option(names = "--no-open", description = "Do not open a browser")
    boolean noOpen;

    @Override
    public Integer call() throws Exception {
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
        server.setExecutor(null);
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println("oss-cli serving on " + url + "   (ctrl-c to stop)");
        System.out.println("  attach an extension: paste the path of a repo containing oss-ext.json");
        if (!noOpen) {
            openBrowser(url);
        }
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
            sendJson(x, 200, Map.of(
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
            sendJson(x, removed ? 200 : 404, Map.of(
                    "ok", removed,
                    "error", removed ? "" : "no extension named \"" + name + "\"",
                    "extensions", snapshot()));
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

    private void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            String[] cmd = os.contains("mac")
                    ? new String[] {"open", url}
                    : os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
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
            </style></head><body><div class="wrap">
            <h1>oss-cli</h1>
            <div class="sub">One core that knows. A <b>bench</b> runs something real; a <b>kb</b> remembers.</div>

            <div class="grp">palette</div>
            <div id="list"></div>

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
                  <span class="${e.reachable?'ok':'bad'}">${e.reachable?'reachable':'MISSING'}</span>
                  <span style="flex:1"></span>
                  <button class="x" data-detach="${esc(e.name)}">detach</button></div>
                <div class="sub">${esc(e.description||'')}</div>
                <div class="verbs"><code>${esc(e.root)}</code></div>
                <div class="verbs">${e.verbs.length} verbs: ${e.verbs.map(esc).join(', ')}
                ${e.writes&&e.writes.length?' · <b>writes outward:</b> '+e.writes.map(esc).join(', '):''}</div>
              </div>`).join('');
            }
            function load(){fetch('api/extensions').then(r=>r.json()).then(d=>draw(d.extensions))}
            function attach(){
              const p=$('#path').value.trim(); if(!p)return;
              $('#msg').textContent='attaching…'; $('#msg').className='msg sub';
              fetch('api/attach',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({path:p})}).then(r=>r.json()).then(d=>{
                if(d.error){$('#msg').textContent=d.error;$('#msg').className='msg bad';return}
                $('#msg').textContent=(d.replaced?'updated ':'attached ')+d.name+' ('+d.kind+')';
                $('#msg').className='msg ok'; $('#path').value=''; draw(d.extensions);
              }).catch(e=>{$('#msg').textContent=String(e);$('#msg').className='msg bad'});
            }
            $('#go').onclick=attach;
            $('#path').addEventListener('keydown',e=>{if(e.key==='Enter')attach()});
            document.addEventListener('click',e=>{
              const n=e.target.getAttribute&&e.target.getAttribute('data-detach'); if(!n)return;
              fetch('api/detach',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({name:n})}).then(r=>r.json()).then(d=>{
                  $('#msg').textContent=d.ok?('detached '+n):(d.error||'could not detach');
                  $('#msg').className='msg '+(d.ok?'ok':'bad'); draw(d.extensions);});
            });
            load();
            </script></body></html>
            """;
}
