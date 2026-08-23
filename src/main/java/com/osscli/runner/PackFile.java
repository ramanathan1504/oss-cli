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
package com.osscli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack as <b>data</b>: a file the tool reads, not a program it runs.
 *
 * <p>A pack used to be {@code pack.sh}, a bash file the engine sourced. That works when the pack is
 * yours, and it is the wrong contract for a pack somebody sends you: sourcing it runs it, so
 * "point oss at this pack" and "run this person's shell script" were the same sentence. It also
 * meant a pack could only be written by someone comfortable with bash arrays.
 *
 * <p>So a pack is now a file that describes itself:
 *
 * <pre>{@code
 * {
 *   "name": "yourproject",
 *   "description": "Your project across a version x config x app matrix, on real JVMs",
 *   "useWhen": { "repository": "owner/name", "files": ["core/pom.xml"] },
 *   "versions": ["2.24.1", "2.25.5", "2.26.1"],
 *   "defaultVersion": "2.26.1",
 *   "apps": ["core-java", "db"],
 *   "appsDir": "apps",
 *   "configsDir": "configs",
 *   "modulePath": "apps/{app}",
 *   "modulePathFor": { "nosql": "apps/db" },
 *   "mainClass": "demo.{app}.Main",
 *   "mainClassFor": { "nosql": "demo.db.Main" }
 * }
 * }</pre>
 *
 * <p>Either {@code pack.json}, or {@code pack.md} with that object in a fenced {@code json} block —
 * the second so a pack can explain itself to a human on the same page it describes itself to the
 * tool, which is what stops the two drifting apart.
 *
 * <p><b>{@code useWhen} is the part a directory could never carry.</b> A pack says when it applies:
 * the repository it is for, or files whose presence identifies the project. That is what lets the
 * tool find the right pack instead of being told, and it is why this is data — a claim about
 * applicability can be read and compared, where a script can only be executed and observed.
 *
 * <p>{@code modulePath} replaces the one thing in the old format that was not data: a bash function
 * from app name to directory. Almost every pack's function was one line returning a path with the
 * name in it, so it becomes a template — and {@code modulePathFor} names the exceptions, because
 * every real pack has one. A pack running nineteen applications out of eighteen directories, where
 * one app is exercised through another's module, cannot be written as a template alone: it would
 * point that app at a directory which does not exist.
 */
public final class PackFile {

    private static final Pattern JSON_FENCE = Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    /** The token a {@code modulePath} template puts the app name into. */
    private static final String APP_TOKEN = "{app}";

    private final Path root;
    private final JsonNode json;

    private PackFile(Path root, JsonNode json) {
        this.root = root;
        this.json = json;
    }

    /**
     * The declarative pack in a directory, if there is one.
     *
     * <p>{@code pack.json} before {@code pack.md}, and neither before {@code pack.sh} — the caller
     * decides that, because a directory holding both is a pack mid-migration and the script is
     * still the one that has been tested.
     */
    public static Optional<PackFile> find(Path dir) throws IOException {
        Path asJson = dir.resolve("pack.json");
        if (Files.isRegularFile(asJson)) {
            return Optional.of(parse(dir, Files.readString(asJson, StandardCharsets.UTF_8), "pack.json"));
        }
        Path asMarkdown = dir.resolve("pack.md");
        if (Files.isRegularFile(asMarkdown)) {
            String text = Files.readString(asMarkdown, StandardCharsets.UTF_8);
            Matcher fence = JSON_FENCE.matcher(text);
            if (!fence.find()) {
                throw new IOException("pack.md has no ```json block, so there is nothing to read it by");
            }
            return Optional.of(parse(dir, fence.group(1), "pack.md"));
        }
        return Optional.empty();
    }

    private static PackFile parse(Path root, String text, String where) throws IOException {
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(text);
        } catch (IOException e) {
            throw new IOException(where + " is not valid JSON: " + e.getMessage(), e);
        }
        for (String required : List.of("name", "apps")) {
            if (node.path(required).isMissingNode()) {
                throw new IOException(where + " has no \"" + required + "\", and a pack without one cannot be run");
            }
        }
        return new PackFile(root, node);
    }

    public String name() {
        return json.path("name").asText();
    }

    public String description() {
        return json.path("description").asText("");
    }

    public Path root() {
        return root;
    }

    /** The applications this pack declares, which is the half only its author could write. */
    public List<String> apps() {
        return texts(json.path("apps"));
    }

    /**
     * Whether this pack is the one for a given repository and working directory.
     *
     * <p>A pack with no {@code useWhen} answers false rather than true. Claiming everything is how
     * one pack in a folder of them becomes the answer to every question, and a pack that does not
     * say what it is for is not evidence that it is for this.
     */
    public boolean appliesTo(String repository, Path workingDir) {
        JsonNode when = json.path("useWhen");
        if (when.isMissingNode() || when.isNull()) {
            return false;
        }
        JsonNode repo = when.path("repository");
        if (repository != null && !repo.isMissingNode()) {
            for (String candidate : texts(repo)) {
                if (candidate.equalsIgnoreCase(repository)) {
                    return true;
                }
            }
        }
        for (String file : texts(when.path("files"))) {
            if (Files.exists(workingDir.resolve(file))) {
                return true;
            }
        }
        return false;
    }

    /** One string or a list of them, since a pack may be for one repository or several. */
    private static List<String> texts(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isTextual()) {
            out.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /**
     * The same declaration, in the form the engine already reads.
     *
     * <p>The engine is POSIX shell and stays that way: it forks Maven, Gradle and JVMs, and
     * rewriting it to parse JSON would be a large change to the part that is working in order to
     * avoid a small one at the boundary. So the boundary does the work — this renders the pack as
     * the variables {@code engine.sh} has always sourced, and the engine gains one line that
     * prefers a generated file when it is handed one.
     *
     * <p>Nothing a pack says reaches the shell unquoted. Values are single-quoted with embedded
     * quotes escaped, so a pack file is data all the way through rather than data until somebody
     * puts a backtick in a version number.
     */
    public String toShell() {
        StringBuilder out = new StringBuilder();
        // The header interpolates nothing. It used to name the pack, which put an unquoted value
        // into the output for the one line that was not an assignment: a name containing a newline
        // ended the comment and made its second line shell code, and a name containing a quote
        // left the file with an unterminated string. Every value below is quoted, so the header was
        // the only way in -- found by handing the renderer two thousand hostile names.
        out.append("# Generated from a pack file by oss. Do not edit.\n");
        out.append("PACK_NAME=").append(quote(name())).append('\n');
        out.append("PACK_DESC=").append(quote(description())).append('\n');
        out.append("PACK_CONFIGS_DIR=")
                .append(quote(json.path("configsDir").asText("configs")))
                .append('\n');
        out.append("PACK_APPS_DIR=")
                .append(quote(json.path("appsDir").asText("apps")))
                .append('\n');
        array(out, "VERSIONS", texts(json.path("versions")));
        out.append("DEFAULT_VERSION=")
                .append(quote(json.path("defaultVersion").asText("")))
                .append('\n');
        array(out, "APPS", texts(json.path("apps")));
        array(out, "APPS_2X_ONLY", texts(json.path("appsNewestMajorCannotBuild")));

        // The one thing that was a function. A template covers most layouts; the exceptions are
        // named one by one, because they exist and a pack that has one had to keep pack.sh
        // entirely. A real pack runs nineteen applications out of eighteen directories -- "nosql"
        // is exercised through the "db" module and has no directory of its own -- which a single
        // template can express only by pointing it at a directory that is not there.
        String template = json.path("modulePath").asText(json.path("appsDir").asText("apps") + "/" + APP_TOKEN);
        out.append("pack_module_path() {\n  case \"$1\" in\n");
        JsonNode overrides = json.path("modulePathFor");
        java.util.Iterator<String> named = overrides.fieldNames();
        while (named.hasNext()) {
            String app = named.next();
            out.append("    ")
                    .append(quote(app))
                    .append(") printf '%s' ")
                    .append(quote(overrides.path(app).asText()))
                    .append(" ;;\n");
        }
        out.append("    *) printf '%s' ")
                .append(quote(template).replace(APP_TOKEN, "'\"$1\"'"))
                .append(" ;;\n  esac\n}\n");

        // How to START one. Without this a declarative pack could describe every application it
        // has and not one way to run it: `oss run init` wrote a pack, `oss run list` printed the
        // apps, and `oss run run <app>` handed `java` an empty class name and died with
        // "Could not find or load main class" and nothing after it. That is the whole point of a
        // runner, and it was unreachable for anyone who had not written pack.sh by hand.
        //
        // Same shape as modulePath, because that decision is already made and a second idiom for
        // "a template, plus the exceptions" would be one more thing to learn: `mainClass` with
        // {app} in it, `mainClassFor` naming the apps that differ.
        //
        // Absent entirely, this emits nothing and the engine's own default applies -- which
        // refuses by name rather than launching a JVM with no class.
        JsonNode perApp = json.path("mainClassFor");
        String mainTemplate = json.path("mainClass").asText("");
        if (!mainTemplate.isEmpty() || perApp.fieldNames().hasNext()) {
            out.append("pack_main_class_for() {\n  case \"$1\" in\n");
            java.util.Iterator<String> mains = perApp.fieldNames();
            while (mains.hasNext()) {
                String app = mains.next();
                out.append("    ")
                        .append(quote(app))
                        .append(") printf '%s' ")
                        .append(quote(perApp.path(app).asText()))
                        .append(" ;;\n");
            }
            if (mainTemplate.isEmpty()) {
                // Only exceptions were given. Anything else has no main class, and saying nothing
                // is what lets the engine explain that rather than the JVM.
                out.append("    *) : ;;\n");
            } else {
                out.append("    *) printf '%s' ")
                        .append(quote(mainTemplate).replace(APP_TOKEN, "'\"$1\"'"))
                        .append(" ;;\n");
            }
            out.append("  esac\n}\n");
        }
        return out.toString();
    }

    private static void array(StringBuilder out, String name, List<String> values) {
        out.append(name).append("=(");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : " ").append(quote(values.get(i)));
        }
        out.append(")\n");
    }

    /** Single quotes, with the only character that ends them escaped. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
