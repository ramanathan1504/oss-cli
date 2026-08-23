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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Saying yes must leave something serving.
 *
 * <p>"Keep it running" installed a service and then kept the port, so the service it had just
 * installed could not bind and its restart policy waited out the throttle. Nothing said so: the
 * terminal printed a tick, and the page carried on working because the terminal was still the thing
 * answering it. The failure appeared a minute later, as the page being gone right after the
 * terminal was closed — which reads as "the install did nothing", and which is the one moment
 * nobody is reading a service log.
 *
 * <p>What is checked here is the part that made it invisible: the confirmation. Whether launchd or
 * systemd actually accepts a given definition needs those systems and is asserted in {@link
 * AutostartAllPlatformsTest} as far as one machine can; that the tick is only printed when
 * something is genuinely answering is checkable anywhere, and it is what was missing.
 */
class HandoverTest {

    /** A port nothing is on. Bound and released, so it is free rather than merely unlikely. */
    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HttpServer serving(String title) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = ("<html><head><title>" + title + "</title></head><body></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/", x -> {
            x.getResponseHeaders().set("Content-Type", "text/html");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("the tick waits for the service to be the thing on the port")
    void answersWhenTheServiceIsUp() throws Exception {
        HttpServer service = serving("oss");
        try {
            assertTrue(ServeCommand.answers(service.getAddress().getPort(), Duration.ofSeconds(5)));
        } finally {
            service.stop(0);
        }
    }

    @Test
    @DisplayName("a port nobody took is not reported as answering, and is waited on before that is said")
    void nothingThereIsNotSuccess() throws Exception {
        int port = freePort();

        long began = System.nanoTime();
        boolean up = ServeCommand.answers(port, Duration.ofSeconds(1));
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        assertFalse(up, "a refused connection was read as a running service");
        // A service does not come up in the instant the port is released -- a JVM has to start. One
        // refused connection is the expected first answer, not the verdict, so the budget has to be
        // spent before failure is reported.
        assertTrue(tookMs >= 1000, "gave up after " + tookMs + "ms without waiting out the budget");
    }

    @Test
    @DisplayName("something else on the port is not this service")
    void anotherSurfaceIsNotTheService() throws Exception {
        // `oss run hub` defaults to this same port. A TCP connect cannot tell the two apart and
        // would report the handover as complete while the board was gone -- which is the exact
        // mistake this check exists to avoid, made a second time.
        HttpServer other = serving("oss run hub");
        try {
            assertFalse(ServeCommand.answers(other.getAddress().getPort(), Duration.ofSeconds(1)));
        } finally {
            other.stop(0);
        }
    }

    @Test
    @DisplayName("stopping the server frees the port, which is what makes the handover possible")
    void stoppingReleasesThePort() throws Exception {
        HttpServer service = serving("oss");
        int port = service.getAddress().getPort();
        assertTrue(ServeCommand.answers(port, Duration.ofSeconds(5)));

        service.stop(0);

        // The order the handover depends on: released first, then the service is told to start.
        // While this process held the port there was nothing the service could do but fail to bind.
        assertFalse(ServeCommand.answers(port, Duration.ofSeconds(1)));
        try (ServerSocket rebound = new ServerSocket(port, 0, InetAddress.getLoopbackAddress())) {
            assertTrue(rebound.isBound(), "the port was not actually given up");
        }
    }
}
