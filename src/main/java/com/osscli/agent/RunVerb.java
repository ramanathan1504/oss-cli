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

import com.osscli.runner.BuiltinRunner;
import java.util.List;

/**
 * Run the project's own build or tests — and only those.
 *
 * <p><b>Not a shell.</b> The obvious tool here is {@code command: <anything>}, and it is the one
 * thing that would turn a wrong answer into a destroyed machine: a model that has read a README
 * will happily suggest {@code rm -rf} because a README said so. What this offers instead is the
 * verb list {@link BuiltinRunner} already exposes — {@code detect}, {@code init}, {@code build},
 * {@code test}, {@code doctor} — which the core derives from the build file the directory already
 * declares.
 *
 * <p>That is not a compromise, it is the same rule the runner was built on: what can be derived
 * from what the directory declares belongs to the core, and what needs the maintainer's own
 * knowledge belongs to a pack. A model asking to run the tests is asking for something derivable. A
 * model asking to run an arbitrary command is asking for the maintainer's judgement, and does not
 * have it.
 */
public final class RunVerb implements Tool {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String usage() {
        return "run — verb: <" + String.join("|", BuiltinRunner.VERBS) + ">   run this project's own build or tests";
    }

    @Override
    public boolean writes() {
        // `build` and `test` write into the project's own output directory, which is what building
        // means, and `init` writes a starter pack. This is declared true so the loop asks: the
        // point of the flag is that the tool decides, and a tool that starts processes should not
        // be the one place that quietly says no.
        return true;
    }

    @Override
    public String run(Action action, Workspace workspace) {
        String verb = action.argument("verb").strip().toLowerCase(java.util.Locale.ROOT);
        if (verb.isEmpty()) {
            return "error: run needs a verb — one of " + String.join(", ", BuiltinRunner.VERBS);
        }
        if (!BuiltinRunner.VERBS.contains(verb)) {
            // Named, so a model that asked for `run: mvn clean install` learns the shape rather
            // than that it failed.
            return "error: \"" + verb + "\" is not one of " + String.join(", ", BuiltinRunner.VERBS)
                    + ". This runs the project's own build, not an arbitrary command.";
        }
        // The bare path, not an invented flag. `--where` is not something BuiltinRunner knows: it
        // takes the first non-flag argument that is a directory, so passing "--where" worked only
        // because the token was skipped for starting with a dash and the path behind it was found
        // anyway. Right by accident is a thing that stops being right when somebody tightens the
        // argument handling.
        int exit = BuiltinRunner.run(verb, List.of(workspace.root().toString()));
        return "run " + verb + " exited " + exit + (exit == 0 ? " (success)" : " (failure)");
    }
}
