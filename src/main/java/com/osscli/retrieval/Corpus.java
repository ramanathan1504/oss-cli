package com.osscli.retrieval;

import com.osscli.AppPaths;
import com.osscli.memory.BuiltinMemory;
import com.osscli.review.ReviewLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything you have written, searchable, in one place.
 *
 * <p>Notes and review write-ups were being indexed separately by whichever command happened to need
 * them, each with its own idea of what counted and how much. That is how two commands come to
 * disagree about your own corpus — and it is the same mistake that produced two follow-ups with
 * different flags, one layer down.
 *
 * <p>So there is one corpus and every command asks it. It answers by meaning when the local model
 * is present and by shared terms when it is not, and it says which. That ordering matters: term
 * search is the floor, not the fallback of last resort. Someone with no model at all still gets
 * useful answers out of their own writing, which is the promise the whole tool rests on.
 *
 * <p>Embeddings are cached by content hash under {@code ~/.oss-cli/vectors/}. Re-embedding an
 * unchanged note on every command would make the model a tax on using the tool rather than a
 * benefit of having it.
 */
public final class Corpus {

    private static final Path VECTORS = AppPaths.BASE_DIR.resolve("vectors");

    /** Reviewing something means you read it; filing a note means you thought it worth keeping. */
    private static final int WEIGHT_REVIEW = 4;

    private final List<Doc> docs = new ArrayList<>();

    private final TextIndex text = new TextIndex();

    private LocalEmbedder embedder;

    private boolean built;

    /** One thing you wrote. */
    public record Doc(String id, String title, String body, String kind, int weight) {}

    /** One answer, with enough to say why it came back. */
    public record Hit(String id, String title, String kind, double score, boolean semantic) {}

    /**
     * Load everything: your notes, and the reviews you have written.
     *
     * @param onProgress told what is happening — loading a model is slow enough to need saying
     */
    public static Corpus load(java.util.function.Consumer<String> onProgress) throws IOException {
        Corpus c = new Corpus();

        for (ReviewLedger.Row r : ReviewLedger.read()) {
            Path up = ReviewLedger.writeUp(r.pr);
            String body = up == null ? r.note : Files.readString(up);
            if (body == null || body.isBlank()) {
                continue;
            }
            String label = r.repo + "#" + r.pr + (r.note.isBlank() ? "" : " — " + r.note);
            c.docs.add(new Doc("review:" + r.repo + "#" + r.pr, label, body, "review", WEIGHT_REVIEW));
        }

        if (Files.isDirectory(BuiltinMemory.DIR)) {
            try (Stream<Path> s = Files.list(BuiltinMemory.DIR)) {
                for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .toList()) {
                    String body = Files.readString(p);
                    c.docs.add(new Doc(
                            "note:" + p.getFileName(),
                            title(body, p.getFileName().toString()),
                            body,
                            "note",
                            1));
                }
            }
        }

        // The model is optional and asked for by presence, never downloaded behind your back: a
        // command that quietly pulls 22 MB the first time it runs is a command people stop trusting.
        if (LocalEmbedder.isDownloaded()) {
            try {
                c.embedder = LocalEmbedder.load(onProgress);
            } catch (Exception e) {
                onProgress.accept("model present but would not load; using term search");
            }
        }
        return c;
    }

    /** True when answers will be by meaning rather than by shared terms. */
    public boolean semantic() {
        return embedder != null;
    }

    public int size() {
        return docs.size();
    }

    /** What this corpus is made of, for a one-line explanation to the person asking. */
    public Map<String, Integer> composition() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Doc d : docs) {
            m.merge(d.kind(), 1, Integer::sum);
        }
        return m;
    }

    public List<Hit> search(String query, int limit) throws IOException {
        if (docs.isEmpty()) {
            return List.of();
        }
        return embedder != null ? bySimilarity(query, limit) : byTerms(query, limit);
    }

    // ------------------------------------------------------------------ meaning ---

    private List<Hit> bySimilarity(String query, int limit) throws IOException {
        double[] q = embedder.embed(query);
        List<Hit> out = new ArrayList<>();
        for (Doc d : docs) {
            double[] v = vectorFor(d);
            if (v == null) {
                continue;
            }
            // Both vectors are L2-normalised by the embedder, so the dot product IS the cosine.
            double dot = 0;
            for (int i = 0; i < Math.min(q.length, v.length); i++) {
                dot += q[i] * v[i];
            }
            out.add(new Hit(d.id(), d.title(), d.kind(), dot * weightFactor(d), true));
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * The vector for a document, computed once and kept.
     *
     * <p>Keyed by a hash of the content, so editing a note re-embeds it and leaving it alone does
     * not. A cache keyed by filename would go quietly stale, which is worse than no cache.
     */
    private double[] vectorFor(Doc d) throws IOException {
        Path f = VECTORS.resolve(Integer.toHexString(d.body().hashCode()) + ".vec");
        if (Files.isRegularFile(f)) {
            try {
                String[] parts = Files.readString(f).trim().split(",");
                double[] v = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    v[i] = Double.parseDouble(parts[i]);
                }
                return v;
            } catch (RuntimeException e) {
                // A corrupt cache entry is not worth failing a search over; recompute it.
            }
        }
        double[] v = embedder.embed(d.body());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        Files.createDirectories(VECTORS);
        Files.writeString(f, sb.toString());
        return v;
    }

    // -------------------------------------------------------------------- terms ---

    private List<Hit> byTerms(String query, int limit) {
        if (!built) {
            for (Doc d : docs) {
                // Repeating a document is how a TF-IDF index is told it counts for more.
                for (int w = 0; w < d.weight(); w++) {
                    text.add(d.id() + ":" + w, d.title(), d.body());
                }
            }
            text.build();
            built = true;
        }
        List<Hit> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TextIndex.Hit h : text.search(query, limit * 4)) {
            String id = h.id().substring(0, h.id().lastIndexOf(':'));
            if (!seen.add(id)) {
                continue;
            }
            out.add(new Hit(id, h.title(), kindOf(id), h.score(), false));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static String kindOf(String id) {
        int i = id.indexOf(':');
        return i < 0 ? "doc" : id.substring(0, i);
    }

    private double weightFactor(Doc d) {
        // Gentler than the term index's repetition: cosine similarity is already bounded, so a
        // multiplier of four would let one review outrank a genuinely better match every time.
        return 1.0 + (d.weight() - 1) * 0.05;
    }

    private static String title(String body, String fallback) {
        for (String line : body.split("\n", 40)) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return fallback.replaceAll("\\.md$", "").replace('-', ' ');
    }
}
