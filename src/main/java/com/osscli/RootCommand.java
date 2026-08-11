package com.osscli;

import com.osscli.cli.*;
import picocli.CommandLine.Command;

@Command(
        name = "oss-cli",
        aliases = {"sa"},
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
            // than one so `oss-cli bench …` and `oss-cli kb …` read as first-class capabilities.
            ExtCommand.class,
            com.osscli.serve.ServeCommand.class,
            ExtCommand.BenchDispatch.class,
            ExtCommand.KbDispatch.class
        })
public class RootCommand {}
