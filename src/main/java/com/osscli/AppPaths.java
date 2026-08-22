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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Canonical on-disk locations for the application.
 *
 * <p>The project has been renamed twice: {@code issue-ai}, then {@code self-analyse}, now
 * {@code oss-cli}. Each rename moved the data directory, and the first was not handled -- the build
 * read a path that did not exist while the real database sat untouched under the old name, which is
 * indistinguishable from total data loss. Every previous location therefore stays declared here and
 * {@code DatabaseManager} relocates once on first run, rather than asking anyone to move files by hand.
 *
 * <p>Every path below moves as a set when {@value #HOME_ENV_VAR} is exported. That exists so a
 * development build cannot touch the data an installed release depends on: both are built from the
 * same pom by the same command, so nothing else tells them apart at runtime, and the schema
 * migrations in {@code DatabaseManager} are one-way. Run development builds as:
 *
 * <pre>{@code
 * OSS_CLI_HOME=~/.oss-cli-dev java -jar target/oss-cli-<version>.jar doctor
 * }</pre>
 */
public class AppPaths {
    // Dynamically resolves to /Users/<you> (or equivalent on Linux/Windows)
    public static final String HOME_DIR = System.getProperty("user.home");

    /** Environment variable that relocates {@link #BASE_DIR}, and with it every path below. */
    public static final String HOME_ENV_VAR = "OSS_CLI_HOME";

    /**
     * System property carrying {@link #BASE_DIR} to {@code log4j2.xml}.
     *
     * <p>The log file location is configured in XML, not in Java, so it cannot read {@link #BASE_DIR}
     * directly. Without this bridge a relocated run still writes its logs into the real data
     * directory, which defeats the point of relocating.
     */
    public static final String HOME_SYSTEM_PROPERTY = "oss.cli.home";

    /** Where the data lives when nothing is overridden: {@code ~/.oss-cli} */
    private static final Path DEFAULT_BASE_DIR = Paths.get(HOME_DIR, ".oss-cli");

    /** The master global hidden directory: {@code ~/.oss-cli}, or {@value #HOME_ENV_VAR} when set. */
    public static final Path BASE_DIR = resolveBaseDir();

    /**
     * True when {@value #HOME_ENV_VAR} pointed us somewhere other than the real data directory.
     *
     * <p>Setting the variable to the default location on purpose is not a relocation, so an operator
     * who exports it explicitly still gets the normal legacy carry-over.
     */
    public static final boolean IS_RELOCATED = !BASE_DIR.equals(DEFAULT_BASE_DIR);

    /**
     * Every location this data has lived in, newest first.
     *
     * <p>The project has been renamed twice: issue-ai, then self-analyse, now oss-cli. Each rename
     * moved the data directory, and the first one was not handled -- the build started reading a
     * path that did not exist while 176 MB sat untouched under the old name, which is
     * indistinguishable from total data loss. Keeping the full chain means someone upgrading from
     * ANY previous version is carried across, not just the most recent one.
     */
    public static final Path[] LEGACY_BASE_DIRS = {
        Paths.get(HOME_DIR, ".self-analyse"), Paths.get(HOME_DIR, ".issue-ai"),
    };

    public static final Path DATA_DIR = BASE_DIR.resolve("data");
    public static final Path REPORTS_DIR = BASE_DIR.resolve("reports");
    public static final Path BACKUPS_DIR = BASE_DIR.resolve("backups");

    public static final String DB_FILE_NAME = "issue_intelligence.db";

    public static final Path DB_PATH = DATA_DIR.resolve(DB_FILE_NAME);

    /** Absolute JDBC URL targeting {@code <base>/data/issue_intelligence.db} */
    public static final String DB_URL = "jdbc:sqlite:" + DB_PATH.toAbsolutePath();

    /**
     * Publishes {@link #BASE_DIR} as the {@value #HOME_SYSTEM_PROPERTY} system property.
     *
     * <p>Must run before the first {@code LogManager} call, because Log4j reads its configuration
     * once, on first use. {@code Main} therefore calls this ahead of everything else.
     */
    public static void bootstrap() {
        System.setProperty(HOME_SYSTEM_PROPERTY, BASE_DIR.toString());
    }

    /**
     * First legacy database that actually exists, or null when there is nothing to carry over.
     *
     * <p>Nothing is ever carried into a relocated base. Dragging the real database across is exactly
     * what the relocation exists to prevent, and it would leave a throwaway sandbox holding a full
     * copy of production data.
     */
    public static Path findLegacyDb() {
        if (IS_RELOCATED) {
            return null;
        }
        for (Path base : LEGACY_BASE_DIRS) {
            Path candidate = base.resolve("data").resolve(DB_FILE_NAME);
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether the store in use is the real one, or a directory somebody pointed us at.
     *
     * <p>Read from the environment variable rather than by comparing paths, because
     * {@code OSS_CLI_HOME=$HOME/.oss-cli} is somebody naming the real store deliberately, and a
     * path comparison would call that redirected. The question being asked is "was this chosen",
     * and the variable is the choice.
     */
    public static boolean isDefaultBaseDir() {
        String override = System.getenv(HOME_ENV_VAR);
        return override == null || override.isBlank();
    }

    private static Path resolveBaseDir() {
        String override = System.getenv(HOME_ENV_VAR);
        if (override == null || override.isBlank()) {
            return DEFAULT_BASE_DIR;
        }
        String trimmed = override.trim();
        // A quoted value skips shell expansion, and Java would then create a directory literally
        // named "~" -- silently, beside the real one, which is a confusing way to lose an evening.
        if (trimmed.equals("~")) {
            return Paths.get(HOME_DIR);
        }
        if (trimmed.startsWith("~/")) {
            trimmed = HOME_DIR + trimmed.substring(1);
        }
        return Paths.get(trimmed).toAbsolutePath().normalize();
    }
}
