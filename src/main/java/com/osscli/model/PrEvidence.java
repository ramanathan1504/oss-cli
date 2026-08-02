package com.osscli.model;

/**
 * Everything known about one pull request at one commit.
 *
 * <p>Identified by {@code headSha} as well as number, because a pull request's content is rewritten by every push. Two
 * evidence sets for the same number are different objects, not different versions of one.
 *
 * <p>JSON columns hold the API responses as received. Keeping the raw form means a later reviewer, model or profile can
 * ask a question of this evidence that nobody thought to extract when it was fetched.
 */
public record PrEvidence(
        String repository,
        long prNumber,
        String headSha,
        String title,
        String author,
        String state,
        String baseRef,
        String body,
        String commitsJson,
        String filesJson,
        String diff,
        String reviewsJson,
        String commentsJson,
        String checksJson,
        int additions,
        int deletions,
        int changedFiles) {}
