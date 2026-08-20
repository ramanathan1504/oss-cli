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
package com.osscli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the tool does not name one company's product for a setting that is a list of folders.
 *
 * <p>{@code drive.paths} is any directory a person keeps notes in. The code said "Scanning Google
 * Drive paths recursively for AI Studio logs" — to a user whose folders are in iCloud, and to every
 * user who does not have either product. A sentence naming somebody else's product for your files
 * is wrong for most readers and unhelpful to the rest.
 *
 * <p>The providers this tool genuinely talks to — Ollama, Claude, Gemini, OpenAI, GitHub — are a
 * different thing: naming those is naming what is actually being called.
 */
class GenericVoiceTest {

    /**
     * Products the code claimed the user's folders WERE.
     *
     * <p>Deliberately just these two, and the second is a phrase rather than a name. Naming a
     * service as one example among several ("point at a synced folder (iCloud, Dropbox, Drive)") is
     * helpful; naming one for behaviour genuinely its own — iCloud files not downloaded yet — is
     * accurate; and naming one as a product you can import an export <em>from</em> is a fact. What
     * broke was telling every user that the folders they configured are Google Drive and that the
     * files in them are AI Studio logs — claims about <em>their</em> archive, not about a source.
     */
    private static final List<String> NOT_OURS = List.of("Google Drive", "AI Studio logs");

    @Test
    @DisplayName("no storage vendor is named for the user's own note folders")
    void noStorageVendorInUserFacingText() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/com/osscli"))) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String vendor : NOT_OURS) {
                    if (text.contains(vendor)) {
                        offences.add(file.getFileName() + " names " + vendor);
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "drive.paths is a list of folders, not a product: " + offences);
    }
}
