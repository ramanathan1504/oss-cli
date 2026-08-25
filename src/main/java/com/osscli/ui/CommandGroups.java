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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fifteen commands, arranged by what you are trying to do.
 *
 * <p>{@code oss --help} listed them flat, so {@code bug} sat between {@code doctor} and {@code hub}
 * and the only way to find the one you wanted was to already know its name. Fifteen is a short list
 * and it was still unreadable, because an alphabet is not an order anybody thinks in: nobody opens
 * a terminal wanting the command after {@code ext}.
 *
 * <p>The groups are questions rather than categories. "what is waiting on me" is a thing somebody
 * arrives wanting; "Repository Management" is a thing somebody writes after the fact.
 *
 * <p><b>This is the only copy.</b> The documentation site groups the same commands the same way,
 * and {@code CommandGroupsTest} asserts that every command {@code --help} shows appears here
 * exactly once — so adding a command fails the build until somebody decides where it belongs,
 * rather than it quietly falling off the end of a list.
 *
 * <p>The engine prefixes are deliberately last and deliberately labelled as a choice rather than a
 * verb: {@code oss claude review 12} is {@code review}, answered by somebody in particular.
 */
public final class CommandGroups {

    private CommandGroups() {}

    /** Heading, then the commands under it, in the order they are worth reading. */
    public static final Map<String, List<String>> GROUPS = groups();

    private static Map<String, List<String>> groups() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("start with this", List.of("setup", "sync"));
        m.put("what is waiting on me", List.of("hub"));
        m.put("one pull request", List.of("review", "pr", "triage"));
        m.put("find something", List.of("search", "ask"));
        m.put("run it for real", List.of("run", "serve"));
        m.put("remember it", List.of("memory"));
        m.put("teach it", List.of("skill", "ext"));
        m.put("when something is wrong", List.of("doctor", "bug"));
        return m;
    }

    /** Every command named above, flattened -- the set the guard compares against. */
    public static List<String> all() {
        return GROUPS.values().stream().flatMap(List::stream).toList();
    }

    /** The group a command belongs to, or null when nobody has decided. */
    public static String groupOf(String command) {
        for (Map.Entry<String, List<String>> entry : GROUPS.entrySet()) {
            if (entry.getValue().contains(command)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
