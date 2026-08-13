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
package com.osscli.ui;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A live status line: what the command is doing, right now, and for how long.
 *
 * <p>A command that prints nothing for forty seconds is indistinguishable from one that has hung.
 * The user cannot tell whether to wait or to press ctrl-c, and the difference matters most exactly
 * when the work is slow -- a sweep, an embedding pass, a hundred API calls. So the rule here is that
 * <b>anything that can take more than a second says what it is doing while it does it.</b>
 *
 * <p>Three things this deliberately gets right, because each is a way these go wrong:
 *
 * <ul>
 *   <li><b>It writes to stderr, never stdout.</b> Output is piped, redirected and parsed;
 *       {@code oss ext list > file} must not collect spinner frames. Progress is commentary, not
 *       result, and the two go to different places.
 *   <li><b>It degrades to plain lines when there is no terminal.</b> Carriage-return animation in a
 *       log file produces one unreadable mega-line. Not a TTY means one line per step, no cursor
 *       tricks, no colour.
 *   <li><b>It always clears the line it drew.</b> A spinner that is not erased leaves half a frame
 *       in front of the next real output, which looks like corruption.
 * </ul>
 */
public final class Live implements AutoCloseable {

    /** Braille dots: one cell wide in every terminal font, unlike most emoji or block spinners. */
    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private static final long TICK_MS = 90;

    /**
     * Something to read while a long job runs.
     *
     * <p>Only after {@link #QUIP_AFTER_MS}, because a joke attached to work that finishes in 200ms
     * is noise, and only ever in the dim tail of the line, where it cannot be mistaken for status.
     * They are about the trade this tool is used for, so they read as company rather than filler.
     */
    private static final String[] QUIPS = {
        "a vector is just an opinion with 384 decimal places",
        "somewhere, a cache is being invalidated. it is not going well",
        "the embedding does not know what your code does either. it is guessing politely",
        "cosine similarity: the art of being confidently approximately right",
        "this is still faster than reading the whole issue tracker yourself",
        "every index is a bet that you will ask about this later",
        "the model has read your notes. it has opinions about your naming",
        "somebody once fixed this exact bug. probably you. eighteen months ago",
        "retrieval is just grep that went to university",
        "no daemon was harmed in the making of these vectors",
        "the hard part was never the maths, it was remembering where you put it",
        "a 22 MB model, doing arithmetic, on your laptop, offline. we live in the future",
    };

    /** Long enough that quick work stays silent and clean. */
    private static final long QUIP_AFTER_MS = 8_000;

    /** Slow enough to read one before it changes. */
    private static final long QUIP_EVERY_MS = 13_000;

    private final PrintStream out = System.err;
    private final boolean animated;
    private final boolean quips;
    private final long startedAt = System.nanoTime();
    private final AtomicReference<String> status = new AtomicReference<>("");
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String title;
    private Thread ticker;
    private volatile int lastWidth = 0;

    private Live(String title) {
        this.title = title;
        // No console means piped, redirected, cron or CI. NO_COLOR is the de-facto opt-out and is
        // honoured because someone who sets it has already said what they want.
        this.animated = System.console() != null && System.getenv("NO_COLOR") == null;
        this.quips = this.animated && System.getenv("OSS_NO_QUIPS") == null;
    }

    /** Begin a live line. Use in try-with-resources so it is always cleared. */
    public static Live start(String title) {
        Live live = new Live(title);
        if (live.animated) {
            live.ticker = new Thread(live::spin, "live-status");
            live.ticker.setDaemon(true); // must never hold the JVM open
            live.ticker.start();
        } else {
            live.out.println("… " + title);
        }
        return live;
    }

    /** Update what is happening now. Cheap; call it as often as the work has stages. */
    public Live step(String what) {
        status.set(what == null ? "" : what);
        if (!animated) {
            // Plain mode still reports progress -- it just cannot redraw, so each step is a line.
            out.println("  · " + what);
        }
        return this;
    }

    private void spin() {
        int i = 0;
        while (running.get()) {
            draw(FRAMES[i++ % FRAMES.length]);
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void draw(String frame) {
        String s = status.get();
        String line = "\r\u001b[36m" + frame + "\u001b[0m " + title
                + (s.isEmpty() ? "" : " \u001b[2m— " + s + "\u001b[0m")
                + " \u001b[2m" + elapsed() + "\u001b[0m"
                + quip();
        // Pad to the previous width so a shorter status cannot leave the tail of a longer one
        // stranded on screen.
        int visible = visibleLength(line);
        StringBuilder padded = new StringBuilder(line);
        for (int i = visible; i < lastWidth; i++) {
            padded.append(' ');
        }
        lastWidth = Math.max(visible, 0);
        out.print(padded);
        out.flush();
    }

    /** Length ignoring ANSI escapes, so padding maths is not thrown off by colour codes. */
    private static int visibleLength(String s) {
        return s.replaceAll("\u001b\\[[0-9;]*m", "").length() - 1; // -1 for the leading \r
    }

    private String elapsed() {
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        return ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0);
    }

    /**
     * The dim tail of the line, once the wait is long enough to be worth furnishing.
     *
     * <p>Empty until {@link #QUIP_AFTER_MS}, and empty entirely when {@code OSS_NO_QUIPS} is set --
     * somebody watching a build log at three in the morning is entitled to a plain line, and asking
     * is cheaper than guessing.
     */
    private String quip() {
        if (!quips) {
            return "";
        }
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        if (ms < QUIP_AFTER_MS) {
            return "";
        }
        int i = (int) ((ms - QUIP_AFTER_MS) / QUIP_EVERY_MS) % QUIPS.length;
        return "  [2;3m" + QUIPS[i] + "[0m";
    }

    private synchronized void clear() {
        if (animated && lastWidth > 0) {
            out.print("\r");
            out.print(" ".repeat(lastWidth + 1));
            out.print("\r");
            out.flush();
            lastWidth = 0;
        }
    }

    /** Finish and leave a single settled line behind. */
    public void done(String summary) {
        settle("\u001b[32m", "✓", summary);
    }

    public void fail(String why) {
        settle("\u001b[31m", "✗", why);
    }

    /**
     * The one line left behind, coloured only when something is there to render it.
     *
     * <p>These used to emit their escapes unconditionally, so the verdict line carried raw ANSI into
     * exactly the places this class is careful everywhere else not to: a redirected log, a cron
     * mail, a CI transcript. The class promised "no colour" without a terminal and then wrote colour
     * anyway -- a promise kept in three places and broken in the fourth.
     */
    private void settle(String colour, String mark, String tail) {
        stop();
        String suffix = tail == null || tail.isEmpty() ? "" : " — " + tail;
        if (animated) {
            out.println(colour + mark + "\u001b[0m " + title + suffix + " \u001b[2m" + elapsed() + "\u001b[0m");
        } else {
            out.println(mark + " " + title + suffix + " (" + elapsed() + ")");
        }
    }

    private void stop() {
        if (running.compareAndSet(true, false)) {
            if (ticker != null) {
                ticker.interrupt();
            }
            clear();
        }
    }

    /**
     * Clears the line without printing a verdict.
     *
     * <p>Called by try-with-resources. If {@link #done} or {@link #fail} already ran this does
     * nothing, so the common shape -- spin, then report -- needs no special handling, and an
     * exception on the way out still cannot leave a spinner frame on screen.
     */
    @Override
    public void close() {
        stop();
    }
}
