package com.osscli.ui;

import com.osscli.ext.Extension;
import com.osscli.ext.ExtensionRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is worth doing next, offered where the previous thing finished.
 *
 * <p>A toolbox with sixteen commands is only as useful as someone's memory of it. The moment they
 * are most likely to want the next step is the moment the last one finished -- they searched and
 * found forty issues, and now the useful question is *which one should I pick up*. Sending them back
 * to `--help` to rediscover that is how a capability that exists goes unused.
 *
 * <p>Two rules keep this a help and not a nag:
 *
 * <ul>
 *   <li><b>Only suggest what will actually work.</b> Offering "run it on the bench" with no bench
 *       attached teaches people that the suggestions are noise. Every entry is filtered against what
 *       is really registered, so a suggestion is a promise.
 *   <li><b>Three at most, and never in a pipe.</b> Suggestions are for a person reading a terminal.
 *       They go to stderr and are skipped entirely when output is redirected, so they cannot end up
 *       in a file someone is parsing.
 * </ul>
 */
public final class NextSteps {

    private NextSteps() {}

    /** Where the user just was, which decides what is worth offering. */
    public enum After {
        SEARCH,
        REVIEW,
        TRIAGE,
        SYNC,
        ATTACH,
        PROFILE
    }

    /**
     * Print up to three suggestions for what to do next.
     *
     * @param after the command that just finished
     * @param subject what it was about -- an issue number, a repo -- or null
     */
    public static void suggest(After after, String subject) {
        // No terminal means piped, cron or CI. Nobody is reading, and a suggestion in a log is
        // clutter that outlives its usefulness.
        if (System.console() == null) {
            return;
        }
        List<String> lines = build(after, subject);
        if (lines.isEmpty()) {
            return;
        }
        System.err.println();
        System.err.println("\u001b[2m  next:\u001b[0m");
        for (String l : lines) {
            System.err.println("    " + l);
        }
    }

    private static List<String> build(After after, String subject) {
        boolean hasBench = !ExtensionRegistry.ofKind(Extension.Kind.BENCH).isEmpty();
        boolean hasKb = !ExtensionRegistry.ofKind(Extension.Kind.KB).isEmpty();
        String s = (subject == null || subject.isBlank()) ? "<n>" : subject.trim();

        // Ordered by how often each is the right next move, then trimmed to three: a list long
        // enough to scan past is a list nobody scans.
        Map<String, String> options = new LinkedHashMap<>();
        switch (after) {
            case SEARCH -> {
                options.put("oss-cli pick", "let it choose one worth your time");
                options.put("oss-cli inspect " + s, "see the context behind one result");
                if (hasBench) {
                    options.put("oss-cli bench repro " + s, "does it actually reproduce?");
                }
            }
            case REVIEW -> {
                if (hasBench) {
                    options.put("oss-cli bench review " + s, "build it, run it, red/green");
                }
                options.put("oss-cli guide " + s, "a step-by-step resolution blueprint");
                if (hasKb) {
                    options.put("oss-cli kb file <draft.md>", "keep the reasoning, not just the verdict");
                }
            }
            case TRIAGE -> {
                options.put("oss-cli duplicates", "is this the same as something already open?");
                options.put("oss-cli guide " + s, "how would it actually be fixed?");
                if (hasBench) {
                    options.put("oss-cli bench repro " + s, "confirm it before answering");
                }
            }
            case SYNC -> {
                options.put("oss-cli report", "what changed, and what is waiting on you");
                options.put("oss-cli critical", "rank what arrived by severity");
                options.put("oss-cli search <words>", "find something specific");
            }
            case ATTACH -> {
                if (hasBench) {
                    options.put("oss-cli bench list", "what that bench can run");
                }
                if (hasKb) {
                    options.put("oss-cli kb doctor", "is the archive reachable?");
                }
                options.put("oss-cli serve", "see everything attached, on one page");
            }
            case PROFILE -> {
                options.put("oss-cli onboard", "what this project expects before you contribute");
                options.put("oss-cli review " + s, "judge a pull request against those conventions");
            }
        }

        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (out.size() == 3) {
                break;
            }
            out.add(String.format("\u001b[36m%-34s\u001b[0m \u001b[2m%s\u001b[0m", e.getKey(), e.getValue()));
        }
        return out;
    }
}
