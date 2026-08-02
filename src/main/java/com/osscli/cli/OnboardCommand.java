package com.osscli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.model.Issue;
import com.osscli.model.RepoProfile;
import com.osscli.onboard.StarterIssues;
import com.osscli.profile.RepoProfileBuilder;
import com.osscli.storage.SqliteStorage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Answers "I want to contribute to this project — what do I need to know?"
 *
 * <p>Reads the same repository profile {@code review} judges against, from the other direction. A maintainer needs the
 * rules to check a change; a newcomer needs them before writing one. Deriving both from one source is what stops the
 * advice given to contributors drifting from the standard their pull requests are actually held to.
 */
@Command(
        name = "onboard",
        mixinStandardHelpOptions = true,
        description = "Learn what a project expects before you contribute to it")
public class OnboardCommand implements Callable<Integer> {

    private static final Logger LOGGER = LogManager.getLogger(OnboardCommand.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_STARTER_ISSUES = 8;

    @Option(
            names = {"-r", "--repo"},
            description = "Target repository in 'owner/name' format")
    private String repository;

    @Option(
            names = {"--rebuild"},
            description = "Re-read the repository rather than using the stored profile")
    private boolean rebuild;

    @Option(
            names = {"--no-steps"},
            description = "Skip the model-written build steps and show the source documents only")
    private boolean noSteps;

    @Override
    public Integer call() throws Exception {
        if (repository == null) {
            repository = SqliteStorage.loadConfig("default.repository");
            if (repository == null || repository.isBlank()) {
                LOGGER.error("No repository specified. Use '-r owner/name' or run 'setup' to set a default.");
                return 1;
            }
        }

        RepoProfile profile = rebuild ? null : SqliteStorage.loadRepoProfile(repository);
        if (profile == null) {
            LOGGER.info("Reading {}...", repository);
            try {
                profile = RepoProfileBuilder.build(repository);
            } catch (IllegalArgumentException e) {
                LOGGER.error("{}", e.getMessage());
                return 1;
            }
            SqliteStorage.saveRepoProfile(profile);
        }

        Map<String, String> conventions = MAPPER.readValue(
                profile.conventionsJson() == null ? "{}" : profile.conventionsJson(),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        Map<String, String> docs = MAPPER.readValue(
                profile.docsJson() == null ? "{}" : profile.docsJson(),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));

        printHeader(profile);
        printRules(profile, conventions);
        boolean stepsWritten = noSteps || printBuildSteps(profile, docs);
        printReading(docs);
        boolean issuesShown = printStarterIssues();
        printSources(stepsWritten && !noSteps, issuesShown);
        return 0;
    }

    private void printHeader(RepoProfile p) {
        LOGGER.info("");
        LOGGER.info("╔══════════════════════════════════════════════════════════╗");
        LOGGER.info("║  ONBOARD  |  {}", p.repository());
        LOGGER.info("╚══════════════════════════════════════════════════════════╝");
        LOGGER.info("");
        LOGGER.info("  Language      {}", orUnknown(p.primaryLanguage()));
        LOGGER.info("  Build         {}", orUnknown(p.buildSystem()));
        LOGGER.info(
                "  Toolchain     {}{}",
                p.targetVersion() == null ? "not declared" : p.targetVersion(),
                p.minVersion() == null ? "" : "  (minimum " + p.minVersion() + ")");
    }

    /**
     * The gates a contribution has to pass, phrased as instructions rather than as detected plugin names.
     *
     * <p>A newcomer cannot act on "bnd-baseline-maven-plugin". They can act on being told that adding a public class
     * will fail the build until the API baseline is updated.
     */
    private void printRules(RepoProfile profile, Map<String, String> conventions) {
        List<String> rules = new ArrayList<>();

        if (profile.targetVersion() != null) {
            rules.add("Write for " + profile.targetVersion()
                    + (profile.minVersion() == null ? "" : " (minimum " + profile.minVersion() + ")")
                    + ". Newer language features will not compile here.");
        }
        if (conventions.containsKey("spotless")) {
            rules.add("Run the formatter before committing — the build fails on unformatted code.");
        }
        if (conventions.containsKey("checkstyle") || conventions.containsKey("pmd")) {
            rules.add("Style is linted as part of the build, not reviewed by hand.");
        }
        if (conventions.containsKey("bnd-baseline-maven-plugin")
                || conventions.containsKey("japicmp")
                || conventions.containsKey("revapi")) {
            rules.add("Public API is gated. Adding, moving or changing a public type can fail the build "
                    + "until the API baseline is updated — mention it in your pull request.");
        }
        if (conventions.containsKey("bnd-maven-plugin") || conventions.containsKey("maven-bundle-plugin")) {
            rules.add("Modules ship as OSGi bundles. A new package may need its exports declared.");
        }
        if (conventions.containsKey("module-info")) {
            rules.add("The project uses JPMS modules, so a new package must be declared in module-info.");
        }
        if (conventions.containsKey("inherits-from")) {
            rules.add("Build rules are inherited from " + conventions.get("inherits-from")
                    + " — some conventions are not visible in this repository at all.");
        }
        if (conventions.containsKey("ci")) {
            rules.add("Every pull request runs " + conventions.get("ci") + ". Expect them to gate the merge.");
        }

        LOGGER.info("");
        LOGGER.info("── What this project expects ──");
        if (rules.isEmpty()) {
            LOGGER.info("  Nothing enforced was detected. Read the contributing guide before assuming there is none.");
        }
        rules.forEach(r -> LOGGER.info("  • {}", r));
    }

    /**
     * Asks the local model to turn the project's own build documentation into commands.
     *
     * <p>Grounded in the documents rather than general knowledge, because the whole difficulty for a newcomer is that
     * every project builds differently. Where the documents do not say, the model is told to say so — a plausible
     * invented command wastes more time than an admission, since it fails somewhere unrelated to the real setup.
     *
     * @return true when steps were written
     */
    private boolean printBuildSteps(RepoProfile profile, Map<String, String> docs) {
        String source = null;
        String sourceName = null;
        for (Map.Entry<String, String> e : docs.entrySet()) {
            String base = e.getKey().toLowerCase(Locale.ROOT);
            if (base.contains("building") || base.contains("contributing") || base.contains("developing")) {
                source = e.getValue();
                sourceName = e.getKey();
                break;
            }
        }
        if (source == null || source.isBlank()) {
            return false;
        }

        try {
            String model = SqliteStorage.loadConfig("ollama.model.guidance");
            if (model == null || model.isBlank()) {
                model = "qwen2.5-coder:7b";
            }
            com.osscli.llm.OllamaClient ollama = new com.osscli.llm.OllamaClient(model);
            if (!ollama.isServerReachable()) {
                return false;
            }

            LOGGER.info("");
            LOGGER.info("  ↳ Reading {} for the build steps...", sourceName);

            String doc = source.length() > 18000 ? source.substring(0, 18000) : source;
            String prompt = String.format(
                    """
                    A new contributor wants to build and test %s (%s, %s).

                    Below is the project's own %s. Extract the commands it actually
                    gives. Do not supply commands from general knowledge of this build
                    tool — if the document does not say how to do something, return an
                    empty list for it rather than guessing.

                    DOCUMENT:
                    %s

                    Respond in JSON with this exact structure:
                    {
                      "build": ["<command>"],
                      "test": ["<command>"],
                      "gotchas": ["<something surprising a newcomer would hit>"]
                    }
                    """,
                    profile.repository(),
                    orUnknown(profile.primaryLanguage()),
                    orUnknown(profile.buildSystem()),
                    sourceName,
                    doc);

            JsonNode node = MAPPER.readTree(ollama.generateJson(prompt));

            boolean any = printCommands("Build it", node.path("build"))
                    | printCommands("Test it", node.path("test"))
                    | printBullets("Watch out for", node.path("gotchas"));

            if (!any) {
                LOGGER.info("  {} does not spell out build commands — read it directly.", sourceName);
            }
            return true;

        } catch (Exception e) {
            LOGGER.debug("Could not derive build steps: {}", e.getMessage());
            return false;
        }
    }

    private boolean printCommands(String heading, JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return false;
        }
        LOGGER.info("");
        LOGGER.info("── {} ──", heading);
        for (JsonNode item : array) {
            LOGGER.info("  $ {}", item.asText(""));
        }
        return true;
    }

    private boolean printBullets(String heading, JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return false;
        }
        LOGGER.info("");
        LOGGER.info("── {} ──", heading);
        for (JsonNode item : array) {
            LOGGER.info("  • {}", item.asText(""));
        }
        return true;
    }

    private void printReading(Map<String, String> docs) {
        if (docs.isEmpty()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info("── Read these ──");
        for (Iterator<String> it = docs.keySet().iterator(); it.hasNext(); ) {
            String name = it.next();
            String lower = name.toLowerCase(Locale.ROOT);
            String why = "";
            if (lower.contains("contributing")) {
                why = "  ← the process your pull request goes through";
            } else if (lower.contains("building")) {
                why = "  ← how to get it compiling";
            } else if (lower.contains("pull_request_template")) {
                why = "  ← what your pull request description must contain";
            } else if (lower.contains("agents")) {
                why = "  ← this project has instructions for AI agents";
            } else if (lower.contains("security")) {
                why = "  ← read before reporting anything security-related";
            } else if (lower.contains("code_of_conduct")) {
                why = "  ← the behaviour expected of contributors";
            }
            LOGGER.info("  {}{}", name, why);
        }
    }

    /** @return true when starter issues were found and listed */
    private boolean printStarterIssues() {
        try {
            List<Issue> issues = SqliteStorage.loadIssues(repository);
            if (issues.isEmpty()) {
                LOGGER.info("");
                LOGGER.info("── Where to start ──");
                LOGGER.info("  No issues synced yet — run 'sync -r {}' to see what needs doing.", repository);
                return false;
            }

            List<Issue> starters = StarterIssues.find(issues, MAX_STARTER_ISSUES);
            LOGGER.info("");
            if (starters.isEmpty()) {
                LOGGER.info("── Where to start ──");
                LOGGER.info("  None of the {} open issues carry a newcomer label.", issues.size());
                LOGGER.info("  Ask on the project's own channels which areas need help.");
                return false;
            }

            LOGGER.info("── Where to start ({} issue(s) labelled for newcomers) ──", starters.size());
            for (Issue i : starters) {
                LOGGER.info(
                        "  #{}  [{}]  {}", i.number(), StarterIssues.labelOf(i), i.title() == null ? "" : i.title());
                LOGGER.info("        {} comment(s) so far", i.comments());
            }
            LOGGER.info("");
            LOGGER.info("  Fewest comments first — a long thread usually means it turned out to be hard,");
            LOGGER.info("  or somebody is already on it.");
            return true;

        } catch (Exception e) {
            LOGGER.debug("Could not list starter issues: {}", e.getMessage());
            return false;
        }
    }

    private void printSources(boolean stepsWritten, boolean issuesShown) {
        LOGGER.info("");
        LOGGER.info("── Where this came from ──");
        LOGGER.info("  ✔ The repository's own files and inherited build rules");
        LOGGER.info(
                "  {} Build steps read out of the project's documentation{}",
                stepsWritten ? "✔" : "○",
                stepsWritten ? "" : (noSteps ? " — skipped by --no-steps" : " — needs Ollama running"));
        LOGGER.info(
                "  {} Open issues labelled for newcomers{}",
                issuesShown ? "✔" : "○",
                issuesShown ? "" : " — run 'sync' to populate them");
    }

    private String orUnknown(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
