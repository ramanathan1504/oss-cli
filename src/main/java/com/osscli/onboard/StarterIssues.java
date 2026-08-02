package com.osscli.onboard;

import com.osscli.model.Issue;
import com.osscli.model.Label;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the open issues a project has marked as suitable for someone new.
 *
 * <p>Matching is on whole words within a normalised label, not on substrings. Projects label liberally, and a
 * substring test finds "easy" inside {@code area/resteasy-classic} and "starter" inside {@code spring boot starter} --
 * measured on a real corpus, both appear alongside the genuine labels. Handing a newcomer a REST framework bug as a
 * first task is worse than handing them nothing, because it looks vetted.
 */
public final class StarterIssues {

    /**
     * Label phrases that mean "suitable for a newcomer", normalised to spaces.
     *
     * <p>Covers the spellings projects actually use: separators vary ({@code good-first-issue},
     * {@code good first issue}), and several ecosystems have their own ({@code E-easy}, {@code difficulty/easy},
     * {@code low hanging fruit}).
     */
    private static final Set<String> STARTER_PHRASES = Set.of(
            "good first issue",
            "good first bug",
            "first timers only",
            "help wanted",
            "beginner",
            "beginner friendly",
            "newcomer",
            "newbie",
            // Not bare "starter": in the Spring ecosystem a starter is a dependency bundle, and
            // "spring boot starter" is a real label on issues that have nothing to do with newcomers.
            "starter issue",
            "starter task",
            "good starter",
            "easy",
            "e easy",
            "difficulty easy",
            "low hanging fruit",
            "up for grabs",
            "contributions welcome");

    private StarterIssues() {}

    /** Open issues carrying a newcomer label, least-discussed first. */
    public static List<Issue> find(List<Issue> issues, int limit) {
        List<Issue> hits = new ArrayList<>();
        for (Issue issue : issues) {
            if (issue.isPullRequest() || issue.labels() == null) {
                continue;
            }
            for (Label label : issue.labels()) {
                if (isStarterLabel(label.name())) {
                    hits.add(issue);
                    break;
                }
            }
        }

        // Fewest comments first. A starter issue with a long thread is usually one that turned out to be hard, or one
        // somebody is already on -- neither is what a newcomer should pick up.
        hits.sort(Comparator.comparingInt(Issue::comments));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** The newcomer label on an issue, for display, or null. */
    public static String labelOf(Issue issue) {
        if (issue.labels() == null) {
            return null;
        }
        for (Label label : issue.labels()) {
            if (isStarterLabel(label.name())) {
                return label.name();
            }
        }
        return null;
    }

    static boolean isStarterLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        // Separators become spaces so every spelling of a phrase collapses to one form, then the phrase is required to
        // sit on word boundaries -- which is what keeps "easy" out of "resteasy".
        String normalised =
                " " + raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";

        for (String phrase : STARTER_PHRASES) {
            if (normalised.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }
}
