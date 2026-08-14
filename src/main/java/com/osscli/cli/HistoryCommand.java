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
package com.osscli.cli;

import com.osscli.knowledge.SessionDigest;
import com.osscli.model.ChatSession;
import com.osscli.model.ChatTurn;
import com.osscli.storage.ChatSessionStore;
import com.osscli.ui.Picker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Everything you have talked through, and the way back into any of it.
 *
 * <p>Plain {@code oss history} is the interactive one: a list you move through with the arrow keys,
 * a preview of whatever is highlighted, and enter to carry on where that conversation stopped. The
 * preview matters more than the list does -- an id and a timestamp cannot tell you which of
 * Tuesday's four conversations about the same issue was the one that got somewhere.
 *
 * <p>Every part of it also works without a terminal, because this is used from scripts and over
 * connections that cannot do raw keyboard input: {@code --list} prints, {@code --show} dumps one
 * transcript, and {@code oss chat --resume <id>} needs no picker at all.
 */
@Command(name = "history", mixinStandardHelpOptions = true, description = "Browse and resume saved conversations.")
public class HistoryCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(HistoryCommand.class);

    /** Enough to hold months of work, small enough that the list stays a list. */
    private static final int DEFAULT_LIMIT = 50;

    @Option(
            names = {"-r", "--repo"},
            description = "Only conversations about this repository (owner/name).")
    private String repository;

    @Option(
            names = {"-i", "--issue"},
            description = "Only conversations about this issue or PR number.")
    private Long issueNumber;

    @Option(
            names = {"-n", "--limit"},
            description = "How many to consider. Default ${DEFAULT-VALUE}.")
    private int limit = DEFAULT_LIMIT;

    @Option(
            names = {"-l", "--list"},
            description = "Print the list and stop, without opening anything.")
    private boolean listOnly;

    @Option(
            names = {"--show"},
            paramLabel = "<id>",
            description = "Print one conversation in full and stop.")
    private Long showId;

    @Option(
            names = {"--search"},
            paramLabel = "<text>",
            description = "Find conversations by meaning, using the built-in model.")
    private String search;

    @Override
    public Integer call() throws Exception {
        if (showId != null) {
            return show(showId);
        }

        List<ChatSession> sessions = ChatSessionStore.recent(repository, issueNumber, limit);
        if (search != null && !search.isBlank()) {
            sessions = SessionSearch.rank(sessions, search);
        }

        if (sessions.isEmpty()) {
            LOGGER.info("No saved conversations{}{}.", scopeSuffix(), search == null ? "" : " matching that");
            LOGGER.info("  oss chat <number> starts one; it is saved as you go.");
            return 0;
        }

        if (listOnly) {
            for (ChatSession s : sessions) {
                // Straight to stdout rather than through the logger, so the rows carry no level or
                // timestamp prefix. Note that log4j2 here is also configured to SYSTEM_OUT, so a
                // redirect still collects the startup line above -- that is the whole CLI's
                // behaviour, not this command's, and is not worth diverging from in one place.
                System.out.println(row(s));
            }
            return 0;
        }

        ChatSession chosen =
                Picker.choose(header(sessions.size()), sessions, HistoryCommand::row, HistoryCommand::preview);
        if (chosen == null) {
            LOGGER.info("Nothing opened.");
            return 0;
        }
        LOGGER.info("Resuming conversation {} — {} #{}", chosen.id(), chosen.repository(), chosen.issueNumber());
        return ChatCommand.resume(chosen);
    }

    /** The picker, borrowed by {@code oss chat --resume} so both routes show the same list. */
    static ChatSession pick(String repository, Long issueNumber) throws Exception {
        List<ChatSession> sessions = ChatSessionStore.recent(repository, issueNumber, DEFAULT_LIMIT);
        if (sessions.isEmpty()) {
            LOGGER.error("No saved conversations to resume yet.");
            LOGGER.error("  oss chat <number> starts one.");
            return null;
        }
        return Picker.choose("Resume which conversation?", sessions, HistoryCommand::row, HistoryCommand::preview);
    }

    private String header(int count) {
        return count + " saved conversation" + (count == 1 ? "" : "s") + scopeSuffix();
    }

    private String scopeSuffix() {
        if (repository != null && issueNumber != null) {
            return " for " + repository + " #" + issueNumber;
        }
        if (repository != null) {
            return " for " + repository;
        }
        if (issueNumber != null) {
            return " for #" + issueNumber;
        }
        return "";
    }

    // ==========================================
    // Rendering
    // ==========================================

    /** One session as one line: enough to recognise it, short enough for a narrow terminal. */
    static String row(ChatSession s) {
        String live = s.heldElsewhere(ChatSessionStore.myPid(), ChatSessionStore.myHost()) ? " ●" : "";
        return String.format(
                "%-5s %-24s #%-7d %3d turns  %-9s %s%s",
                s.id(), clip(s.repository(), 24), s.issueNumber(), s.turnCount(), s.ageLabel(), overviewOf(s), live);
    }

    /**
     * What the highlighted session was about.
     *
     * <p>The stored overview is used when there is one, and one is only computed here when there is
     * not. Generating a line per session on every keystroke would put a model call between the arrow
     * key and the redraw, which is the difference between a list that moves and one that stutters.
     */
    static List<String> preview(ChatSession s) {
        List<String> lines = new ArrayList<>();
        try {
            List<ChatTurn> turns = ChatSessionStore.turns(s.id());
            lines.add(s.repository() + " #" + s.issueNumber() + (s.issueTitle() == null ? "" : " — " + s.issueTitle()));
            lines.add("started " + shortStamp(s.startedAt()) + " · last active " + s.ageLabel() + " · "
                    + turns.size() + " turns"
                    + (s.ended() ? "" : " · still open")
                    + (s.parentId() == null ? "" : " · forked from " + s.parentId()));
            if (s.heldElsewhere(ChatSessionStore.myPid(), ChatSessionStore.myHost())) {
                lines.add("● open in another terminal (pid " + s.ownerPid() + " on " + s.ownerHost() + ")");
            }
            lines.add("");
            if (s.summary() != null && !s.summary().isBlank()) {
                lines.add("earlier, summarised: " + clip(s.summary(), 200));
                lines.add("");
            }
            // The tail, because the useful question about a half-finished conversation is where it
            // got to, not how it opened.
            int from = Math.max(0, turns.size() - 4);
            for (ChatTurn t : turns.subList(from, turns.size())) {
                lines.add(t.role().label() + ": " + clip(t.content(), 400));
            }
        } catch (Exception e) {
            lines.add("(could not read this conversation: " + e.getMessage() + ")");
        }
        return lines;
    }

    private static String overviewOf(ChatSession s) {
        if (s.overview() != null && !s.overview().isBlank()) {
            return s.overview();
        }
        try {
            return SessionDigest.extractiveOverview(s, ChatSessionStore.turns(s.id()));
        } catch (Exception e) {
            return "";
        }
    }

    private int show(long id) throws Exception {
        ChatSession s = ChatSessionStore.byId(id);
        if (s == null) {
            LOGGER.error("No conversation with id {}.", id);
            return 1;
        }
        List<ChatTurn> turns = ChatSessionStore.turns(id);
        System.out.println("# " + s.repository() + " #" + s.issueNumber()
                + (s.issueTitle() == null ? "" : " — " + s.issueTitle()));
        System.out.println("# conversation " + s.id() + ", started " + s.startedAt() + ", " + turns.size() + " turns");
        System.out.println();
        System.out.print(ChatSessionStore.transcript(s, turns));
        if (!s.ended()) {
            System.out.println("(still open — oss chat --resume " + s.id() + ")");
        }
        return 0;
    }

    private static String shortStamp(String iso) {
        if (iso == null || iso.length() < 16) {
            return "unknown";
        }
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
