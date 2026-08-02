package com.osscli.model;

/**
 * What a repository is, derived from what it actually contains.
 *
 * <p>Everything here comes from pattern-matching real files, never from a table of known projects. A repository nobody
 * has profiled before is handled the same way as a familiar one -- which is the only way this can serve a user who
 * registers something the author has never seen.
 */
public record RepoProfile(
        String repository,
        String primaryLanguage,
        String buildSystem,
        String targetVersion,
        String minVersion,
        String conventionsJson,
        String docsJson,
        String summary) {}
