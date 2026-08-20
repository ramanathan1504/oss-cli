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
package com.osscli.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That no page sends a reader to a port this program does not serve.
 *
 * <p>The landing page advertised {@code localhost:8787} for a page served on 1504 — a number that
 * appears nowhere in the product. Somebody following it gets a browser error and no clue why, and
 * the page describing the board was the one place they would have gone to find out.
 */
class AdvertisedPortTest {

    /** The port ServeCommand binds unless told otherwise. */
    private static final String PORT = "1504";

    /** Other people's services, named in examples because a reader points this tool at them. */
    private static final List<String> CONNECTS_TO = List.of("11434", "9092");

    /**
     * Every page a reader could follow, found rather than listed.
     *
     * <p>A hand-written list is how INSTALL.md kept the retired port after every other page had
     * been corrected: the guard looked at four files and that was the fifth.
     */
    private static List<Path> pages() throws IOException {
        List<Path> out = new java.util.ArrayList<>(List.of(Path.of("site/index.html")));
        try (java.util.stream.Stream<Path> md = Files.list(Path.of("."))) {
            md.filter(f -> f.getFileName().toString().endsWith(".md")).forEach(out::add);
        }
        return out;
    }

    @Test
    @DisplayName("the default port the code binds is the one the code documents")
    void theCodeAgreesWithItself() throws IOException {
        String serve = Files.readString(Path.of("src/main/java/com/osscli/serve/ServeCommand.java"));

        assertTrue(serve.contains("int port = " + PORT), "ServeCommand no longer defaults to " + PORT);
    }

    @Test
    @DisplayName("no page advertises a localhost port this program never binds")
    void noPageInventsAPort() throws IOException {
        for (Path page : pages()) {
            if (!Files.exists(page)) {
                continue;
            }
            String text = Files.readString(page);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("localhost:(\\d{2,5})").matcher(text);
            while (m.find()) {
                String found = m.group(1);
                // Ports this tool CONNECTS to rather than serves are somebody else's default and
                // belong in the documentation: Ollama listens on 11434, Kafka on 9092. What must
                // never appear is a port for a page this program is supposed to be serving.
                boolean known = PORT.equals(found) || CONNECTS_TO.contains(found);
                assertTrue(known, page + " sends readers to localhost:" + found + ", which nothing here serves");
            }
        }
    }

    @Test
    @DisplayName("the retired port is gone from every page")
    void theOldPortIsNotStillWritten() throws IOException {
        for (Path page : pages()) {
            if (Files.exists(page)) {
                assertFalse(Files.readString(page).contains("8787"), page + " still names the old hub port");
            }
        }
    }
}
