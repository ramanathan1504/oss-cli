package com.osscli.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Search that finds related things without a model.
 *
 * <p>Semantic search needed an embedding server, so with no model running the whole of *finding*
 * disappeared -- while the data sat right there in SQLite. That is the wrong shape for a tool whose
 * point is that your own material is the corpus: the storing half already works with no AI, and the
 * finding half should degrade rather than vanish.
 *
 * <p>This is TF-IDF over an inverted index, built in memory from what is already stored. No server,
 * no model, no network. When a model IS available it still wins on meaning, and this becomes the
 * floor rather than the ceiling.
 *
 * <h2>Why it finds related things, not just matches</h2>
 *
 * A plain substring search answers "does this contain those letters". This scores by <b>which</b>
 * words two texts share: a word appearing in almost every issue carries almost no signal, and one
 * appearing in three carries a great deal. That is what makes {@code NullPointerException rollover}
 * surface the three issues about rollovers failing rather than the four hundred mentioning an NPE.
 *
 * <h2>Identifiers are split, and also kept whole</h2>
 *
 * {@code AbstractDatabaseManager} is indexed as itself AND as abstract/database/manager. Splitting
 * only would lose the exact-name search that a maintainer actually types; keeping only the whole
 * token would mean a search for "database manager" never finds it.
 */
public final class TextIndex {

    /**
     * Words carrying no signal in a bug tracker.
     *
     * <p>Short and deliberately conservative. An over-eager stop list is how a search for a real
     * term silently returns nothing, and "error", "fail" and "null" are load-bearing here even
     * though they are everywhere.
     */
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "have", "has", "was", "are", "but",
            "not", "you", "your", "all", "can", "will", "would", "should", "when", "then", "than",
            "there", "here", "what", "which", "into", "over", "under", "about", "just", "like",
            "some", "any", "its", "it's", "our", "out", "get", "got", "how", "why", "does", "did");

    /** One indexed document: what it is, and what it is made of. */
    public record Doc(String id, String title, double[] weights, Map<String, Double> terms) {}

    private final Map<String, List<String>> postings = new HashMap<>(); // term -> doc ids
    private final Map<String, Map<String, Double>> vectors = new HashMap<>(); // doc id -> term -> tf-idf
    private final Map<String, String> titles = new HashMap<>();
    private final Map<String, Double> idf = new HashMap<>();

    /** Tokenise: lowercase words, identifiers kept whole AND split on case/underscore boundaries. */
    static List<String> tokenise(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("[^A-Za-z0-9_.]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            String whole = raw.toLowerCase(Locale.ROOT);
            if (whole.length() > 2 && !STOP.contains(whole)) {
                out.add(whole);
            }
            // Split CamelCase, snake_case and dotted names into their parts as well, so
            // "database manager" can reach AbstractDatabaseManager.
            for (String part : raw.split("(?<=[a-z0-9])(?=[A-Z])|[_.]+")) {
                String p = part.toLowerCase(Locale.ROOT);
                if (p.length() > 2 && !p.equals(whole) && !STOP.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Add one document. Title is weighted more heavily than body, by counting it twice. */
    public void add(String id, String title, String body) {
        Map<String, Integer> counts = new HashMap<>();
        // A term in the title is a stronger claim about what a thing is ABOUT than the same term
        // buried in a stack trace, and counting it twice is the cheapest way to say so.
        for (String t : tokenise(title)) {
            counts.merge(t, 2, Integer::sum);
        }
        for (String t : tokenise(body)) {
            counts.merge(t, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return;
        }
        titles.put(id, title == null ? "" : title);
        Map<String, Double> tf = new HashMap<>();
        double total = counts.values().stream().mapToInt(Integer::intValue).sum();
        for (var e : counts.entrySet()) {
            tf.put(e.getKey(), e.getValue() / total);
            postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(id);
        }
        vectors.put(id, tf);
    }

    /** Compute IDF and fold it into every document vector. Call once, after adding everything. */
    public void build() {
        int n = Math.max(1, vectors.size());
        for (var e : postings.entrySet()) {
            // Smoothed, so a term present in every document scores near zero rather than exactly
            // zero -- a hard zero would make a search for a very common word return nothing at all
            // instead of returning everything weakly, which is the more honest answer.
            idf.put(e.getKey(), Math.log(1.0 + (double) n / (1 + e.getValue().size())));
        }
        for (Map<String, Double> vec : vectors.values()) {
            vec.replaceAll((term, tf) -> tf * idf.getOrDefault(term, 0.0));
        }
    }

    /** Top matches for a query, best first. Empty when nothing shares a meaningful term. */
    public List<Hit> search(String query, int limit) {
        Map<String, Double> q = new HashMap<>();
        for (String t : tokenise(query)) {
            q.merge(t, 1.0, Double::sum);
        }
        q.replaceAll((term, tf) -> tf * idf.getOrDefault(term, 0.0));
        double qNorm = norm(q);
        if (qNorm == 0) {
            return List.of();
        }

        // Only documents sharing at least one query term are scored. On a corpus of any size this
        // is the difference between instant and noticeable, and it cannot change the ranking:
        // anything sharing no term scores zero by definition.
        Set<String> candidates = new HashSet<>();
        for (String term : q.keySet()) {
            candidates.addAll(postings.getOrDefault(term, List.of()));
        }

        List<Hit> hits = new ArrayList<>();
        for (String id : candidates) {
            Map<String, Double> v = vectors.get(id);
            double dot = 0;
            for (var e : q.entrySet()) {
                Double w = v.get(e.getKey());
                if (w != null) {
                    dot += w * e.getValue();
                }
            }
            double sim = dot / (qNorm * norm(v));
            if (sim > 0) {
                hits.add(new Hit(id, titles.getOrDefault(id, ""), sim));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private static double norm(Map<String, Double> v) {
        double sum = 0;
        for (double d : v.values()) {
            sum += d * d;
        }
        return Math.max(Math.sqrt(sum), 1e-9);
    }

    public int size() {
        return vectors.size();
    }

    public record Hit(String id, String title, double score) {}
}
