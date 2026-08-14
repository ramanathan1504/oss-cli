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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * That a successful filing does not print three lines of apparent failure.
 *
 * <p>{@code oss memory file note.md --topic Tooling --apply} keeps a local working copy as well as
 * handing the note to the attached archive. The whole argument list was passed to the local store,
 * which reads every argument as a path, so the extension's own options came back as
 * {@code skipped (not a file)  --topic}, {@code skipped (not a file)  Tooling} and
 * {@code skipped (not a file)  --apply} — printed <em>after</em> a filing that had entirely
 * succeeded, on every invocation that used a flag.
 *
 * <p>Which options take a value is the extension's business and cannot be known from here, so the
 * rule is deliberately not a parser: an argument is a path if it is one.
 */
class MemoryPassthroughTest {

    /** The rule under test, kept identical to {@code ExtCommand.alsoLocally}. */
    private static List<String> pathsOnly(List<String> args) {
        List<String> paths = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("-") && Files.isRegularFile(Path.of(a))) {
                paths.add(a);
            }
        }
        return paths;
    }

    @Test
    @DisplayName("the extension's options are not mistaken for files")
    void optionsAreNotPaths(@TempDir Path dir) throws IOException {
        Path note = Files.writeString(dir.resolve("note.md"), "# a note");

        List<String> kept = pathsOnly(List.of(note.toString(), "--topic", "Tooling", "--apply"));

        assertEquals(List.of(note.toString()), kept, "only the note is a file; the rest are flags and their values");
    }

    @Test
    @DisplayName("an option's value is dropped even though it does not look like an option")
    void aValueThatIsNotAFileIsDropped() {
        // "Tooling" carries no dash and would pass a naive startsWith("-") test on its own. It is
        // rejected for the only reason that survives not knowing the extension's grammar: there is
        // no such file.
        assertTrue(pathsOnly(List.of("--topic", "Tooling")).isEmpty());
    }

    @Test
    @DisplayName("several notes all survive")
    void everyRealFileIsKept(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.md"), "a");
        Path b = Files.writeString(dir.resolve("b.md"), "b");

        List<String> kept = pathsOnly(List.of(a.toString(), b.toString(), "--apply"));

        assertEquals(2, kept.size(), "filing two notes must keep two");
        assertTrue(kept.contains(a.toString()) && kept.contains(b.toString()));
    }

    @Test
    @DisplayName("a directory is not a note")
    void directoriesAreNotFiled(@TempDir Path dir) {
        assertTrue(pathsOnly(List.of(dir.toString())).isEmpty(), "a folder has no contents to copy as a note");
    }

    @Test
    @DisplayName("nothing filed locally when nothing was a path")
    void noPathsMeansNoLocalCall() {
        // The caller skips the local copy entirely in this case, so the built-in store never runs
        // and prints nothing. Silence is the correct output for "the archive handled it".
        assertTrue(pathsOnly(List.of("--apply", "--topic", "Tooling")).isEmpty());
    }

    @Test
    @DisplayName("a path that does not exist is not passed on either")
    void missingFilesAreDropped(@TempDir Path dir) {
        List<String> kept = pathsOnly(List.of(dir.resolve("absent.md").toString()));
        assertTrue(kept.isEmpty(), "the archive already reported it; repeating the complaint differently is noise");
    }

    @Test
    @DisplayName("the real caller carries the same rule")
    void theSourceStillFilters() throws IOException {
        // Asserted against the source so the filter cannot be quietly removed, which would restore
        // the noise without failing anything else.
        String src = Files.readString(Path.of("src/main/java/com/osscli/cli/ExtCommand.java"));
        assertTrue(src.contains("isRegularFile"), "alsoLocally must still pass only real files");
        assertFalse(
                src.contains("BuiltinMemory.run(\"file\", args)"),
                "the raw argument list must not reach the built-in store again");
    }
}
