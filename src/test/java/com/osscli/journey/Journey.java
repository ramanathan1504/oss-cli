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
package com.osscli.journey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run the real command-line, end to end, against a store that is not yours.
 *
 * <p>Everything else in this suite calls a class. That is how the product reached a state where
 * {@code oss run init} passed its test, {@code oss run list} passed its test, {@code PackFile}
 * passed its tests, and the three of them in a row did not work -- the declarative pack format had
 * no field for a main class, so the documented path from an empty directory to a running
 * application had no end. Nothing failed. The step between the steps had no owner.
 *
 * <p>So a journey test types what a person types, in order, and asserts on what they see.
 *
 * <p><b>It runs the compiled classes, never the packaged jar.</b> The first journey test written
 * here shelled out to {@code target/oss-cli-*.jar}, which only exists after {@code package} -- so
 * during {@code mvn verify} it found no jar, assumed itself away, and reported as SKIPPED. A test
 * written because an untested step was broken, itself not running. The test JVM's own classpath
 * already holds {@code target/classes} and every dependency, so it is handed straight to the child.
 *
 * <p><b>OSS_CLI_HOME is the only safe way to redirect the store</b>, and it is an environment
 * variable read by {@code AppPaths.resolveBaseDir()}. The system property {@code oss.cli.home} is
 * written BY the application so log4j2.xml can read the path back; setting it from outside
 * redirects nothing. A test once "redirected" itself with the property and deleted a real 496 MB
 * database, which is why this asserts where it is pointing before it runs anything.
 */
public final class Journey {

    private Journey() {}

    /** What one command printed, and what it exited with. */
    public record Ran(int code, String out, String err) {
        public String all() {
            return out + err;
        }
    }

    /**
     * Type one command.
     *
     * @param home the store to use. Must not be the real one: this is checked, because "assert
     *     where it is pointing and refuse" is the rule that exists here after a real store was
     *     destroyed by a test that trusted its configuration
     * @param cwd the directory the user is standing in
     */
    public static Ran oss(Path home, Path cwd, String... argv) throws Exception {
        return run(home, cwd, null, argv);
    }

    /**
     * The same, with a GitHub token present.
     *
     * <p>Whether a token exists decides WHICH refusal an unreachable network produces, and both are
     * correct: with no token the tool stops at "GitHub Token is missing", and with one it gets as
     * far as "no network". A test that asserted only the second passed here and failed on every CI
     * runner, because this machine had a token in its environment and the runners did not -- the
     * assertion had encoded one machine's state rather than the contract.
     */
    public static Ran ossWithToken(Path home, Path cwd, String... argv) throws Exception {
        return run(home, cwd, "ghp_notarealtokenusedonlyintests", argv);
    }

    /** The same, against a GitHub that answers -- see {@link FakeGitHub}. */
    public static Ran ossAgainst(FakeGitHub github, Path home, Path cwd, String... argv) throws Exception {
        return run(home, cwd, "ghp_notarealtokenusedonlyintests", github.url(), argv);
    }

    private static Ran run(Path home, Path cwd, String token, String... argv) throws Exception {
        return run(home, cwd, token, "http://127.0.0.1:1", argv);
    }

    private static Ran run(Path home, Path cwd, String token, String api, String... argv) throws Exception {
        String where = home.toAbsolutePath().toString();
        if (where.startsWith(System.getProperty("user.home") + "/.oss-cli")) {
            throw new IllegalStateException("refusing to run a journey against the real store: " + where);
        }

        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.osscli.Main"));
        cmd.addAll(List.of(argv));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.environment().put("OSS_CLI_HOME", where);
        // Nothing in a journey may reach the network by accident. A test that quietly starts
        // working because the developer happened to be online is worse than one that fails.
        pb.environment().put("GITHUB_API_URL", api);
        pb.environment().remove("GITHUB_TOKEN");
        pb.environment().remove("GH_TOKEN");
        if (token != null) {
            pb.environment().put("GITHUB_TOKEN", token);
        }

        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Ran(p.waitFor(), out, err);
    }
}
