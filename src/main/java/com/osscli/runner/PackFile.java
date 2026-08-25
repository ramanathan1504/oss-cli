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
        // ── Everything below was a function somebody had to write in shell ──────────
        //
        // The engine has eighteen hooks a pack may define. A declarative pack could
        // express two of them, so anyone whose project needed a nineteenth JDK rule or a
        // config property spelled differently on one major version wrote pack.sh by hand
        // and kept it -- the JSON form was a way to describe a pack, not a way to have
        // one. Sixteen are data with a shape, and they are emitted here.
        //
        // Two are not, and are not pretended to be: pack_modules lists a project's
        // modules out of a checkout, and pack_modules_on_classpath filters a classpath on
        // stdin. Those are programs. `shell` carries them verbatim, so needing one costs
        // a field rather than the whole file.
        map(out, "pack_min_java_for", json.path("minJavaFor"), "0");
        map(out, "pack_min_version_for", json.path("minVersionFor"), null);
        map(out, "pack_requires_config_for", json.path("requiresConfigFor"), null);
        map(out, "pack_requires_app_for", json.path("requiresAppFor"), null);
        array(out, "PACK_INTERACTIVE_APPS", texts(json.path("interactiveApps")));

        // Positional names, so a rule can say {app} and not have to know it is $1 here
        // and $2 in the next function. Wrong by one is the kind of mistake that produces
        // a pack which runs and quietly skips the wrong cells.
        rules(out, "pack_skip_reason", json.path("skipWhen"), ARGS_CELL);
        flags(out, "pack_build_flags", json.path("buildFlags"), json.path("buildFlagsWhen"), ARGS_VERSION, false, null);
        flags(out, "pack_always_jvm_args", json.path("alwaysJvmArgs"), null, ARGS_NONE, false, null);
        perAppFlags(out, "pack_jvm_args", json.path("jvmArgsFor"));
        flags(out, "pack_config_args", null, json.path("configArgs"), ARGS_CONFIG, true, json.path("configArgsAlso"));
        scalar(out, "pack_gradle_version_flag", json.path("gradleVersionFlag"), ARGS_VERSION);
        scalar(out, "pack_upstream_repo", json.path("upstreamRepo"), ARGS_NONE);
        scalar(out, "pack_source_clone_hint", json.path("sourceCloneHint"), ARGS_NONE);
        map(out, "pack_source_clone", json.path("sourceClone"), null);

        String shell = json.path("shell").asText("");
        if (!shell.isEmpty()) {
            out.append("# From this pack's \"shell\": what is a program rather than data.\n");
            out.append(shell);
            if (!shell.endsWith("\n")) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * A hook that answers the first reason that applies, or nothing.
     *
     * <p>First match wins and each match returns, which is how the shell these replace was written
     * by hand and is the only order that makes a list of reasons readable: the specific ones go
     * above the general ones, and a rule with no conditions ends the list.
     *
     * <p>Returning 0 at the end matters. The engine captures this in a command substitution and
     * tests whether the output was empty, but the pack is sourced under {@code set -e} in places,
     * and a function whose last statement was a failed test would exit non-zero and take the run
     * with it -- for the cell that was fine.
     */
    private static void rules(StringBuilder out, String fn, JsonNode when, java.util.Map<String, String> args) {
        if (!when.isArray() || when.size() == 0) {
            return;
        }
        out.append(fn).append("() {\n");
        for (JsonNode rule : when) {
            String reason = rule.path("reason").asText("");
            if (reason.isEmpty()) {
                continue;
            }
            String test = conditions(rule, args);
            String say = "printf '%s\\n' " + splice(reason, args) + "; return 0\n";
            if (test == null) {
                out.append("  ").append(say);
            } else {
                out.append("  if ").append(test).append("; then ").append(say).append("  fi\n");
            }
        }
        out.append("  return 0\n}\n");
    }

    /** Which positional the tokens in a rule mean, per hook. */
    private static final java.util.Map<String, String> ARGS_CELL =
            java.util.Map.of("{app}", "$1", "{config}", "$2", "{java}", "$3", "{version}", "$4");

    private static final java.util.Map<String, String> ARGS_CONFIG =
            java.util.Map.of("{app}", "$1", "{config}", "$2", "{version}", "$3");

    private static final java.util.Map<String, String> ARGS_VERSION = java.util.Map.of("{version}", "$1");

    private static final java.util.Map<String, String> ARGS_NONE = java.util.Map.of();

    /**
     * A hook that answers one value per name, as a shell {@code case}.
     *
     * <p>The keys are glob patterns rather than exact names, because {@code case} matches that way
     * and the packs that need this need it: a rule about every config whose
     * name ends in one word is one line, and the same rule written out per config is a list that
     * goes stale.
     *
     * <p>A {@code "*"} key is the default and is emitted last whatever order it was written in --
     * {@code case} takes the first match, so a default written first would swallow everything after
     * it and the pack would answer one value for every name.
     */
    private static void map(StringBuilder out, String fn, JsonNode node, String fallback) {
        if (!node.fieldNames().hasNext()) {
            return;
        }
        out.append(fn).append("() {\n  case \"$1\" in\n");
        String star = null;
        java.util.Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = node.path(key).asText();
            if ("*".equals(key)) {
                star = value;
                continue;
            }
            out.append("    ")
                    .append(glob(key))
                    .append(") printf '%s' ")
                    .append(home(value))
                    .append(" ;;\n");
        }
        String last = star != null ? star : fallback;
        out.append("    *) ")
                .append(last == null ? ":" : "printf '%s' " + home(last))
                .append(" ;;\n");
        out.append("  esac\n}\n");
    }

    /**
     * A hook that answers a list of words, one per line.
     *
     * <p>One per line rather than as an array because bash 3.2 is what macOS ships and it has no
     * {@code mapfile}; the engine reads every one of these with {@code done < <(...)}.
     *
     * <p>{@code always} is emitted unconditionally and {@code when} is first-match-wins, so a rule
     * with no conditions is the fallback and anything after it is unreachable -- which is exactly
     * how the shell these replace was written by hand.
     */
    private static void flags(
            StringBuilder out,
            String fn,
            JsonNode always,
            JsonNode when,
            java.util.Map<String, String> args,
            boolean firstMatchWins,
            JsonNode also) {
        List<String> unconditional = always == null ? List.of() : texts(always);
        boolean conditional = when != null && when.isArray() && when.size() > 0;
        boolean additional = also != null && also.isArray() && also.size() > 0;
        if (unconditional.isEmpty() && !conditional && !additional) {
            return;
        }
        out.append(fn).append("() {\n");
        for (String flag : unconditional) {
            out.append("  printf '%s\\n' ").append(splice(flag, args)).append('\n');
        }
        if (conditional) {
            boolean chained = false;
            for (JsonNode rule : when) {
                String test = conditions(rule, args);
                String emit = "";
                for (String flag : texts(rule.path("args").isMissingNode() ? rule.path("flags") : rule.path("args"))) {
                    emit += "    printf '%s\\n' " + splice(flag, args) + "\n";
                }
                if (emit.isEmpty()) {
                    continue;
                }
                // Exclusive or additive, because both were written by hand and they are not the
                // same question. How a version reaches Maven is additive -- the 3.x flags go on
                // TOP of the version flag. How an application is pointed at a configuration is
                // exclusive: passing 2.x's property name to a 3.x build does not error, it falls
                // back to a default configuration and logs to the console, so an entire column
                // can pass while testing nothing. Emitting that chain as three ifs, which is what
                // this did first, sent both properties every time.
                if (test == null) {
                    if (firstMatchWins && chained) {
                        out.append("  else\n").append(emit);
                    } else {
                        out.append(emit.replace("    ", "  "));
                    }
                } else if (firstMatchWins) {
                    out.append(chained ? "  elif " : "  if ")
                            .append(test)
                            .append("; then\n")
                            .append(emit);
                    chained = true;
                } else {
                    out.append("  if ")
                            .append(test)
                            .append("; then\n")
                            .append(emit)
                            .append("  fi\n");
                }
            }
        }
        if (conditional && firstMatchWins) {
            out.append("  fi\n");
        }
        // On top of whichever branch won. A framework that reconfigures logging during its own
        // startup honours its own property and ignores the library's, so those cells need both --
        // and needing both is not a fourth branch of a chain whose branches are exclusive.
        if (additional) {
            for (JsonNode rule : also) {
                String test = conditions(rule, args);
                StringBuilder emit = new StringBuilder();
                for (String flag : texts(rule.path("args"))) {
                    emit.append("    printf '%s\\n' ")
                            .append(splice(flag, args))
                            .append('\n');
                }
                if (emit.length() == 0) {
                    continue;
                }
                if (test == null) {
                    out.append(emit.toString().replace("    ", "  "));
                } else {
                    out.append("  if ")
                            .append(test)
                            .append("; then\n")
                            .append(emit)
                            .append("  fi\n");
                }
            }
        }
        out.append("}\n");
    }

    /** {@code pack_jvm_args}: a list of words per application name. */
    private static void perAppFlags(StringBuilder out, String fn, JsonNode node) {
        if (!node.fieldNames().hasNext()) {
            return;
        }
        out.append(fn).append("() {\n  case \"$1\" in\n");
        java.util.Iterator<String> keys = node.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            out.append("    ").append(glob(key)).append(")\n");
            for (String flag : texts(node.path(key))) {
                out.append("      printf '%s\\n' ")
                        .append(splice(flag, ARGS_NONE))
                        .append('\n');
            }
            out.append("      ;;\n");
        }
        out.append("    *) : ;;\n  esac\n}\n");
    }

    /** A hook that answers one value, with no name to switch on. */
    private static void scalar(StringBuilder out, String fn, JsonNode node, java.util.Map<String, String> args) {
        String value = node.isObject() ? "" : node.asText("");
        if (node.isObject()) {
            // {"env": "NAME", "default": "..."} -- an answer the person running it may
            // override without editing the pack. The name is checked against the shape of a
            // shell identifier rather than trusted: it is the one thing here that cannot be
            // quoted, because quoting it is what stops it being an expansion at all.
            String env = node.path("env").asText("");
            if (!env.isEmpty() && !ENV_NAME.matcher(env).matches()) {
                throw new IllegalArgumentException(fn + ": \"" + env + "\" is not a usable environment variable name");
            }
            String fallbackValue = node.path("default").asText("");
            if (env.isEmpty()) {
                value = fallbackValue;
            } else {
                out.append(fn)
                        .append("() { printf '%s' \"${")
                        .append(env)
                        .append(":-")
                        .append(fallbackValue.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("}\"; }\n");
                return;
            }
        }
        if (value.isEmpty()) {
            return;
        }
        out.append(fn).append("() { printf '%s' ").append(splice(value, args)).append("; }\n");
    }

    /**
     * The {@code if} a rule's conditions come to, or null when it has none.
     *
     * <p>Conditions are globs on the values the hook was handed, plus {@code javaBelow} and
     * {@code appIn}, which name the two comparisons a glob cannot make.
     */
    private static String conditions(JsonNode rule, java.util.Map<String, String> args) {
        List<String> tests = new ArrayList<>();
        for (java.util.Map.Entry<String, String> token : new java.util.TreeMap<>(args).entrySet()) {
            String field = token.getKey().substring(1, token.getKey().length() - 1);
            if (rule.hasNonNull(field)) {
                tests.add("[[ \"" + token.getValue() + "\" == "
                        + glob(rule.path(field).asText()) + " ]]");
            }
            String not = field + "Not";
            if (rule.hasNonNull(not)) {
                tests.add("[[ \"" + token.getValue() + "\" != "
                        + glob(rule.path(not).asText()) + " ]]");
            }
        }
        if (rule.hasNonNull("javaBelow") && args.containsKey("{java}")) {
            tests.add("[[ \"" + args.get("{java}") + "\" -lt "
                    + rule.path("javaBelow").asInt() + " ]]");
        }
        // The one list a rule may ask about by name. Written out as a loop rather than as a
        // second copy of the list, so a pack that edits APPS_2X_ONLY does not have to
        // remember that a rule quoted it.
        if (rule.path("appIn").asText("").equals("appsNewestMajorCannotBuild") && args.containsKey("{app}")) {
            tests.add("_pack_in_list \"" + args.get("{app}") + "\" ${APPS_2X_ONLY[@]+\"${APPS_2X_ONLY[@]}\"}");
        }
        return tests.isEmpty() ? null : String.join(" && ", tests);
    }

    /**
     * A pattern, quoted so that only its wildcards are still wildcards.
     *
     * <p>{@code quote()} makes a value literal, which is right everywhere except the two places
     * shell does matching: a {@code case} label and the right-hand side of {@code [[ == ]]}.
     * Quoting there turns a glob into an exact string, so {@code 3.*} stopped matching 3.0.0 and
     * every version rule in a generated pack quietly did nothing -- the failure that looks like a
     * pack which runs fine and skips the wrong cells. Found by rendering one and reading it.
     *
     * <p>So the literal runs are quoted and {@code *} and {@code ?} are left bare: {@code 3.*}
     * becomes {@code '3.'*}. Nothing else is a metacharacter here -- a bracket expression is
     * quoted like any other text, which is a smaller surprise than letting {@code [} through.
     */
    private static String glob(String pattern) {
        StringBuilder out = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    out.append(quote(literal.toString()));
                    literal.setLength(0);
                }
                out.append(c);
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            out.append(quote(literal.toString()));
        }
        return out.length() == 0 ? "''" : out.toString();
    }

    /** A path a pack wrote with a leading {@code ~/}, kept expandable without letting anything else in. */
    private static String home(String value) {
        return value.startsWith("~/") ? "\"$HOME/\"" + quote(value.substring(2)) : quote(value);
    }

    /** A literal with {tokens} replaced by the positional they mean, still fully quoted. */
    private static String splice(String value, java.util.Map<String, String> args) {
        String quoted = quote(value);
        for (java.util.Map.Entry<String, String> token : args.entrySet()) {
            quoted = quoted.replace(token.getKey(), "'\"" + token.getValue() + "\"'");
        }
        return quoted;
    }

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
