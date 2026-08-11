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
 *       {@code oss-cli ext list > file} must not collect spinner frames. Progress is commentary, not
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

    private final PrintStream out = System.err;
    private final boolean animated;
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
                + " \u001b[2m" + elapsed() + "\u001b[0m";
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
        stop();
        out.println("\u001b[32m✓\u001b[0m " + title + (summary == null || summary.isEmpty() ? "" : " — " + summary)
                + " \u001b[2m" + elapsed() + "\u001b[0m");
    }

    public void fail(String why) {
        stop();
        out.println("\u001b[31m✗\u001b[0m " + title + (why == null || why.isEmpty() ? "" : " — " + why)
                + " \u001b[2m" + elapsed() + "\u001b[0m");
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
