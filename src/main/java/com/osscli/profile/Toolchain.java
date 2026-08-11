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
package com.osscli.profile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the language version a project builds against out of whatever file happens to declare it.
 *
 * <p>There is no single answer to "which Java does this use". Maven states it as a property, Gradle in any of five
 * spellings across the root build file or {@code buildSrc}, and a good many projects only really pin it in CI. A
 * profile that reads one of those and stops reports "not declared" for a project that declares it plainly somewhere
 * else -- measured on OpenSearch, which pins Java 21 in {@code buildSrc/build.gradle} and again in
 * {@code .ci/java-versions.properties}, and was profiled as having no toolchain at all.
 *
 * <p>Each finding carries where it came from, because the places disagree in practice: a project can compile to an
 * older bytecode level than the JDK its CI runs, and a contributor needs to know which number is which.
 */
public final class Toolchain {

    /** A declared version and the file that declared it. */
    public record Finding(String tool, String version, String source) {
        public String describe() {
            return tool + " " + version;
        }
    }

    // Gradle, in the spellings that actually occur.
    private static final Pattern[] GRADLE_JAVA = {
        // java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
        Pattern.compile("JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)"),
        // kotlin { jvmToolchain(17) }
        Pattern.compile("jvmToolchain\\s*\\(\\s*(\\d+)\\s*\\)"),
        // sourceCompatibility = JavaVersion.VERSION_21
        Pattern.compile("(?:source|target)Compatibility\\s*=\\s*JavaVersion\\.VERSION_(\\d+)"),
        // sourceCompatibility = 17 | '17' | "17" | JavaVersion.toVersion(17)
        Pattern.compile("(?:source|target)Compatibility\\s*=\\s*[\"']?(?:1\\.)?(\\d+)[\"']?"),
        // options.release = 17
        Pattern.compile("options\\.release(?:\\.set)?\\s*[=(]\\s*(\\d+)"),
    };

    // Maven, where the property name varies by generation.
    private static final Pattern[] MAVEN_JAVA = {
        Pattern.compile("<maven\\.compiler\\.release>\\s*(\\d+)\\s*</maven\\.compiler\\.release>"),
        Pattern.compile("<maven\\.compiler\\.target>\\s*(?:1\\.)?(\\d+)\\s*</maven\\.compiler\\.target>"),
        Pattern.compile("<java\\.version>\\s*(?:1\\.)?(\\d+)\\s*</java\\.version>"),
        Pattern.compile("<release>\\s*(\\d+)\\s*</release>"),
    };

    /**
     * Toolchain declarations in a GitHub Actions workflow.
     *
     * <p>Worth reading because CI is the one place every ecosystem states its version the same way, and because it is
     * the version the project is actually verified against regardless of what the build files claim.
     */
    private static final Map<String, Pattern> CI_SETUP = new LinkedHashMap<>();

    static {
        CI_SETUP.put("java", Pattern.compile("java-version\\s*:\\s*[\"']?(\\d+)"));
        CI_SETUP.put("node", Pattern.compile("node-version\\s*:\\s*[\"']?v?(\\d+)"));
        CI_SETUP.put("python", Pattern.compile("python-version\\s*:\\s*[\"']?(\\d+\\.\\d+)"));
        CI_SETUP.put("go", Pattern.compile("go-version\\s*:\\s*[\"']?v?(\\d+\\.\\d+)"));
        CI_SETUP.put("ruby", Pattern.compile("ruby-version\\s*:\\s*[\"']?(\\d+\\.\\d+)"));
    }

    /**
     * Key/value properties naming a build JDK, e.g. {@code OPENSEARCH_BUILD_JAVA=openjdk21}.
     *
     * <p>Deliberately case-SENSITIVE and restricted to environment-style keys. Matching any key containing "java"
     * reads dependency coordinates as toolchains: Elasticsearch's {@code version.properties} carries
     * {@code cuvs_java = 26.02.0}, a library version, which was reported as "java 26" -- close enough to the JDK that
     * project actually bundles that the mistake looked like a correct answer. Uppercase separates the two reliably,
     * because a CI toolchain variable is uppercase by convention and a dependency coordinate is not.
     *
     * <p>The value must also be a bare major version, so a dotted library version cannot satisfy it.
     */
    private static final Pattern PROPERTIES_JAVA = Pattern.compile("(?m)^(?:[A-Z][A-Z0-9_]*_)?"
            + "(?:BUILD_JAVA|RUNTIME_JAVA|JAVA_VERSION|JDK_VERSION|JAVA)"
            + "\\s*=\\s*(?:openjdk|java|jdk|temurin)?[-_]?(\\d+)\\s*$");

    private Toolchain() {}

    /** Highest Java version declared anywhere in a Gradle or Maven build file, or null. */
    public static Finding fromBuildFile(String path, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        boolean maven = path.toLowerCase(Locale.ROOT).endsWith("pom.xml");
        Integer best = highest(body, maven ? MAVEN_JAVA : GRADLE_JAVA);
        return best == null ? null : new Finding("java", String.valueOf(best), path);
    }

    /** Java version named by a properties file that pins a build JDK, or null. */
    public static Finding fromProperties(String path, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher m = PROPERTIES_JAVA.matcher(body);
        return m.find() ? new Finding("java", m.group(1), path) : null;
    }

    /** Toolchain a CI workflow sets up, or null. */
    public static Finding fromWorkflow(String path, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Pattern> e : CI_SETUP.entrySet()) {
            Matcher m = e.getValue().matcher(body);
            if (m.find()) {
                return new Finding(e.getKey(), m.group(1), path);
            }
        }
        return null;
    }

    /**
     * The highest version any pattern matches.
     *
     * <p>Build files commonly mention several: a minimum, a target, and the JDK required to run the build itself.
     * Taking the highest reports the level the project has actually moved to, which is the number a contributor needs
     * -- reporting a lower one invites code that will not compile.
     */
    private static Integer highest(String body, Pattern[] patterns) {
        Integer best = null;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    // Sanity bounds: a stray number matched out of context is worse than no answer.
                    if (v >= 5 && v <= 40 && (best == null || v > best)) {
                        best = v;
                    }
                } catch (NumberFormatException ignored) {
                    // Not a version; the next pattern may still match.
                }
            }
        }
        return best;
    }
}
