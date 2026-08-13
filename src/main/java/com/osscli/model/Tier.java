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
package com.osscli.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a note is something you worked out, or something you merely collected.
 *
 * <p>Harvesting whole repositories puts thousands of other people's conversations into the same
 * corpus as your own conclusions. Both are worth having and they are not the same thing: what you
 * decided is evidence, and what you pulled down for context is background. Ranked identically, the
 * background wins on volume alone -- there is far more of it -- and answers start being assembled
 * out of discussions you have never read.
 *
 * <p>So a note carries which it is, and retrieval prefers the first without discarding the second.
 * Nothing is hidden: {@code search} still finds reference material, and says what it is.
 *
 * <p><b>Promotion happens by itself.</b> The tier is read from the note every time it is embedded,
 * and the harvester rewrites a note whenever the thread changes. Comment on an issue you had only
 * collected and the next harvest records your name in it; the next sync re-reads that and the note
 * becomes yours. There is no separate step to remember, because a step you have to remember is one
 * that does not happen.
 */
public enum Tier {

    /** Yours: written by you, decided by you, or a thread you took part in. */
    KNOWLEDGE,

    /** Collected: a discussion pulled down for context, which you have had no part in. */
    REFERENCE;

    /** {@code my_role: none} -- the harvester's way of saying you did nothing in this thread. */
    private static final Pattern MY_ROLE = Pattern.compile("(?m)^my_role:\\s*(.+?)\\s*$");

    /** {@code source: repo-scan} -- found by scanning a repository rather than by your involvement. */
    private static final Pattern SOURCE = Pattern.compile("(?m)^source:\\s*(.+?)\\s*$");

    /**
     * Read the tier out of a note.
     *
     * <p>Defaults to {@link #KNOWLEDGE}, and deliberately so. Anything without this frontmatter is a
     * note you wrote, a resolution this tool recorded, or an export of your own conversation -- all
     * of them yours. Only the harvester marks material as collected, and only it can know.
     *
     * <p>Both fields must agree before a note is demoted. {@code source: repo-scan} says how it was
     * found, which is not the same as what you did in it: a thread discovered by scanning that you
     * turn out to have reviewed is still your work, and the role is what settles it.
     */
    public static Tier of(String content) {
        if (content == null || content.isBlank()) {
            return KNOWLEDGE;
        }
        // Frontmatter only. A body quoting "my_role:" in a pasted log is not a claim about the note.
        String head = content.length() > 2000 ? content.substring(0, 2000) : content;

        Matcher role = MY_ROLE.matcher(head);
        boolean uninvolved =
                role.find() && "none".equalsIgnoreCase(role.group(1).trim());

        Matcher source = SOURCE.matcher(head);
        boolean scanned =
                source.find() && "repo-scan".equalsIgnoreCase(source.group(1).trim());

        return uninvolved && scanned ? REFERENCE : KNOWLEDGE;
    }

    public static Tier of(String raw, Tier fallback) {
        if (raw == null) {
            return fallback;
        }
        for (Tier t : values()) {
            if (t.name().equalsIgnoreCase(raw)) {
                return t;
            }
        }
        return fallback;
    }
}
