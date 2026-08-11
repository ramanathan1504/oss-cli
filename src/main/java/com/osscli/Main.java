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
package com.osscli;

import com.osscli.storage.DatabaseManager;
import picocli.CommandLine;

public class Main {

    public static void main(String[] args) {
        // First, and before anything can touch a logger: log4j2.xml resolves its file appender
        // through the system property this publishes, and Log4j reads its configuration once.
        AppPaths.bootstrap();

        DatabaseManager.initializeSchema();

        CommandLine commandLine = new CommandLine(new RootCommand());

        // `run` and `memory` are dispatchers, not parsers: everything after the verb belongs to the
        // extension. Left as ordinary subcommands, picocli claims flags out of the passthrough --
        // `oss-cli bench list --apps` printed picocli's own usage because --apps was unknown HERE
        // and so never reached the bench. Scoped to these two, because every other subcommand does
        // want its arguments parsed.
        for (String dispatcher : java.util.List.of("run", "memory")) {
            CommandLine sub = commandLine.getSubcommands().get(dispatcher);
            if (sub != null) {
                sub.setStopAtPositional(true).setUnmatchedOptionsArePositionalParams(true);
            }
        }

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
