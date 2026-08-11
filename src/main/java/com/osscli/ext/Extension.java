package com.osscli.ext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One registered capability that OSS-CLI can call out to.
 *
 * <p>OSS-CLI knows things. It deliberately owns no clone, runs no build, and is specific to no
 * project -- that boundary is the reason it works against any repository in any language. But the
 * two questions it cannot answer alone are "does this actually run?" and "have I already worked
 * this out once?". Those belong to something that executes and something that remembers.
 *
 * <p>An extension is how such a thing announces itself. It is declared by a manifest file at the
 * root of any repository, so the extension does not have to be Java, does not have to be built, and
 * does not have to know OSS-CLI exists beyond writing that file:
 *
 * <pre>{@code
 * {
 *   "name": "log4j",
 *   "kind": "bench",
 *   "description": "Log4j across a version x config x app matrix",
 *   "exec": "./bench",
 *   "verbs": { "list": "list", "run": "run", "matrix": "matrix", "review": "review" },
 *   "axes": ["app", "version", "config"]
 * }
 * }</pre>
 *
 * <p>Shelling out rather than loading a plugin is the whole point. A Java SPI would have forced
 * every bench author to write Java, and the first bench this was built for is 936 lines of bash
 * that already worked. The contract is therefore a file and a process, which any language can
 * satisfy.
 *
 * <p><b>{@code verbs} maps a portable name to whatever that tool actually calls it.</b> OSS-CLI
 * speaks {@code run}; one bench spells that {@code run}, another spells it {@code exec}. Without the
 * indirection every bench would have to rename its own commands to join in, which is precisely the
 * kind of tax that stops people writing one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Extension {

    /** The manifest filename looked for at the root of a candidate repository. */
    public static final String MANIFEST = "oss-ext.json";

    /**
     * What an extension is for.
     *
     * <p>Two kinds, because there are two questions OSS-CLI cannot answer alone. Deliberately not
     * an open string: a third kind should be a considered addition, not a typo that silently
     * registers something nothing will ever dispatch to.
     */
    public enum Kind {
        /** Executes something real -- a matrix, a build, a repro. Answers "does it actually run?" */
        BENCH,
        /** Remembers -- an archive that files and retrieves. Answers "have I worked this out?" */
        KB;

        public static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("manifest is missing \"kind\" (expected: bench, kb)");
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown kind \"" + raw + "\" (expected: bench, kb)");
            }
        }

        public String lower() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private String name;
    private String kind;
    private String description;
    private String exec;
    private Map<String, String> verbs = new LinkedHashMap<>();
    private List<String> axes = List.of();

    /**
     * Verbs that write somewhere outward-facing, and so must be confirmed before they run.
     *
     * <p>Declared by the extension because only the extension knows. OSS-CLI cannot tell from the
     * outside that {@code followup} is read-only while {@code followup --comment} posts, so guessing
     * from a verb name would be both wrong and quietly reassuring. Anything listed here is refused
     * unless the operator named this repository with {@code --approve-upstream} and confirms at the
     * terminal; anything not listed runs freely, which is why the default is the empty list and an
     * author has to opt a verb in deliberately.
     */
    private List<String> writes = List.of();

    /**
     * Where those writes land, as {@code owner/name} — compared for equality against
     * {@code --approve-upstream}, so it must be the bare repository and nothing else. A sentence
     * here (it once held one) can never match an approval, silently making the verb unusable.
     */
    private String writesTo;

    /**
     * Absolute path to the repository this was registered from.
     *
     * <p>Written by the registry, never read from the manifest. A manifest that could name its own
     * root would let a copied file point at a directory it does not live in.
     */
    private String root;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExec() {
        return exec;
    }

    public void setExec(String exec) {
        this.exec = exec;
    }

    public Map<String, String> getVerbs() {
        return verbs;
    }

    public void setVerbs(Map<String, String> verbs) {
        this.verbs = verbs == null ? new LinkedHashMap<>() : verbs;
    }

    public List<String> getAxes() {
        return axes;
    }

    public void setAxes(List<String> axes) {
        this.axes = axes == null ? List.of() : axes;
    }

    public List<String> getWrites() {
        return writes;
    }

    public void setWrites(List<String> writes) {
        this.writes = writes == null ? List.of() : writes;
    }

    public String getWritesTo() {
        return writesTo;
    }

    public void setWritesTo(String writesTo) {
        this.writesTo = writesTo;
    }

    /** Whether dispatching this portable verb would write somewhere outward-facing. */
    public boolean writesOutward(String portableVerb) {
        return writes.stream().anyMatch(w -> w.equalsIgnoreCase(portableVerb));
    }

    /** Human description of where a write lands; falls back to naming the extension itself. */
    public String writeTarget() {
        return (writesTo == null || writesTo.isBlank()) ? ("whatever " + name + " posts to") : writesTo;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Kind kind() {
        return Kind.parse(kind);
    }

    public Path rootPath() {
        return Path.of(root);
    }

    /** The executable, resolved against the extension's own root rather than the caller's cwd. */
    public Path execPath() {
        Path p = Path.of(exec);
        return p.isAbsolute() ? p : rootPath().resolve(p).normalize();
    }

    /**
     * Translate a portable verb into the argument this tool actually takes.
     *
     * @return null when this extension does not offer that verb -- callers report it rather than
     *     guessing, because passing an unknown verb through would run the tool's default command,
     *     and for a bench the default is often "do everything".
     */
    public String resolveVerb(String portable) {
        return verbs.get(portable);
    }

    /**
     * Fail loudly on anything that would only surface later as a confusing exec error.
     *
     * @throws IllegalArgumentException with a message naming the field, for a manifest author
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("manifest is missing \"name\"");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    "\"name\" must be a plain identifier (letters, digits, . _ -): got \"" + name + "\"");
        }
        kind(); // throws with the expected-values message
        if (exec == null || exec.isBlank()) {
            throw new IllegalArgumentException("manifest is missing \"exec\"");
        }
        if (verbs.isEmpty()) {
            throw new IllegalArgumentException("manifest declares no \"verbs\", so nothing could ever be dispatched");
        }
    }
}
