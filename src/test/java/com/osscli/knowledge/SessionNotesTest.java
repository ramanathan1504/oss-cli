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
package com.osscli.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.osscli.memory.Sessions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a transcript is filed by what it was about.
 *
 * <p>Every case here is a line taken from the real archive on this machine, because the first
 * version of this code passed everything anyone would have invented and produced notes called
 * "all set here right?" and "[Image: original 2542x950, displayed at 2000x747."
 */
class SessionNotesTest {

    private static Sessions.Turn you(String text) {
        return new Sessions.Turn(true, text);
    }

    private static Sessions.Turn back(String text) {
        return new Sessions.Turn(false, text);
    }

    // ==========================================
    // Titles
    // ==========================================

    @Test
    @DisplayName("a pull request number beats any sentence around it")
    void referenceWins() {
        // Real transcript, real opening line. The sentence is unusable and the URL is the subject.
        List<Sessions.Turn> turns = List.of(
                you("https://github.com/owner/name/pull/812 this my pr @vy made some comment"),
                back("Looking at the review comments now."));

        String title = SessionNotes.titleOf(turns, "fallback");

        assertTrue(title.startsWith("name PR 812"), title);
    }

    @Test
    @DisplayName("an issue is called an issue, not a pull request")
    void issuesAreNotPulls() {
        List<Sessions.Turn> turns = List.of(you("https://github.com/owner/name/issues/377 what we found is real"));

        assertTrue(SessionNotes.titleOf(turns, "fallback").startsWith("name issue 377"));
    }

    @Test
    @DisplayName("the turn with the subject in it beats the turn that came first")
    void substanceBeatsOrder() {
        // This is the bug the first version had: it took the first turn over twelve characters,
        // and people open with an aside far more often than with the point.
        List<Sessions.Turn> turns = List.of(
                you("all set here right?"),
                you("the RollingFileAppender skips a file when rollover happens on the hour"));

        String title = SessionNotes.titleOf(turns, "fallback");

        assertTrue(title.contains("RollingFileAppender"), title);
        assertFalse(title.contains("all set"), title);
    }

    @Test
    @DisplayName("a pasted screenshot never becomes a title")
    void imagesAreNotTitles() {
        // Six notes on the first run were named after a screenshot's pixel dimensions.
        List<Sessions.Turn> turns = List.of(
                you("[Image: original 2542x950, displayed at 2000x747. Multiply coordinates by 1.271]"),
                you("the triggering policy fires twice for a single rollover"));

        String title = SessionNotes.titleOf(turns, "fallback");

        assertFalse(title.contains("Image"), title);
        assertTrue(title.contains("triggering policy"), title);
    }

    @Test
    @DisplayName("an interruption is not a subject")
    void interruptionsAreNotTitles() {
        // The harness's own note that somebody pressed ctrl-c. As a title it says only that a
        // session stopped, which is true of the session and nothing about the work.
        List<Sessions.Turn> turns =
                List.of(you("[Request interrupted by user]"), you("the RollingFileAppender skips a file"));

        assertTrue(SessionNotes.titleOf(turns, "fallback").contains("RollingFileAppender"));
    }

    @Test
    @DisplayName("a resumed session is not titled after the summary it was handed")
    void resumedSessionsAreNotTitledFromTheirPreamble() {
        List<Sessions.Turn> turns = List.of(
                you("This session is being continued from a previous conversation that ran out of context."),
                you("now fix the JsonTemplateLayout escaping for control characters"));

        assertTrue(SessionNotes.titleOf(turns, "fallback").contains("JsonTemplateLayout"));
    }

    @Test
    @DisplayName("a session with nothing in it gets the fallback rather than a confident wrong name")
    void nothingUsableMeansFallback() {
        assertEquals("fallback", SessionNotes.titleOf(List.of(you("ok"), you("yes")), "fallback"));
        assertEquals("fallback", SessionNotes.titleOf(List.of(), "fallback"));
    }

    @Test
    @DisplayName("a title only ever loses whole words")
    void titlesCutAtWords() {
        String long_ = "the RollingFileAppender skips a file when rollover happens exactly on the hour "
                + "and the triggering policy has already fired once for that same second";

        String title = SessionNotes.titleOf(List.of(you(long_)), "fallback");

        assertTrue(title.length() <= 72, title);
        assertTrue(long_.startsWith(title), "a title ending mid-word reads as corruption: " + title);
    }

    @Test
    @DisplayName("a summary names a session the transcript could not name")
    void aWeakTitleDefersToTheSummary() {
        // "why chnaged scraping count is greter than 1" is exactly what was asked and is not
        // something anybody will find again. The summary was written for this session anyway, so
        // its opening clause costs nothing.
        List<Sessions.Turn> turns = List.of(you("why chnaged scraping count is greter than 1 is correct"));
        String summary = "The cron treated a scraping count above one as success. "
                + "It should have compared the status field instead.";

        String title = SessionNotes.titleOf(turns, "fallback", summary);

        assertTrue(title.startsWith("The cron treated a scraping count"), title);
    }

    @Test
    @DisplayName("a summary that declines to summarise does not name the note")
    void nonAnswersAreNotTitles() {
        // The prompt permits "if nothing was concluded, say exactly that" -- the right answer for a
        // session that went nowhere and the worst possible title, because every such session gets
        // the same one. Five notes came back called "Nothing was concluded."
        List<Sessions.Turn> turns = List.of(you("ok"));

        assertEquals("fallback", SessionNotes.titleOf(turns, "fallback", "Nothing was concluded."));
        assertEquals(
                "fallback",
                SessionNotes.titleOf(turns, "fallback", "The transcript contains only the opening message."));
        // A real summary still names it.
        assertTrue(SessionNotes.titleOf(turns, "fallback", "The cron compared the wrong field.")
                .startsWith("The cron compared"));
    }

    @Test
    @DisplayName("a sentence that names its own session beats a generated one")
    void whatYouSaidWinsWhenItIsUsable() {
        // What somebody actually said is the truer title whenever it is usable at all.
        List<Sessions.Turn> turns =
                List.of(you("the RollingFileAppender skips a file when rollover happens on the hour"));

        String title = SessionNotes.titleOf(turns, "fallback", "Something the model wrote instead.");

        assertTrue(title.contains("RollingFileAppender"), title);
    }

    @Test
    @DisplayName("a reference still wins over both")
    void referencesOutrankEverything() {
        List<Sessions.Turn> turns = List.of(you("check pr 4156 for me"));

        assertTrue(SessionNotes.titleOf(turns, "f", "A summary that would have been used.")
                .startsWith("PR 4156"));
    }

    @Test
    @DisplayName("a temporary directory is never a project")
    void tempDirectoriesAreNotWork() {
        // Filed as knowledge these produced notes called "Reply with exactly: OK" -- questions
        // asked of this tool, not work done with it.
        assertTrue(SessionNotes.ranInATempDirectory("private-tmp-claude-501-scratchpad"));
        assertTrue(SessionNotes.ranInATempDirectory("var-folders-xy"));
        assertTrue(SessionNotes.ranInATempDirectory("tmp"));
        // But the home folder is where people do work, and excluding it would drop real sessions.
        assertFalse(SessionNotes.ranInATempDirectory("ramanathan"));
        assertFalse(SessionNotes.ranInATempDirectory("apache-logging-log4j2"));
        assertFalse(SessionNotes.ranInATempDirectory(""));
    }

    // ==========================================
    // Filing
    // ==========================================

    @Test
    @DisplayName("no note is ever filed under the name of the program that produced it")
    void theToolIsNeverAFolder() {
        // The whole point. 541 of 837 notes in the real archive sat under claude-code, claude-web
        // or ai-studio, which is three folders for one subject and the reason none of it read as a
        // knowledge base.
        Path filed = SessionNotes.fileIn(Path.of("/archive"), "log4j", "2026-08-28", "rollover skips a file");

        for (Path part : filed) {
            String name = part.toString().toLowerCase(java.util.Locale.ROOT);
            assertFalse(
                    name.contains("claude") || name.contains("ai-studio") || name.contains("chatgpt"),
                    "the tool is an attribute and belongs in the frontmatter, not in the path: " + filed);
        }
        assertEquals(Path.of("/archive/Projects/log4j/2026-08-28-rollover-skips-a-file.md"), filed);
    }

    @Test
    @DisplayName("the same session files to the same name twice")
    void filingIsStable() {
        // A timestamped name is how one review ended up in an archive six times, each copy
        // embedded and each competing to answer the same question.
        Path a = SessionNotes.fileIn(Path.of("/a"), "log4j", "2026-08-28", "rollover skips a file");
        Path b = SessionNotes.fileIn(Path.of("/a"), "log4j", "2026-08-28", "rollover skips a file");

        assertEquals(a, b);
    }

    @Test
    @DisplayName("a hostile title cannot become a path")
    void slugsCannotEscape() {
        Path root = Path.of("/archive").toAbsolutePath().normalize();

        Path filed = SessionNotes.fileIn(root, "log4j", "2026-08-28", "../../../etc/passwd");

        assertTrue(filed.normalize().startsWith(root), filed.toString());
    }

    @Test
    @DisplayName("a skill's preamble never becomes the title of the session that ran it")
    void skillPreamblesAreNotTitles() {
        // Typing "/pr-review" expands into thousands of words of instructions that outscore
        // the request underneath. Seventeen notes came out of the first run named "Base directory
        // for this skill:" over sessions that were "review pr 606" and its neighbours. The session
        // is real; only the title belonged to the machine.
        List<Sessions.Turn> turns = List.of(
                you("review pr 606"),
                you("Base directory for this skill: /x/.claude/skills/pr-review\n\n"
                        + "# PR review\n\nProduce one review file per PR: an explanation the "
                        + "reviewer can follow, checks he can run by hand, and a draft comment."));

        String title = SessionNotes.titleOf(turns, "fallback");

        assertFalse(title.toLowerCase(java.util.Locale.ROOT).contains("base directory"), title);
        assertTrue(title.startsWith("PR 606"), title);
    }

    @Test
    @DisplayName("a pull request named in prose counts, not only one named in a link")
    void barePullRequestNumbers() {
        assertTrue(
                SessionNotes.titleOf(List.of(you("review pr 606 for me")), "f").startsWith("PR 606"));
        assertTrue(SessionNotes.titleOf(List.of(you("look at issue #707 today")), "f")
                .startsWith("issue 707"));
    }

    @Test
    @DisplayName("a version number is not a pull request")
    void versionsAreNotReferences() {
        // "2.26.1" and "log4j 2" are everywhere in these transcripts. Two digits minimum, and the
        // word has to be there.
        String title = SessionNotes.titleOf(List.of(you("the regression arrived in 2.26.1 apparently")), "f");

        assertFalse(title.startsWith("PR "), title);
    }

    @Test
    @DisplayName("a subagent's own prompt is not somebody's note")
    void agentPromptsAreNotKnowledge() {
        // Sixteen transcripts on this machine opened with the first of these and eight with the
        // second. Filed, they shared two names between twenty-four sessions and overwrote each
        // other down to two notes -- while the count reported twenty-four.
        assertTrue(SessionNotes.isAgentPrompt(
                List.of(you("You are answering a question about this project, on the user's own machine."))));
        assertTrue(SessionNotes.isAgentPrompt(List.of(you("Base directory for this skill: /x/y"))));
        assertTrue(SessionNotes.isAgentPrompt(List.of(you("READ-ONLY triage for owner/name."))));
    }

    @Test
    @DisplayName("the tool's own summarising prompt is not a session worth filing")
    void theToolDoesNotEatItsOwnOutput() {
        // Asking a command-line tool to summarise a transcript creates a session of its own, which
        // the next hourly run reads and files. 89 notes came back that way, every one titled
        // "below is a transcript of one working session on..." -- a machine writing notes about
        // its own notes, once an hour, for ever.
        List<Sessions.Turn> turns = List.of(you(Enrichment.PREAMBLE + " log4j.\n\nWrite at most four sentences."));

        assertTrue(SessionNotes.isAgentPrompt(turns), "the loop reopens the moment this stops matching");
        assertFalse(
                SessionNotes.titleOf(turns, "fallback")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("below is a"),
                "and it must never become a title either");
    }

    @Test
    @DisplayName("the detector reads the prompt from the class that sends it")
    void theTwoCannotDriftApart() throws java.io.IOException {
        // Two spellings of the same sentence would silently reopen the loop the first time the
        // prompt was reworded, and the symptom -- notes titled after a prompt -- takes an hour to
        // appear and looks like a filing bug rather than a shared-constant bug.
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/osscli/knowledge/SessionNotes.java"));

        assertTrue(source.contains("Enrichment.PREAMBLE"), "the detector must key off the prompt, not a copy of it");
    }

    @Test
    @DisplayName("a person quoting a prompt is still having a conversation")
    void onlyTheOpeningTurnCounts() {
        assertFalse(SessionNotes.isAgentPrompt(
                List.of(you("why does rollover skip a file"), you("You are answering a question about this project"))));
        assertFalse(SessionNotes.isAgentPrompt(List.of(you("why does rollover skip a file"))));
        assertFalse(SessionNotes.isAgentPrompt(List.of()));
    }

    @Test
    @DisplayName("two sessions on one day about one thing do not become one note")
    void collisionsDoNotEatNotes(@org.junit.jupiter.api.io.TempDir Path archive) throws java.io.IOException {
        // 134 transcripts filed, 90 files on disk, and nothing said about the difference. A note
        // that vanishes silently is worse than one never written, because the count says it worked.
        Path first = SessionNotes.fileInWithoutClobbering(archive, "log4j", "2026-08-28", "PR 812", "session-a");
        java.nio.file.Files.createDirectories(first.getParent());
        java.nio.file.Files.writeString(first, "---\nsession: session-a\n---\n# PR 812\n");

        Path second = SessionNotes.fileInWithoutClobbering(archive, "log4j", "2026-08-28", "PR 812", "session-b");

        assertFalse(first.equals(second), "the second session would have overwritten the first");
    }

    @Test
    @DisplayName("the same session refiles onto its own note rather than beside it")
    void refilingIsAnUpdate(@org.junit.jupiter.api.io.TempDir Path archive) throws java.io.IOException {
        // This is what keeps an hourly job from leaving a copy per run -- the failure that put six
        // copies of one review in a real archive, each embedded, each answering the same question.
        Path first = SessionNotes.fileInWithoutClobbering(archive, "log4j", "2026-08-28", "PR 812", "session-a");
        java.nio.file.Files.createDirectories(first.getParent());
        java.nio.file.Files.writeString(first, "---\nsession: session-a\n---\n# PR 812\n");

        assertEquals(
                first, SessionNotes.fileInWithoutClobbering(archive, "log4j", "2026-08-28", "PR 812", "session-a"));
    }

    // ==========================================
    // Topics
    // ==========================================

    private static final Map<String, List<String>> TOPICS = Map.of(
            "log4j", List.of("log4j", "appender", "layout"),
            "kafka", List.of("kafka", "partition"));

    @Test
    @DisplayName("the subject is whatever the transcript is mostly about")
    void topicComesFromTheTerms() {
        SessionNotes.Scored scored = SessionNotes.topicOf("the log4j appender and its layout are wrong", "", TOPICS);

        assertEquals("log4j", scored.topic());
        assertTrue(scored.why().contains("matched"), scored.why());
    }

    @Test
    @DisplayName("one stray mention still files, and says it is a guess")
    void oneHitIsHonestAboutItself() {
        // A build failure that happens to log through log4j is probably not a log4j note. It is
        // still more findable under log4j than in a junk drawer called "general", so it goes
        // there -- and the note records that a single term put it there, which is the only way
        // anybody ever notices a wrong filing.
        SessionNotes.Scored scored = SessionNotes.topicOf("the build failed and log4j printed it", "", TOPICS);

        assertEquals("log4j", scored.topic());
        assertTrue(scored.why().contains("guess"), scored.why());
    }

    @Test
    @DisplayName("the junk drawer is only for a transcript that matched nothing")
    void generalIsTheLastResort() {
        assertEquals(
                "general",
                SessionNotes.topicOf("the build failed and printed it", "", TOPICS)
                        .topic());
    }

    @Test
    @DisplayName("a directory outranks a single stray mention")
    void directoryBeatsAGuess() {
        // Both are weak evidence and one of them is a fact: the person was working in that
        // checkout. A single word in passing should not outvote where the work happened.
        SessionNotes.Scored scored =
                SessionNotes.topicOf("the build failed and log4j printed it", "confluent-kafka", TOPICS);

        assertEquals("kafka", scored.topic());
    }

    @Test
    @DisplayName("the directory decides when the words do not")
    void directoryBreaksTheTie() {
        SessionNotes.Scored scored = SessionNotes.topicOf("fix this please", "owner-appender-tools", TOPICS);

        assertEquals("log4j", scored.topic());
        assertTrue(scored.why().contains("ran in"), scored.why());
    }

    @Test
    @DisplayName("a filing decision always says what decided it")
    void everyDecisionIsExplained() {
        for (String text : List.of("nothing here at all", "log4j appender layout", "fix this")) {
            assertFalse(
                    SessionNotes.topicOf(text, "", TOPICS).why().isBlank(),
                    "a filing nobody can argue with is one nobody can correct: " + text);
        }
    }

    // ==========================================
    // Projects
    // ==========================================

    @Test
    @DisplayName("the checkout is recovered from the folder Claude Code named after it")
    void projectFromTranscriptPath() {
        Path t = Path.of(System.getProperty("user.home"), ".claude/projects/-Users-x-apache-name/a.jsonl");

        // The home prefix is on every one of them and identifies nothing.
        assertFalse(SessionNotes.projectOf(t).startsWith("-"), SessionNotes.projectOf(t));
        assertTrue(SessionNotes.projectOf(t).endsWith("name"), SessionNotes.projectOf(t));
    }

    @Test
    @DisplayName("a scratchpad session reports the checkout, not the temporary folder")
    void scratchpadsReportTheirRealProject() {
        Path t = Path.of("/x/-private-tmp-claude-501--Users-ramanathan-apache-name-abc-scratchpad/s.jsonl");

        String project = SessionNotes.projectOf(t);

        assertTrue(project.contains("name"), project);
        assertFalse(project.contains("scratchpad"), project);
    }

    // ==========================================
    // The note
    // ==========================================

    @Test
    @DisplayName("the tool is recorded, in the frontmatter, where an attribute belongs")
    void toolIsMetadata() {
        Sessions.Session session = new Sessions.Session(
                "claude-code",
                "abc",
                Path.of("/t/abc.jsonl"),
                "2026-08-28T10:00:00Z",
                List.of("**you:** why"),
                List.of(you("why does rollover skip a file"), back("because the policy fired twice")),
                List.of("/src/RollingFileAppender.java"));

        String note = SessionNotes.noteFor(
                session,
                new SessionNotes.Scored("log4j", 9, "matched log4j"),
                "apache-name",
                "rollover",
                List.of("/src/A.java"));

        assertTrue(note.contains("tool: claude-code"), note);
        assertTrue(note.contains("topic: log4j"), note);
        assertTrue(note.contains("filed-because: matched log4j"), note);
    }

    @Test
    @DisplayName("a session note carries the headings the digest already mines")
    void notesFeedTheDigest() {
        // Digest reads these two headings out of every note the other harvesters write. A session
        // note that used its own headings would sit in a second pile with its own shape and never
        // reach a digest at all.
        Sessions.Session session = new Sessions.Session(
                "claude-code",
                "abc",
                Path.of("/t/abc.jsonl"),
                "2026-08-28T10:00:00Z",
                List.of("**you:** why"),
                List.of(you("why does rollover skip a file"), back("because the policy fired twice")),
                List.of());

        String note = SessionNotes.noteFor(
                session, new SessionNotes.Scored("log4j", 9, "matched"), "p", "rollover", List.of());

        for (String heading : List.of("The Problem (What & Where)", "The Solution (How)")) {
            assertTrue(note.contains("## " + heading), "missing " + heading + " in:\n" + note);
        }
    }
}
