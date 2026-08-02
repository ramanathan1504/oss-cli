package com.osscli.profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Walks a Maven project's inherited POMs.
 *
 * <p>Needed because a project's real conventions are frequently not in its own repository. Apache Log4j declares no
 * OSGi configuration anywhere in its tree, yet every module is a bundle: the {@code bnd-maven-plugin} setup, the
 * symbolic-name derivation and the API baseline gate all live in {@code org.apache.logging:logging-parent}, a separate
 * artifact in a separate repository. Reading only the checked-out files reports "no OSGi conventions" for a project
 * built entirely around them.
 *
 * <p>Parents are resolved from Maven Central rather than guessed from repository names. Central is where the parent is
 * actually published, so this works for any Maven project regardless of the forge it is hosted on, or whether its
 * parent is hosted anywhere public at all.
 */
public final class MavenParentChain {

    private static final Logger LOGGER = LogManager.getLogger(MavenParentChain.class);

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    /** Depth cap. Real hierarchies are two or three deep; anything more is a loop or a mistake, not information. */
    private static final int MAX_DEPTH = 3;

    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    private MavenParentChain() {}

    /** One inherited POM: its coordinates and its raw XML. */
    public record Pom(String groupId, String artifactId, String version, String xml) {
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /**
     * Returns the inherited POMs above {@code rootPomXml}, nearest first.
     *
     * <p>Resolution stops quietly at the first parent that cannot be fetched. A private or unpublished parent is a
     * normal situation for corporate repositories, and it must degrade to a shorter chain rather than failing the
     * profile that was being built around it.
     */
    public static List<Pom> resolve(String rootPomXml) {
        List<Pom> chain = new ArrayList<>();
        String current = rootPomXml;

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Matcher block = PARENT_BLOCK.matcher(current == null ? "" : current);
            if (!block.find()) {
                break;
            }
            String parent = block.group(1);
            String groupId = tag(parent, "groupId");
            String artifactId = tag(parent, "artifactId");
            String version = tag(parent, "version");

            if (groupId == null || artifactId == null || version == null) {
                break;
            }

            String xml = fetchFromCentral(groupId, artifactId, version);
            if (xml == null) {
                LOGGER.debug(
                        "Parent {}:{}:{} not resolvable from Central; stopping chain.", groupId, artifactId, version);
                break;
            }

            chain.add(new Pom(groupId, artifactId, version, xml));
            current = xml;
        }
        return chain;
    }

    /** Value of the first occurrence of {@code <name>...</name>}, or null. */
    public static String tag(String xml, String name) {
        if (xml == null) {
            return null;
        }
        Matcher m = Pattern.compile("<" + name + ">\\s*(.*?)\\s*</" + name + ">", Pattern.DOTALL)
                .matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String fetchFromCentral(String groupId, String artifactId, String version) {
        String url = CENTRAL + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + ".pom";
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(15))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
