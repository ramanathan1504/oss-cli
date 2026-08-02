package com.osscli.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osscli.github.GitHubClient;
import com.osscli.model.RepoProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Builds a repository's profile from the files it actually contains.
 *
 * <p>Every rule here is a pattern, never a repository name. A project the author has never seen is profiled by the same
 * code path as a familiar one; a Rust or Python project simply matches different rows and reports what it found.
 *
 * <p>Two details caused most of the design. Documentation is matched by NAME across extensions, because a project whose
 * README is {@code README.adoc} would otherwise be reported as having no documentation at all. And Maven conventions
 * are read from the inherited POM chain as well as the checkout, because for many projects the packaging and API rules
 * are published in a parent artifact rather than committed to the repository being reviewed.
 */
public final class RepoProfileBuilder {

    private static final Logger LOGGER = LogManager.getLogger(RepoProfileBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Documentation base names, matched case-insensitively against any extension. */
    private static final String[] DOC_NAMES = {
        "readme",
        "contributing",
        "building",
        "developing",
        "development",
        "security",
        "agents",
        "governance",
        "release-notes",
        "releasing",
        "code_of_conduct",
        "architecture",
        "maintainers"
    };

    /** Build descriptors. Presence identifies the build system; contents carry the rules. */
    private static final Map<String, String> BUILD_FILES = Map.ofEntries(
            Map.entry("pom.xml", "maven"),
            Map.entry("build.gradle", "gradle"),
            Map.entry("build.gradle.kts", "gradle"),
            Map.entry("settings.gradle", "gradle"),
            Map.entry("settings.gradle.kts", "gradle"),
            Map.entry("build.xml", "ant"),
            Map.entry("package.json", "npm"),
            Map.entry("pyproject.toml", "python"),
            Map.entry("setup.py", "python"),
            Map.entry("go.mod", "go"),
            Map.entry("cargo.toml", "cargo"),
            Map.entry("makefile", "make"),
            Map.entry("cmakelists.txt", "cmake"));

    /** Single-purpose files that state a toolchain version outright. */
    private static final Map<String, String> VERSION_FILES = Map.of(
            ".java-version", "java",
            ".nvmrc", "node",
            ".python-version", "python",
            ".ruby-version", "ruby",
            ".tool-versions", "asdf",
            ".go-version", "go");

    /**
     * Convention markers, searched inside build and packaging files.
     *
     * <p>Each is a gate a change can trip. Naming the marker is what lets a review say which rule a diff touches rather
     * than only that something looks unusual.
     */
    private static final Map<String, String> CONVENTION_MARKERS = new LinkedHashMap<>();

    static {
        CONVENTION_MARKERS.put("bnd-maven-plugin", "OSGi bundles built by bnd");
        CONVENTION_MARKERS.put("bnd-baseline-maven-plugin", "OSGi/API baseline enforced — exported packages are gated");
        CONVENTION_MARKERS.put("maven-bundle-plugin", "OSGi bundles built by Felix");
        CONVENTION_MARKERS.put("Export-Package", "explicit OSGi package exports");
        CONVENTION_MARKERS.put("Import-Package", "explicit OSGi package imports");
        CONVENTION_MARKERS.put("Bundle-SymbolicName", "OSGi bundle identity declared");
        CONVENTION_MARKERS.put("module-info", "JPMS modules");
        CONVENTION_MARKERS.put("japicmp", "binary compatibility checked");
        CONVENTION_MARKERS.put("revapi", "API compatibility checked");
        CONVENTION_MARKERS.put("spotless", "formatting enforced by spotless");
        CONVENTION_MARKERS.put("checkstyle", "checkstyle enforced");
        CONVENTION_MARKERS.put("spotbugs", "spotbugs enforced");
        CONVENTION_MARKERS.put("pmd", "PMD enforced");
        CONVENTION_MARKERS.put("enforcer", "maven-enforcer rules active");
        CONVENTION_MARKERS.put("cyclonedx", "SBOM generated");
    }

    /** Packaging descriptors carrying OSGi metadata directly. */
    private static final String[] PACKAGING_SUFFIXES = {".bnd", "manifest.mf"};

    private static final int MAX_DOC_BYTES = 40000;

    private RepoProfileBuilder() {}

    public static RepoProfile build(String repository) throws Exception {
        String[] parts = repository.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Repository must be 'owner/name', got: " + repository);
        }
        String owner = parts[0];
        String name = parts[1];

        GitHubClient client = new GitHubClient();

        String repoJson = client.getJson("/repos/" + owner + "/" + name);
        if (repoJson == null) {
            throw new IllegalArgumentException("Repository '" + repository + "' was not found or is not accessible.");
        }
        JsonNode repoNode = MAPPER.readTree(repoJson);
        String defaultBranch = repoNode.path("default_branch").asText("main");
        String language = repoNode.path("language").asText(null);

        LOGGER.info("  ↳ Reading '{}' file tree (branch {})...", repository, defaultBranch);
        List<String> paths = listTree(client, owner, name, defaultBranch);

        Map<String, String> docs = new LinkedHashMap<>();
        Map<String, String> conventions = new LinkedHashMap<>();
        String buildSystem = null;
        String targetVersion = null;
        String rootPom = null;

        // ── Documentation, matched by name across any extension ──────────────
        for (String path : paths) {
            if (path.contains("/") && !path.startsWith(".github/")) {
                continue; // top level, plus the .github process files
            }
            String base = baseName(path);
            for (String docName : DOC_NAMES) {
                if (base.equals(docName) || base.startsWith(docName + ".")) {
                    String body = safeFetch(client, owner, name, path);
                    if (body != null) {
                        docs.put(path, truncate(body));
                    }
                    break;
                }
            }
            if (base.equals("pull_request_template.md")) {
                String body = safeFetch(client, owner, name, path);
                if (body != null) {
                    docs.put(path, truncate(body));
                }
            }
        }

        // ── Toolchain version files ──────────────────────────────────────────
        for (String path : paths) {
            String tool = VERSION_FILES.get(baseName(path));
            if (tool != null && !path.contains("/")) {
                String body = safeFetch(client, owner, name, path);
                if (body != null && !body.isBlank()) {
                    targetVersion = tool + " "
                            + body.trim().lines().findFirst().orElse("").trim();
                    conventions.put("toolchain-file", path + " → " + targetVersion);
                }
            }
        }

        // ── Build descriptors, and the conventions inside them ───────────────
        for (String path : paths) {
            String system = BUILD_FILES.get(baseName(path));
            if (system == null || path.chars().filter(c -> c == '/').count() > 1) {
                continue;
            }
            if (buildSystem == null || !path.contains("/")) {
                buildSystem = system;
            }
            String body = safeFetch(client, owner, name, path);
            if (body == null) {
                continue;
            }
            if (!path.contains("/") && "pom.xml".equals(baseName(path))) {
                rootPom = body;
            }
            scanForConventions(body, "this repository (" + path + ")", conventions);
        }

        // ── Packaging descriptors committed in the tree ──────────────────────
        for (String path : paths) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String suffix : PACKAGING_SUFFIXES) {
                if (lower.endsWith(suffix)) {
                    conventions.put("packaging-descriptor", path);
                    String body = safeFetch(client, owner, name, path);
                    if (body != null) {
                        scanForConventions(body, "this repository (" + path + ")", conventions);
                    }
                    break;
                }
            }
        }

        // ── CI ───────────────────────────────────────────────────────────────
        long workflows = paths.stream()
                .filter(p -> p.startsWith(".github/workflows/"))
                .filter(p -> p.endsWith(".yml") || p.endsWith(".yaml"))
                .count();
        if (workflows > 0) {
            conventions.put("ci", workflows + " GitHub Actions workflow(s)");
        }

        // ── The inherited chain, where many projects keep the real rules ─────
        String minVersion = null;
        if (rootPom != null) {
            List<MavenParentChain.Pom> chain = MavenParentChain.resolve(rootPom);
            if (!chain.isEmpty()) {
                LOGGER.info(
                        "  ↳ Following {} inherited POM(s) for conventions not held in this repository...",
                        chain.size());
            }
            for (MavenParentChain.Pom pom : chain) {
                conventions.put("inherits-from", pom.coordinates());
                scanForConventions(pom.xml(), "inherited from " + pom.coordinates(), conventions);

                if (targetVersion == null) {
                    String release = MavenParentChain.tag(pom.xml(), "maven.compiler.release");
                    if (release != null) {
                        targetVersion = "java " + release;
                    }
                }
                if (minVersion == null) {
                    minVersion = MavenParentChain.tag(pom.xml(), "minimalJavaBuildVersion");
                }
            }
        }

        RepoProfile profile = new RepoProfile(
                repository,
                language,
                buildSystem,
                targetVersion,
                minVersion,
                MAPPER.writeValueAsString(conventions),
                MAPPER.writeValueAsString(docs),
                summarize(repository, language, buildSystem, targetVersion, conventions, docs));

        return profile;
    }

    /** Records every marker present, with where it was found, so a later reader can cite the source. */
    private static void scanForConventions(String body, String source, Map<String, String> into) {
        for (Map.Entry<String, String> marker : CONVENTION_MARKERS.entrySet()) {
            if (body.contains(marker.getKey())) {
                into.merge(
                        marker.getKey(),
                        marker.getValue() + " [" + source + "]",
                        (existing, added) -> existing.contains(source) ? existing : existing + "; " + source);
            }
        }
    }

    private static List<String> listTree(GitHubClient client, String owner, String name, String branch)
            throws Exception {
        String json = client.getJson("/repos/" + owner + "/" + name + "/git/trees/" + branch + "?recursive=1");
        List<String> paths = new ArrayList<>();
        if (json == null) {
            return paths;
        }
        JsonNode tree = MAPPER.readTree(json).path("tree");
        for (JsonNode node : tree) {
            paths.add(node.path("path").asText(""));
        }
        return paths;
    }

    private static String safeFetch(GitHubClient client, String owner, String name, String path) {
        try {
            return client.getFileContent(owner, name, path);
        } catch (Exception e) {
            LOGGER.debug("Could not read {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String s) {
        return s.length() > MAX_DOC_BYTES ? s.substring(0, MAX_DOC_BYTES) : s;
    }

    private static String summarize(
            String repository,
            String language,
            String buildSystem,
            String targetVersion,
            Map<String, String> conventions,
            Map<String, String> docs) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Repository profile: ").append(repository).append("\n\n");
        sb.append("- Language: ")
                .append(language == null ? "unknown" : language)
                .append('\n');
        sb.append("- Build system: ")
                .append(buildSystem == null ? "unknown" : buildSystem)
                .append('\n');
        sb.append("- Toolchain: ")
                .append(targetVersion == null ? "not declared" : targetVersion)
                .append('\n');
        sb.append("- Documents found: ")
                .append(String.join(", ", docs.keySet()))
                .append('\n');
        sb.append("\n## Conventions detected\n\n");
        conventions.forEach(
                (k, v) -> sb.append("- **").append(k).append("**: ").append(v).append('\n'));
        return sb.toString();
    }
}
