package com.osscli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Properties;
import picocli.CommandLine;

/**
 * Answers {@code oss-cli --version} with enough detail to tell two identical builds apart.
 *
 * <p>The version used to be a literal in {@code RootCommand}, and had drifted: it read {@code 1.1}
 * while the pom said {@code 1.2.0}. It is now filtered into {@code version.properties} at build
 * time, so it cannot disagree with the pom again.
 *
 * <p>Version alone still does not identify a build. {@code release.sh} runs the same
 * {@code mvn clean package} a developer runs, so a locally built 1.2.0 and the released 1.2.0 report
 * the same number. What actually separates them is where they were loaded from and which data
 * directory they are about to write to, so both are printed.
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    private static final String RESOURCE = "/version.properties";
    private static final String UNKNOWN = "unknown";

    @Override
    public String[] getVersion() {
        Properties props = load();
        String version = props.getProperty("version", UNKNOWN);
        String buildTime = props.getProperty("buildTime", UNKNOWN);
        Path location = codeSourceLocation();

        String headline = "oss-cli " + version;
        if (isDevelopmentBuild(location)) {
            headline += " (development build)";
        }

        String dataLine = "data      " + AppPaths.BASE_DIR;
        if (AppPaths.IS_RELOCATED) {
            dataLine += "   [" + AppPaths.HOME_ENV_VAR + "]";
        }

        return new String[] {
            headline, "build     " + buildTime, "running   " + (location == null ? UNKNOWN : location), dataLine,
        };
    }

    /**
     * Whether this build came from a working tree rather than an installed artifact.
     *
     * <p>A heuristic on the load path, not a guarantee: nothing is stamped into the jar at release
     * time, so a released jar copied into a {@code target/} directory would still read as
     * development. It is accurate for the case it exists for -- telling a Homebrew install apart
     * from {@code java -jar target/...} -- and it errs toward flagging development, which is the
     * safer direction to be wrong in.
     */
    private static boolean isDevelopmentBuild(Path location) {
        if (location == null) {
            return false;
        }
        String path = location.toString();
        if (!path.endsWith(".jar")) {
            return true; // loose classes: run straight from the compiler output
        }
        Path parent = location.getParent();
        return parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals("target");
    }

    private static Path codeSourceLocation() {
        try {
            CodeSource source = VersionProvider.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Paths.get(source.getLocation().toURI());
        } catch (URISyntaxException | SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // --version must never be the thing that fails; the defaults already say "unknown".
        }
        return props;
    }
}
