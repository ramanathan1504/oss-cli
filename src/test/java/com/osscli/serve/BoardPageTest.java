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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the page is <em>about</em>, and what it shows of an answer.
 *
 * <p>Every capability had been moved inside the core, and the page still opened on a box asking for
 * the path of an extension — with the board's own questions below it and `triage`, which takes an
 * issue number, reachable only through a browser `prompt()` that says nothing until after it has
 * interrupted you. The order of a page is a claim about what matters on it.
 */
class BoardPageTest {

    private static final String PAGE = ServeCommand.page();

    @Test
    @DisplayName("the board comes before the extensions, because that is what the page is for")
    void boardLeads() {
        int board = PAGE.indexOf("<div class=\"grp\">board</div>");
        int oneOf = PAGE.indexOf("ask about one thing");
        int extensions = PAGE.indexOf("id=\"extsum\"");

        assertTrue(board >= 0, "the board section is missing");
        assertTrue(oneOf > board, "questions about one thing belong under the board");
        assertTrue(extensions > oneOf, "extensions must not lead a page whose subject is the board");
    }

    @Test
    @DisplayName("a question that needs a number gets a field, not a browser dialog")
    void noBrowserPrompt() {
        // prompt() is also the only reason the page needed no input markup, so its absence is
        // checked rather than the inputs' presence: adding a field and leaving the modal in place
        // would pass a test that only looked for the field.
        assertFalse(PAGE.contains("prompt(q.asks"), "triage still opens a browser prompt()");
        assertTrue(PAGE.contains("issue or PR number"), "the field must say what it wants");
    }

    @Test
    @DisplayName("the board is the same product as the site: same colours, same fonts, same toggle")
    void themeMatchesTheSite() throws java.io.IOException {
        // Two copies of a look drift, and this repository has paid for two copies of a web page
        // before. The colours were already the site's; the fonts and the way a theme is chosen
        // were not -- the page was light by default while the site is dark by default, so a
        // machine set to light showed a light board beside a dark manual.
        java.nio.file.Path site = java.nio.file.Path.of("site", "index.html");
        String html = java.nio.file.Files.readString(site);

        for (String colour :
                new String[] {"#07141A", "#E6EFF0", "#1A3540", "#0D202A", "#D8B23A", "#E4EBED", "#08161D"}) {
            assertTrue(PAGE.contains(colour), "the board dropped " + colour);
            assertTrue(html.contains(colour), "the site no longer has " + colour + " — the two have drifted");
        }
        assertTrue(PAGE.contains("JetBrains Mono") && html.contains("JetBrains Mono"), "the mono stacks differ");
        // Three states, in the site's order: dark base, light under the media query, explicit wins.
        assertTrue(PAGE.contains("prefers-color-scheme:light"), "light must come from the media query");
        assertTrue(
                PAGE.contains("[data-theme=\"light\"]") && PAGE.contains("[data-theme=\"dark\"]"),
                "an explicit choice must beat the system preference, both ways");
        assertTrue(PAGE.contains("ubuos-theme") && html.contains("ubuos-theme"), "the two remember under one key");
    }

    @Test
    @DisplayName("a slow question does not take the whole page down with it")
    void oneAskDoesNotBlockTheRest() throws Exception {
        // Measured against the released 2.2.0: with the default executor -- one dispatcher thread,
        // requests answered in order -- the board fired `hub` as it loaded, `hub` shelled out to a
        // command that takes tens of seconds against ten repositories, and the PAGE ITSELF never
        // arrived. A service that cannot serve its own HTML while answering reads as hung.
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.CountDownLatch slowStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/slow", x -> {
            slowStarted.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        server.createContext("/quick", x -> {
            x.sendResponseHeaders(204, -1);
            x.close();
        });
        // The same executor the service uses. Built here rather than reached into, because what is
        // being asserted is the behaviour a pool gives, not the fact that a field was set.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(6));
        server.start();
        try {
            int port = server.getAddress().getPort();
            new Thread(() -> get(port, "/slow")).start();
            assertTrue(slowStarted.await(5, java.util.concurrent.TimeUnit.SECONDS), "the slow request never began");

            // With one thread this blocks until /slow finishes. With a pool it answers now.
            assertEquals(204, get(port, "/quick"), "a second request must not queue behind a slow one");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static int get(int port, String path) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net
                    .URI
                    .create("http://localhost:" + port + path)
                    .toURL()
                    .openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            return c.getResponseCode();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Test
    @DisplayName("what a command prints about starting up is not shown as its answer")
    void startupChatterIsStripped() {
        String out = "Initializing local SQLite database connection...\n\n  WAITING ON YOU\n    #4229 changes";

        String shown = ServeCommand.withoutStartupChatter(out);

        assertTrue(shown.startsWith("WAITING ON YOU"), "the answer should lead: " + shown);
        assertFalse(shown.contains("Initializing"), "startup chatter reached the page: " + shown);
    }

    @Test
    @DisplayName("only the lines before the answer are dropped")
    void onlyLeadingChatter() {
        // A command that mentions the database in its actual output is reporting, not starting up.
        String out = "Initializing local SQLite database connection...\nresult\nInitializing local SQLite again";

        assertEquals("result\nInitializing local SQLite again", ServeCommand.withoutStartupChatter(out));
        assertEquals("", ServeCommand.withoutStartupChatter(null));
        assertEquals("", ServeCommand.withoutStartupChatter("Initializing local SQLite database connection..."));
    }
}
