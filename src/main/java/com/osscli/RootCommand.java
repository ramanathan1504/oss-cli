package com.osscli;

import com.osscli.cli.*;
import picocli.CommandLine.Command;

@Command(
        name = "oss-cli",
        aliases = {"sa"},
        mixinStandardHelpOptions = true,
        version = "1.1",
        subcommands = {
            SyncCommand.class,
            CriticalCommand.class,
            AnalyzeCommand.class,
            HiddenCriticalCommand.class,
            DuplicatesCommand.class,
            SearchCommand.class,
            PrsCommand.class,
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
            DoctorCommand.class
        })
public class RootCommand {}
