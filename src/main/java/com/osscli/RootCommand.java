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

import com.osscli.cli.*;
import picocli.CommandLine.Command;

@Command(
        // The command is `oss`. It has to say so too: usage text and error messages
        // that name a command nobody typed send people looking for a binary that is
        // no longer installed.
        name = "oss",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
            SyncCommand.class,
            CriticalCommand.class,
            AnalyzeCommand.class,
            HiddenCriticalCommand.class,
            DuplicatesCommand.class,
            SearchCommand.class,
            PrsCommand.class,
            ReviewCommand.class,
            ProfileCommand.class,
            OnboardCommand.class,
            ReportCommand.class,
            TrendCommand.class,
            TriageCommand.class,
            SetupCommand.class,
            GuideCommand.class,
            ChatCommand.class,
            PromptCommand.class,
            InspectCommand.class,
            BackupCommand.class,
            RestoreCommand.class,
            DoctorCommand.class,
            // OSS-CLI knows; a bench runs and a kb remembers. Registered as three entries rather
            // than one so `oss run …` and `oss memory …` read as first-class capabilities.
            AliasCommand.class,
            FollowupCommand.class,
            HubCommand.class,
            IssueCommand.class,
            PrCommand.class,
            ExtCommand.class,
            com.osscli.serve.ServeCommand.class,
            ExtCommand.BenchDispatch.class,
            ExtCommand.KbDispatch.class
        })
public class RootCommand {}
