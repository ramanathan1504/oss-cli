package com.osscli.cli;

import com.osscli.retrieval.Corpus;
import com.osscli.retrieval.LocalEmbedder;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The local model: whether it is here, and fetching it if you want it.
 *
 * <p>Search, {@code pick} and the rest work without it — term overlap against your own writing is
 * arithmetic and needs nothing installed. The model upgrades that from shared words to shared
 * meaning, which is a real difference when the words differ: "rollover compression" and "log file
 * rotation with zstd" are the same subject and share no terms.
 *
 * <p>It is a deliberate command rather than something that happens to you. 22 MB arriving silently
 * the first time a search runs looks like a hang, and a tool that downloads things unasked is a
 * tool people stop pointing at their work.
 */
@Command(
        name = "model",
        mixinStandardHelpOptions = true,
        description = "The local model that upgrades search from words to meaning")
public class ModelCommand implements Callable<Integer> {

    @Option(names = "--fetch", description = "Download it (about 22 MB, once)")
    boolean fetch;

    @Override
    public Integer call() {
        if (LocalEmbedder.isDownloaded()) {
            System.out.println("  model  present — search and pick answer by meaning");
            report();
            return 0;
        }
        if (!fetch) {
            System.out.println("  model  not present — search and pick answer by shared terms");
            System.out.println();
            System.out.println("  That works, and needs nothing installed. The model adds matching by");
            System.out.println("  meaning: \"rollover compression\" and \"log rotation with zstd\" are the");
            System.out.println("  same subject and share no words.");
            System.out.println();
            System.out.println("  oss model --fetch      about 22 MB, once, stored under ~/.oss-cli/models");
            report();
            return 0;
        }
        try {
            LocalEmbedder.load(m -> System.out.println("  " + m)).close();
            System.out.println("  model  ready");
            report();
            return 0;
        } catch (Exception e) {
            System.err.println("error  " + e.getMessage());
            System.err.println("       Nothing is broken: everything still answers by shared terms.");
            return 1;
        }
    }

    /** What it would be searching, so the answer is not abstract. */
    private void report() {
        try {
            Corpus c = Corpus.load(m -> {});
            if (c.size() == 0) {
                System.out.println("  corpus is empty — oss memory file <notes.md> starts it");
            } else {
                System.out.printf("  corpus %d item(s) %s%n", c.size(), c.composition());
            }
        } catch (Exception e) {
            // The corpus is a courtesy here; failing to describe it must not fail the command.
        }
    }
}
