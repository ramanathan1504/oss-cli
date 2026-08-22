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
package com.osscli.agent;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What the loop is allowed to touch: the directory it was started in, and nothing above it.
 *
 * <p>The model chooses the paths. That is the whole point of a loop and also the whole risk: a
 * suggestion of {@code ../../.ssh/id_rsa} costs nothing to write and everything to honour, and it
 * does not take malice — a model that has read a stack trace mentioning a home directory will ask
 * for a home directory.
 *
 * <p>So the rule is resolved and compared, never matched as a string. {@code ..} is normalised away
 * first, symlinks are followed, and the result must still sit under the root. Checking for the
 * characters {@code ..} instead would pass {@code src/../../etc/passwd} and fail
 * {@code src/main/java/Foo..Bar.java}, which is exactly backwards.
 *
 * <p>Separated from the tools so the rule is one implementation with one test, rather than a
 * condition each tool remembers to write. The tools that write will need the same answer, and this
 * is where they will get it.
 */
public final class Workspace {

    private final Path root;

    /**
     * @param root the directory the loop was started in; canonicalised once, here
     */
    public Workspace(Path root) {
        Path absolute = root.toAbsolutePath().normalize();
        Path real = absolute;
        try {
            // Canonicalised at construction, because resolve() canonicalises what it is given and
            // the two have to be comparable. On macOS a temporary directory is /var/... which is
            // really /private/var/..., so an un-canonicalised root made display() answer with six
            // levels of ../ for a file that was plainly inside it.
            real = absolute.toRealPath();
        } catch (Exception e) {
            // Not there yet, or unreadable. The absolute path is the best answer available and the
            // containment check below still holds, because resolve() falls back the same way.
        }
        this.root = real;
    }

    public Path root() {
        return root;
    }

    /**
     * The real path this request means, or empty when it leaves the workspace.
     *
     * <p>Empty rather than an exception: the caller turns it into a sentence the model reads and
     * retries against, and an unreadable path is an ordinary event in a loop rather than a failure
     * of the program.
     */
    public Optional<Path> resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(requested.strip());
            Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate))
                    .toAbsolutePath()
                    .normalize();
            // Symlinks are followed before the comparison where the file exists: a link inside the
            // workspace pointing outside it is the same escape wearing a different name.
            Path real = java.nio.file.Files.exists(resolved) ? resolved.toRealPath() : resolved;
            return real.startsWith(root) ? Optional.of(real) : Optional.empty();
        } catch (Exception e) {
            // An unparseable path on this platform -- a colon on Windows, a null byte anywhere --
            // is refused rather than guessed at.
            return Optional.empty();
        }
    }

    /** How a path reads back to the model: relative to the root, so replies stay portable. */
    public String display(Path path) {
        try {
            return root.relativize(path).toString();
        } catch (Exception e) {
            return path.toString();
        }
    }
}
