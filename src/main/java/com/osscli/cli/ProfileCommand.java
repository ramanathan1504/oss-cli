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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.memory.VoiceProfile;
import com.osscli.model.RepoProfile;
import com.osscli.profile.RepoProfileBuilder;
import com.osscli.storage.SqliteStorage;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Builds and shows a repository's technical profile: language, build, toolchain and the conventions a change must
 * respect.
 *
 * <p>Serves both directions the same data. A maintainer reads it as the rules a pull request will be judged against; a
 * new contributor reads it as what to target before writing anything.
 */
@Command(
        name = "profile",
        mixinStandardHelpOptions = true,
        description = "Build or show a repository's language, build and convention profile")
public class ProfileCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(ProfileCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository in 'owner/name' format")
    private String repository;

    @Option(
            names = {"--rebuild"},
            description = "Re-read the repository even if a profile is already stored")
    private boolean rebuild;

    @Option(
            names = {"--me"},
            description = "Profile how YOU write, from text you authored, instead of a repository")
    private boolean me;

    @Override
    public Integer call() throws Exception {
        if (me) {
            return profileMe();
        }
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.isBlank()) {
                LOGGER.error("No repository specified. Use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        RepoProfile profile = rebuild ? null : SqliteStorage.loadRepoProfile(repository);

        if (profile == null) {
            LOGGER.info("Building profile for {}...", repository);
            try {
                profile = RepoProfileBuilder.build(repository);
            } catch (IllegalArgumentException e) {
                LOGGER.error("{}", e.getMessage());
                return 1;
            }
            SqliteStorage.saveRepoProfile(profile);
        } else {
            LOGGER.info("Stored profile for {} (pass --rebuild to re-read).", repository);
        }

        print(profile);
        return 0;
    }

    private void print(RepoProfile p) throws Exception {
        LOGGER.info("");
        LOGGER.info("╔══════════════════════════════════════════════════════════╗");
        LOGGER.info("║  PROFILE  |  {}", p.repository());
        LOGGER.info("╚══════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        LOGGER.info("  Language      {}", orUnknown(p.primaryLanguage()));
        LOGGER.info("  Build         {}", orUnknown(p.buildSystem()));
        LOGGER.info("  Toolchain     {}", p.targetVersion() == null ? "not declared" : p.targetVersion());
        if (p.minVersion() != null) {
            LOGGER.info("  Minimum       {}", p.minVersion());
        }

        JsonNode docs = MAPPER.readTree(p.docsJson() == null ? "{}" : p.docsJson());
        LOGGER.info("");
        LOGGER.info("── Documents ({}) ──", docs.size());
        for (Iterator<String> it = docs.fieldNames(); it.hasNext(); ) {
            LOGGER.info("  {}", it.next());
        }

        Map<String, String> conventions = MAPPER.readValue(
                p.conventionsJson() == null ? "{}" : p.conventionsJson(),
                MAPPER.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, String.class));

        LOGGER.info("");
        LOGGER.info("── Conventions ({}) ──", conventions.size());
        if (conventions.isEmpty()) {
            LOGGER.info("  none detected");
        }
        conventions.forEach((k, v) -> LOGGER.info("  {} — {}", k, v));
    }

    private String orUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    /**
     * How the user writes, measured and filed as markdown.
     *
     * <p>Written to the memory directory rather than only printed, because everything else this
     * tool knows lives there as a file that can be read, corrected and indexed. A voice profile the
     * user cannot open and disagree with is one they have to take on trust.
     */
    private Integer profileMe() throws Exception {
        VoiceProfile profile = VoiceProfile.ofThisMachine();
        java.nio.file.Path out = com.osscli.AppPaths.BASE_DIR.resolve("memory").resolve("voice.md");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, profile.markdown());

        System.out.println(profile.markdown());
        System.out.println("  filed at " + out);
        if (!profile.confident()) {
            // Loud, and specific about the remedy. The corpus being full of prose is exactly what
            // makes this failure quiet: 1,874 notes on disk and almost none of them the user's.
            System.out.println();
            System.out.println("  Only text you authored counts — harvested threads and generated");
            System.out.println("  notes are somebody else's voice, including this tool's own.");
            System.out.println("  Nothing is added to a prompt until there are " + VoiceProfile.ENOUGH + ".");
        }
        return 0;
    }
}
