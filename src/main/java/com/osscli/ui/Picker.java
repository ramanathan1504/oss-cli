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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Function;

/**
 * Pick one thing from a list with the arrow keys, reading what it is before committing to it.
 *
 * <p>Choosing a past conversation out of a printed list means matching an id to a row by eye and
 * typing it back -- fine for three, useless for forty. Moving a highlight and reading a preview of
 * whatever is under it is the difference between remembering which session you want and guessing.
 *
 * <p><b>It is never the only way in.</b> Raw keyboard input needs a real terminal and, on unix,
 * {@code stty}; neither is available under {@code cron}, in CI, over some remote shells, or on
 * Windows without a unix layer. So the same list is also selectable by typing a number, and every
 * command offering a picker also takes the id directly on the command line. The interactive layer
 * is a convenience on top, exactly like every other capability here -- if it cannot run, it says so
 * and falls back, rather than failing.
 *
 * <p>Drawing goes to stderr, like {@link Live}, so redirecting a command's output never collects
 * cursor movement.
 */
public final class Picker {

    private static final String ALT_SCREEN_ON = "\u001b[?1049h";
    private static final String ALT_SCREEN_OFF = "\u001b[?1049l";
    private static final String CURSOR_HIDE = "\u001b[?25l";
    private static final String CURSOR_SHOW = "\u001b[?25h";
    private static final String HOME_AND_CLEAR = "\u001b[H\u001b[J";

    private static final String DIM = "\u001b[2m";
    private static final String BOLD = "\u001b[1m";
    private static final String RESET = "\u001b[0m";
    private static final String INVERT = "\u001b[7m";

    private final PrintStream out = System.err;
    private final int width;
    private final int rows;

    /** Package-private so a test can draw a frame into a buffer and read it back. */
    Picker() {
        this.width = envInt("COLUMNS", 80, 20, 200);
        this.rows = envInt("LINES", 24, 10, 100);
    }

    private static int envInt(String name, int fallback, int min, int max) {
        try {
            String raw = System.getenv(name);
            if (raw != null && !raw.isBlank()) {
                int n = Integer.parseInt(raw.trim());
                if (n >= min) {
                    return Math.min(n, max);
                }
            }
        } catch (RuntimeException ignored) {
            // An unparseable COLUMNS/LINES is not worth failing a picker over.
        }
        return fallback;
    }

    /**
     * Shows the list and returns the chosen item, or null if the user backed out.
     *
     * @param row one line per item, already short enough to fit a terminal row
     * @param preview the detail shown for whatever is highlighted, as lines
     */
    public static <T> T choose(
            String title, List<T> items, Function<T, String> row, Function<T, List<String>> preview) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        if (items.size() == 1 && canAsk()) {
            // Nothing to navigate. Asking someone to press enter to confirm the only option is
            // ceremony, and the caller still prints what was chosen.
            //
            // Only in front of a person, though. Ungated, this was the whole of a real bug:
            // `oss history` with exactly one saved conversation took this shortcut in a pipe, and
            // the caller resumed it -- a browse command silently opening a chat session on a
            // decision nobody made. Without a terminal it falls through to the numbered fallback,
            // which reads stdin and cancels when there is nothing there.
            return items.get(0);
        }
        Picker picker = new Picker();
        if (RawMode.available()) {
            return picker.interactive(title, items, row, preview);
        }
        return picker.numbered(title, items, row);
    }

    /**
     * Whether there is a person on the other end of stdin.
     *
     * <p>Raw mode covers the arrow-key path; a console covers the typed-number fallback. Either one
     * means a question can be asked and an answer waited for. Neither means this is a pipe, a
     * script, {@code cron} or CI — where the honest answer to "which one?" is that nobody said.
     *
     * <p>{@code OSS_ASSUME_TTY} forces it, for a terminal this cannot detect.
     */
    public static boolean canAsk() {
        String forced = System.getenv("OSS_ASSUME_TTY");
        if (forced != null && !forced.isBlank()) {
            return !"0".equals(forced.trim()) && !"false".equalsIgnoreCase(forced.trim());
        }
        return RawMode.available() || System.console() != null;
    }

    // ==========================================
    // Interactive
    // ==========================================

    private <T> T interactive(String title, List<T> items, Function<T, String> row, Function<T, List<String>> preview) {
        int selected = 0;
        // Leave room for the title, the hint line, the preview block and its rule.
        int visibleRows = Math.max(3, rows - 12);
        int top = 0;

        try (RawMode raw = RawMode.enter()) {
            out.print(ALT_SCREEN_ON + CURSOR_HIDE);
            out.flush();
            InputStream in = System.in;
            while (true) {
                if (selected < top) {
                    top = selected;
                } else if (selected >= top + visibleRows) {
                    top = selected - visibleRows + 1;
                }
                draw(title, items, row, preview, selected, top, visibleRows);

                int key = read(in);
                switch (key) {
                    case Key.UP -> selected = selected == 0 ? items.size() - 1 : selected - 1;
                    case Key.DOWN -> selected = (selected + 1) % items.size();
                    case Key.HOME -> selected = 0;
                    case Key.END -> selected = items.size() - 1;
                    case Key.PAGE_UP -> selected = Math.max(0, selected - visibleRows);
                    case Key.PAGE_DOWN -> selected = Math.min(items.size() - 1, selected + visibleRows);
                    case Key.ENTER -> {
                        return items.get(selected);
                    }
                    case Key.QUIT, Key.EOF -> {
                        return null;
                    }
                    default -> {
                        // Unmapped key: redraw and wait. Silently ignoring beats guessing.
                    }
                }
            }
        } catch (IOException e) {
            // A terminal that stopped answering is not worth losing the command over.
            out.println("  ⚠ Keyboard selection stopped working (" + e.getMessage() + ").");
            return numbered(title, items, row);
        } finally {
            out.print(CURSOR_SHOW + ALT_SCREEN_OFF);
            out.flush();
        }
    }

    /** Raw mode means the terminal will not add the carriage return for us. */
    private static final String NL = "\r\n";

    /**
     * One frame, written with CRLF because the terminal is in raw mode.
     *
     * <p>{@code stty raw} turns off OPOST, and with it ONLCR -- the post-processing that normally
     * turns a newline into carriage-return-plus-newline on the way out. So a bare {@code \n} here
     * is a pure line feed: the cursor drops one row and <b>stays in the column it was already in</b>.
     * Every line then starts where the previous one ended, and the menu walks off the right of the
     * screen one entry at a time.
     *
     * <p>Package-private so it can be drawn into a buffer and inspected. Every other test in this
     * file exercises the numbered fallback, because that is the half that runs without a terminal --
     * which is exactly how a menu nobody could read shipped with twelve tests passing.
     */
    <T> void draw(
            String title,
            List<T> items,
            Function<T, String> row,
            Function<T, List<String>> preview,
            int selected,
            int top,
            int visibleRows) {

        StringBuilder b = new StringBuilder(HOME_AND_CLEAR);
        b.append(BOLD).append(title).append(RESET).append(NL).append(NL);

        int end = Math.min(items.size(), top + visibleRows);
        for (int i = top; i < end; i++) {
            String text = clip(row.apply(items.get(i)), width - 4);
            if (i == selected) {
                b.append(INVERT).append(" ▸ ").append(pad(text, width - 4)).append(RESET);
            } else {
                b.append("   ").append(text);
            }
            b.append(NL);
        }
        if (items.size() > visibleRows) {
            b.append(DIM)
                    .append("   ")
                    .append(selected + 1)
                    .append(" of ")
                    .append(items.size())
                    .append(RESET)
                    .append(NL);
        }

        b.append(NL).append(DIM).append(rule()).append(RESET).append(NL);
        for (String line : preview.apply(items.get(selected))) {
            b.append("  ").append(clip(line, width - 3)).append(NL);
        }
        b.append(DIM).append(rule()).append(RESET).append(NL);
        b.append(DIM)
                .append("  ↑↓ or j/k move   enter open   q cancel")
                .append(RESET)
                .append(NL);

        out.print(b);
        out.flush();
    }

    private String rule() {
        return "─".repeat(Math.max(10, width - 2));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\t', ' ').replaceAll("[\\r\\n]+", " ");
        return flat.length() <= max ? flat : flat.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String pad(String s, int to) {
        return s.length() >= to ? s : s + " ".repeat(to - s.length());
    }

    // ==========================================
    // Fallback
    // ==========================================

    /** The same list, chosen by typing a number. Works anywhere stdin is a person. */
    private <T> T numbered(String title, List<T> items, Function<T, String> row) {
        out.println(title);
        for (int i = 0; i < items.size(); i++) {
            out.printf("  %2d) %s%n", i + 1, clip(row.apply(items.get(i)), width - 7));
        }
        out.print("  Number to open, or enter to cancel: ");
        out.flush();
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.Charset.defaultCharset()));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return null;
            }
            int choice = Integer.parseInt(line.trim());
            if (choice >= 1 && choice <= items.size()) {
                return items.get(choice - 1);
            }
            out.println("  No such entry.");
            return null;
        } catch (NumberFormatException e) {
            out.println("  Not a number.");
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // ==========================================
    // Keys
    // ==========================================

    private static final class Key {
        static final int UP = -1;
        static final int DOWN = -2;
        static final int ENTER = -3;
        static final int QUIT = -4;
        static final int EOF = -5;
        static final int PAGE_UP = -6;
        static final int PAGE_DOWN = -7;
        static final int HOME = -8;
        static final int END = -9;
        static final int OTHER = -10;

        private Key() {}
    }

    /**
     * Reads one key, resolving the escape sequences arrow keys actually arrive as.
     *
     * <p>An arrow is three bytes ({@code ESC [ A}), which is why raw mode is needed at all: a
     * line-buffered terminal would not hand them over until enter was pressed, by which point the
     * user has typed literal bracket-A into their shell.
     */
    static int read(InputStream in) throws IOException {
        int c = in.read();
        switch (c) {
            case -1:
                return Key.EOF;
            case 3: // ctrl-c: raw mode swallowed the signal, so honour it here
                return Key.QUIT;
            case 13:
            case 10:
                return Key.ENTER;
            case 'q':
            case 'Q':
                return Key.QUIT;
            case 'j':
                return Key.DOWN;
            case 'k':
                return Key.UP;
            case 'g':
                return Key.HOME;
            case 'G':
                return Key.END;
            case 27:
                break;
            default:
                return Key.OTHER;
        }

        // ESC alone (the user pressed escape) versus ESC [ … (a cursor key). Nothing is buffered
        // yet if it was a bare escape, so a stream with nothing available means cancel.
        if (in.available() == 0) {
            return Key.QUIT;
        }
        int bracket = in.read();
        if (bracket != '[' && bracket != 'O') {
            return Key.OTHER;
        }
        int code = in.read();
        switch (code) {
            case 'A':
                return Key.UP;
            case 'B':
                return Key.DOWN;
            case 'H':
                return Key.HOME;
            case 'F':
                return Key.END;
            case '5': // PgUp arrives as ESC [ 5 ~
                consumeTilde(in);
                return Key.PAGE_UP;
            case '6': // PgDn arrives as ESC [ 6 ~
                consumeTilde(in);
                return Key.PAGE_DOWN;
            default:
                return Key.OTHER;
        }
    }

    private static void consumeTilde(InputStream in) throws IOException {
        if (in.available() > 0) {
            in.read();
        }
    }

    // ==========================================
    // Raw mode
    // ==========================================

    /**
     * Puts the terminal into raw mode for as long as it is open, and always puts it back.
     *
     * <p>Leaving a terminal in raw mode is the worst thing in this file: the shell stops echoing
     * what the user types and ctrl-c stops working, and the only way out is {@code reset} typed
     * blind. So restoration happens in {@link #close()} <em>and</em> from a shutdown hook, because
     * the case that strands people is the one where the process does not reach its own finally --
     * a kill, or an exception in the middle of a redraw.
     */
    static final class RawMode implements AutoCloseable {

        private static Boolean supported;
        private Thread hook;

        static synchronized boolean available() {
            if (supported != null) {
                return supported;
            }
            // No console means piped or scripted, whatever the platform says.
            if (System.console() == null || System.getenv("OSS_NO_KEYS") != null) {
                supported = false;
                return false;
            }
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                // A Windows console does not take stty. Rather than shell out and fail, the
                // numbered list is used, which works identically everywhere.
                supported = false;
                return false;
            }
            supported = stty("-g") != null;
            return supported;
        }

        static RawMode enter() throws IOException {
            RawMode mode = new RawMode();
            if (stty("raw -echo") == null) {
                throw new IOException("could not switch the terminal to raw mode");
            }
            mode.hook = new Thread(RawMode::restore, "picker-restore");
            Runtime.getRuntime().addShutdownHook(mode.hook);
            return mode;
        }

        @Override
        public void close() {
            restore();
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // Already shutting down; the hook is running or has run.
                }
            }
        }

        private static void restore() {
            stty("sane");
        }

        /** Runs stty against the controlling terminal, returning its output or null if it failed. */
        private static String stty(String args) {
            try {
                Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty")
                        .redirectErrorStream(false)
                        .start();
                String output;
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                    output = r.readLine();
                }
                return p.waitFor() == 0 ? (output == null ? "" : output) : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IOException e) {
                return null;
            }
        }
    }
}
