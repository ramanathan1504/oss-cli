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

import com.osscli.Main;
import com.osscli.llm.Ai;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The engine you are willing to let answer, typed in front of the command.
 *
 * <pre>{@code
 * oss review 4249              built-in only -- nothing leaves this machine
 * oss llm review 4249          local Ollama may answer
 * oss claude review 4249       Claude may answer
 * oss llm claude review 4249   either may, in that order
 * }</pre>
 *
 * <p>A prefix grants <b>permission, not an instruction</b>. Every ask starts on the local rung --
 * your own notes, the vector index, the built-in model -- and an external engine is reached only
 * when that rung fails a test the command states out loud, with the reason printed. A question the
 * archive already answers is not worth a network round trip, and spending one anyway teaches you to
 * distrust the tool's judgement about when it genuinely needs help.
 *
 * <p>Each prefix consumes itself and re-dispatches what follows, so they stack in the order typed
 * and every command below sees an ordinary argument list. Nothing after the prefix is parsed here:
 * {@code oss claude review 4249 --refresh} hands {@code --refresh} to {@code review}.
 *
 * <p>A command that never generates refuses the prefix instead of ignoring it. {@code oss claude
 * doctor} looks like it asked a model something, and quietly running the same deterministic report
 * would leave that impression standing.
 */
public abstract class EngineCommand implements Callable<Integer> {

    @Parameters(index = "0..*", description = "The command to run, and its arguments")
    List<String> rest = List.of();

    abstract Ai.Engine engine();

    /**
     * Whether {@code --cli} was typed.
     *
     * <p>Declared on the engines that have a command-line tool rather than here, so {@code oss llm
     * --help} does not list a flag whose only possible outcome is an error. A prefix advertising an
     * option it always refuses is the same lie as a tick against a layer that did not run.
     */
    boolean cliRequested() {
        return false;
    }

    @Override
    public Integer call() {
        Ai.add(engine());

        if (cliRequested()) {
            com.osscli.llm.CliClient.Spec spec = com.osscli.llm.CliClient.specFor(engine());
            if (spec == null) {
                // Ollama is already a local daemon and the built-in model runs in this process.
                // Neither has a command-line tool to put in front of it, and quietly ignoring the
                // flag would leave somebody believing they had changed where their code went.
                System.err.println(
                        "error  " + engine().typed() + " has no command-line tool — --cli means nothing here.");
                return 2;
            }
            Ai.useCli(true);
        }

        if (rest.isEmpty()) {
            return explain();
        }

        String head = rest.get(0);
        // Another prefix stacks; only a real command is gated, and it is gated once, here, rather
        // than in each of the thirty-odd commands that would otherwise have to remember to.
        if (Ai.byPrefix(head).isEmpty() && Ai.use(head) == Ai.Use.NEVER) {
            System.err.println("error  \"" + head + "\" never asks a model anything, so " + engine().typed()
                    + " would change nothing about it.");
            System.err.println("       Run it plain:  oss " + String.join(" ", rest));
            return 2;
        }

        return Main.commandLine().execute(rest.toArray(new String[0]));
    }

    /**
     * What this prefix means, when it is typed with nothing after it.
     *
     * <p>The credential is reported here rather than refused: permission is not a call, and a
     * missing key only matters if the local rung falls short later. Saying it now costs a line and
     * saves discovering it at the end of a long command.
     */
    private Integer explain() {
        Ai.Engine e = engine();
        System.out.println("  " + e.typed() + " <command>   lets " + e.label() + " answer when the local rung cannot");
        System.out.println();
        if (e.needsKey() && !e.hasCredential()) {
            System.out.println("  no key configured for " + e.label() + " — oss setup");
            System.out.println();
        }
        System.out.println("  commands that can use it:");
        for (var entry : Ai.USE.entrySet()) {
            if (entry.getValue() != Ai.Use.NEVER) {
                System.out.printf(
                        "    oss %s %-9s %s%n",
                        e.typed().substring(4),
                        entry.getKey(),
                        entry.getValue() == Ai.Use.ALWAYS ? "(needs an engine)" : "(adds a judgement)");
            }
        }
        return 0;
    }

    @Command(name = "llm", description = "Let the local Ollama daemon answer when the local rung cannot")
    public static class Llm extends EngineCommand {
        @Override
        Ai.Engine engine() {
            return Ai.Engine.OLLAMA;
        }
    }

    @Command(name = "claude", description = "Let Anthropic Claude answer when the local rung cannot")
    public static class Claude extends EngineCommand {
        @Option(
                names = "--cli",
                description = "Answer through this provider's own command-line tool, on your subscription")
        boolean cli;

        @Override
        boolean cliRequested() {
            return cli;
        }

        @Override
        Ai.Engine engine() {
            return Ai.Engine.CLAUDE;
        }
    }

    @Command(name = "gemini", description = "Let Google Gemini answer when the local rung cannot")
    public static class Gemini extends EngineCommand {
        @Option(
                names = "--cli",
                description = "Answer through this provider's own command-line tool, on your subscription")
        boolean cli;

        @Override
        boolean cliRequested() {
            return cli;
        }

        @Override
        Ai.Engine engine() {
            return Ai.Engine.GEMINI;
        }
    }

    // `codex` rather than `openai`: it is what the provider's own command-line tool is called, and
    // the three prefixes then read as the three assistants rather than as two brands and a company.
    @Command(name = "codex", description = "Let OpenAI answer when the local rung cannot")
    public static class Codex extends EngineCommand {
        @Option(
                names = "--cli",
                description = "Answer through this provider's own command-line tool, on your subscription")
        boolean cli;

        @Override
        boolean cliRequested() {
            return cli;
        }

        @Override
        Ai.Engine engine() {
            return Ai.Engine.OPENAI;
        }
    }
}
