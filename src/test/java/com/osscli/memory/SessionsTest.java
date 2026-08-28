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
package com.osscli.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the transcripts three other programs wrote.
 *
 * <p>The formats belong to Claude Code, codex and gemini and none of them promised to keep them
 * still. So every case here is a shape seen in a real file on a real machine, and the failure mode
 * being guarded against is always the same one: reading a transcript, extracting nothing, and
 * reporting the file as read.
 */
class SessionsTest {

    private static Path write(Path dir, String name, String body) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        // Padded past MIN_BYTES: discovery skips tiny files, and a fixture that trips that filter
        // would make this test pass for the wrong reason.
        StringBuilder pad = new StringBuilder(body);
        while (pad.length() < Sessions.MIN_BYTES + 64) {
            pad.append('\n');
        }
        Files.writeString(p, pad.toString(), StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("all three tools are found, wherever each one keeps its sessions")
    void discoversEveryTool(@TempDir Path home) throws IOException {
        write(
                home.resolve(".claude/projects/-Users-x"),
                "a.jsonl",
                "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}");
        write(
                home.resolve(".codex/sessions/2026"),
                "rollout-b.jsonl",
                "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}");
        write(home.resolve(".gemini/tmp/proj/chats"), "session-c.json", "{\"messages\":[]}");

        List<Path> found = Sessions.discover(home);

        assertEquals(3, found.size(), found.toString());
        assertEquals(
                List.of("claude-code", "codex", "gemini"),
                found.stream().map(Sessions::toolOf).sorted().toList());
    }

    @Test
    @DisplayName("a session someone opened and closed is not a note")
    void tinyTranscriptsAreSkipped(@TempDir Path home) throws IOException {
        Path dir = home.resolve(".claude/projects/-Users-x");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("stub.jsonl"), "{}\n", StandardCharsets.UTF_8);

        assertTrue(Sessions.discover(home).isEmpty(), "an empty session became a note");
    }

    @Test
    @DisplayName("the assistant is recognised under all three names it goes by")
    void everySpeakerNameIsRead() {
        // gemini writes "gemini", the Gemini API writes "model", Claude Code and codex write
        // "assistant". Knowing only the last dropped every gemini answer while still counting the
        // file as read -- found on a real transcript, which came out with zero turns.
        for (String name : List.of("assistant", "gemini", "model")) {
            String turn = Sessions.turnOf(node("{\"type\":\"" + name + "\",\"content\":\"answered\"}"));
            assertNotNull(turn, name + " was not read as the assistant");
            assertTrue(turn.startsWith("**assistant:**"), turn);
        }
        assertTrue(Sessions.turnOf(node("{\"type\":\"user\",\"content\":\"asked\"}"))
                .startsWith("**you:**"));
    }

    @Test
    @DisplayName("a content block is read by its text, not by a type label beside it")
    void bareTextBlocksAreRead() {
        // gemini's blocks are {"text": "..."} with no type at all; a check for type == "text" read
        // every one of them as empty.
        assertEquals("hello\n", Sessions.textOf(node("[{\"text\":\"hello\"}]")));
        assertEquals("hello\n", Sessions.textOf(node("[{\"type\":\"text\",\"text\":\"hello\"}]")));
        assertEquals("hello", Sessions.textOf(node("\"hello\"")));

        // Tool calls and their results are the bulk of a transcript and none of what anyone wants
        // to read a year later -- and they are where the keys and file contents are.
        assertEquals("", Sessions.textOf(node("[{\"type\":\"tool_use\",\"input\":{\"cmd\":\"ls\"}}]")));
    }

    @Test
    @DisplayName("nothing but the two speakers gets in")
    void otherEntriesAreIgnored() {
        for (String type : List.of("attachment", "queue-operation", "last-prompt", "atis-latch", "system", "")) {
            assertEquals(null, Sessions.turnOf(node("{\"type\":\"" + type + "\",\"content\":\"x\"}")), type);
        }
    }

    @Test
    @DisplayName("a transcript is budgeted, not concatenated")
    void turnsAndSessionsAreCapped(@TempDir Path home) throws IOException {
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < Sessions.MAX_TURNS * 3; i++) {
            jsonl.append("{\"type\":\"user\",\"message\":{\"content\":\"turn ")
                    .append(i)
                    .append("\"}}\n");
        }
        Path f = write(home.resolve(".claude/projects/-p"), "big.jsonl", jsonl.toString());

        Sessions.Session s = Sessions.read(f);

        // The rule digest learned the hard way: 335 notes rendered whole came to 23 MB.
        assertEquals(Sessions.MAX_TURNS, s.turns().size());
        assertTrue(Sessions.noteFor(s).contains("First " + Sessions.MAX_TURNS + " turns"), "the cap must say so");
    }

    @Test
    @DisplayName("one enormous turn is clipped, and the note says where to find the rest")
    void longTurnsAreClipped(@TempDir Path home) throws IOException {
        String huge = "x".repeat(Sessions.MAX_TURN_CHARS * 4);
        Path f = write(
                home.resolve(".claude/projects/-p"),
                "one.jsonl",
                "{\"type\":\"user\",\"message\":{\"content\":\"" + huge + "\"}}");

        Sessions.Session s = Sessions.read(f);

        assertEquals(1, s.turns().size());
        assertTrue(s.turns().get(0).length() < Sessions.MAX_TURN_CHARS + 64, "a pasted stack trace is not the note");
        assertTrue(Sessions.noteFor(s).contains(f.toString()), "the whole transcript must still be findable");
    }

    @Test
    @DisplayName("a secret in a transcript is replaced, not the passage dropped")
    void secretsAreRedacted(@TempDir Path home) throws IOException {
        Path f = write(
                home.resolve(".claude/projects/-p"),
                "keys.jsonl",
                "{\"type\":\"user\",\"message\":{\"content\":\"try password=hunter2-swordfish on it\"}}");

        String note = Sessions.noteFor(Sessions.read(f));

        // Real transcripts on a real machine carried two sets of JDBC credentials and a password,
        // and the troubleshooting around each was the part worth keeping.
        assertFalse(note.contains("hunter2-swordfish"), note);
        assertTrue(note.contains("REDACTED"), note);
        assertTrue(note.contains("try"), "the surrounding sentence was dropped with the secret");
    }

    @Test
    @DisplayName("a second harvest rewrites the note it wrote, it does not add a second one")
    void namesAreStable(@TempDir Path home) throws IOException {
        Path f = write(home.resolve(".claude/projects/-p"), "abc.jsonl", "{\"type\":\"user\",\"content\":\"hi\"}");

        assertEquals(Sessions.nameFor(Sessions.read(f)), Sessions.nameFor(Sessions.read(f)));
        assertEquals("session-claude-code-abc.md", Sessions.nameFor(Sessions.read(f)));
    }

    @Test
    @DisplayName("a transcript name cannot become a path")
    void hostileNamesStayNames(@TempDir Path home) throws IOException {
        // The name comes from another program's file on disk; treating it as trusted is how a note
        // archive gets written outside itself.
        Sessions.Session s = new Sessions.Session(
                "claude-code", "../../etc/passwd", Path.of("/x"), "", List.of("t"), List.of(), List.of());

        String name = Sessions.nameFor(s);

        assertFalse(name.contains("/"), name);
        assertFalse(name.contains("\\"), name);
        Path root = Path.of("/archive").toAbsolutePath().normalize();
        assertTrue(root.resolve(name).normalize().startsWith(root), name);
    }

    @Test
    @DisplayName("garbage in a transcript costs that transcript and nothing else")
    void malformedInputDoesNotThrow(@TempDir Path home) throws IOException {
        // These formats belong to three other programs and change without notice. A harvest that
        // throws on the first surprise collects nothing from the hundreds of files behind it.
        for (String junk : List.of(
                "not json at all",
                "{\"type\":\"user\"",
                "{\"type\":\"user\",\"message\":{\"content\":null}}",
                "{\"type\":null}",
                "[]",
                " ")) {
            Path f = write(home.resolve(".claude/projects/-p"), "j" + junk.hashCode() + ".jsonl", junk);
            Sessions.Session s = Sessions.read(f);
            assertNotNull(s, junk);
            assertNotNull(Sessions.noteFor(s), junk);
        }
    }

    @Test
    @DisplayName("an unparseable json chat is read as empty rather than throwing")
    void malformedJsonChatIsSafe(@TempDir Path home) throws IOException {
        Path f = write(home.resolve(".gemini/tmp/p/chats"), "session-x.json", "{\"messages\": [ broken");

        assertFalse(Sessions.read(f).worthKeeping());
    }

    private static com.fasterxml.jackson.databind.JsonNode node(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
