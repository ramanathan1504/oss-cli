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
import java.nio.file.Path;
import java.util.List;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * A line somebody types, with the editing they already expect.
 *
 * <p>{@code oss ask} read its input with {@code System.console().readLine()}. That is a raw read:
 * no arrow keys, no history, no ctrl-a, no completion. Spotting a typo in the third word of a long
 * question meant retyping the question, and the up arrow printed {@code ^[[A} into the middle of
 * it. Every terminal a person uses has had these for forty years, so their absence does not read as
 * a missing feature -- it reads as a broken program.
 *
 * <p>History is kept per install rather than per directory, because a question worth asking again
 * is usually worth asking about a different repository.
 *
 * <h2>It is never the only way in</h2>
 *
 * JLine needs a real terminal. Under {@code cron}, in CI, or with input piped from a file there is
 * none, and {@link #line} falls back to reading stdin plainly. The prompt still works; it just
 * stops offering the editing nobody can use in that situation anyway. Same rule as {@link Picker}:
 * the interactive layer is a convenience on top, and when it cannot run it says nothing and gets
 * out of the way.
 */
public final class Prompt implements AutoCloseable {

    /** Where the history lives, so a question survives closing the terminal. */
    private static final Path HISTORY = com.osscli.AppPaths.BASE_DIR.resolve("ask-history");

    private final Terminal terminal;
    private final LineReader reader;
    private final java.io.BufferedReader plain;

    private Prompt(Terminal terminal, LineReader reader, java.io.BufferedReader plain) {
        this.terminal = terminal;
        this.reader = reader;
        this.plain = plain;
    }

    /**
     * A prompt, interactive where that is possible.
     *
     * @param completions words worth completing on tab -- commands, or nothing
     */
    public static Prompt open(List<String> completions) {
        try {
            Terminal terminal = TerminalBuilder.builder().dumb(false).build();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new StringsCompleter(completions))
                    .variable(LineReader.HISTORY_FILE, HISTORY)
                    .build();
            return new Prompt(terminal, reader, null);
        } catch (IOException | RuntimeException e) {
            // No terminal, or one JLine will not drive. Reading stdin still works, and a prompt
            // that refuses because it cannot offer tab completion would be refusing over the
            // decoration rather than the thing being asked for.
            return new Prompt(null, null, new java.io.BufferedReader(new java.io.InputStreamReader(System.in)));
        }
    }

    /**
     * One line, or null when the person is done.
     *
     * <p>ctrl-d and ctrl-c both return null rather than throwing. They are how somebody leaves a
     * prompt, which is an ordinary thing to do and not an error to report.
     */
    public String line(String prompt) {
        if (reader != null) {
            try {
                return reader.readLine(prompt);
            } catch (UserInterruptException | EndOfFileException e) {
                return null;
            }
        }
        try {
            System.out.print(prompt);
            System.out.flush();
            return plain.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                // Closing a terminal that is already gone is not worth a message to somebody who
                // has finished asking questions.
            }
        }
    }
}
