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

import com.osscli.RootCommand;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * The command a verb actually belongs to, when a dispatcher was handed one of its siblings.
 *
 * <p>{@code oss memory sync --me} is the case this exists for. {@code sync} is a top-level command
 * and {@code --me} is its flag, so the whole line is three characters from correct — but the
 * built-in memory answered "no verb \"sync\"", listed its own eleven, and never mentioned the
 * command sitting one level up that does exactly what was asked. The reply was accurate and sent
 * the reader looking for a memory verb that is never going to exist.
 *
 * <p>Read from {@link RootCommand}'s annotation rather than a second list of command names. A
 * hand-kept copy is how the surface and the docs drift apart, and this one would drift silently:
 * nothing fails when a hint stops being offered.
 *
 * <p>Annotations only — no subcommand is instantiated. This runs on the error path of a command
 * that already failed, and a hint is not worth the cost or the risk of building the whole tree.
 */
public final class NearMiss {

    private NearMiss() {}

    /**
     * The command line to suggest, already carrying the arguments that were typed, or {@code null}
     * when the verb names nothing at the top level.
     *
     * @param dispatcher the command that could not handle it, so {@code oss run run} does not
     *     suggest itself
     * @param verb the word that matched no verb
     * @param args whatever followed it — reprinted so the suggestion is a line that can be run,
     *     not a command the reader has to re-assemble
     */
    public static String elsewhere(String dispatcher, String verb, List<String> args) {
        if (verb == null || verb.isBlank()) {
            return null;
        }
        String wanted = verb.strip();
        if (wanted.equalsIgnoreCase(dispatcher)) {
            return null;
        }
        String found = topLevelNameOf(wanted);
        if (found == null) {
            return null;
        }
        StringBuilder line = new StringBuilder("oss ").append(found);
        if (args != null) {
            for (String arg : args) {
                line.append(' ').append(arg);
            }
        }
        return line.toString();
    }

    /** The canonical name of the top-level command known by this name or alias. Null when none. */
    private static String topLevelNameOf(String wanted) {
        Command root = RootCommand.class.getAnnotation(Command.class);
        if (root == null) {
            return null;
        }
        for (Class<?> subcommand : root.subcommands()) {
            Command spec = subcommand.getAnnotation(Command.class);
            if (spec == null) {
                continue;
            }
            if (spec.name().equalsIgnoreCase(wanted)) {
                return spec.name();
            }
            for (String alias : spec.aliases()) {
                if (alias.equalsIgnoreCase(wanted)) {
                    // The canonical name, not the alias that was typed. `oss kb sync` should be
                    // answered with `oss sync`, and a suggestion is the wrong place to teach a
                    // second spelling of the same command.
                    return spec.name();
                }
            }
        }
        return null;
    }
}
