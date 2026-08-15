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
        c.embedder = Embeddings.ifPresent(onProgress);
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

    /**
     * The cosine below which a match is not an answer.
     *
     * <p>There was no floor at all, and with a small corpus that is indistinguishable from having no
     * search. Asked for "keyspace" against six notes, this ranked all six and presented the top
     * three — at 0.10, 0.09 and 0.08 — in the same shape as a real hit. None of them was about
     * keyspaces; they were simply the least unrelated things on disk. Every nearest-neighbour search
     * has a nearest neighbour, so without a floor the answer to a question the corpus cannot answer
     * is noise formatted as knowledge.
     *
     * <p>0.25 is set from the observed spread: real subject matches on this corpus land at 0.35 and
     * above, and everything under 0.2 has been unrelated. Overridable, because the right floor
     * depends on how much a corpus holds.
     */
    public static final double RELEVANCE_FLOOR = 0.25;

    private static double floor() {
        try {
            String configured = com.osscli.storage.SqliteStorage.loadConfig("search.relevance_floor");
            if (configured != null && !configured.isBlank()) {
                return Double.parseDouble(configured.trim());
            }
        } catch (Exception e) {
            // An unreadable or misconfigured floor must not silently become 0 and bring the noise
            // back. Falling back to the default is the safe direction here.
            return RELEVANCE_FLOOR;
        }
        return RELEVANCE_FLOOR;
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
        double floor = floor();
        List<Hit> out = new ArrayList<>();
        for (Doc d : docs) {
            double[] v = vectorFor(d);
            // A length mismatch means two different models, whose vectors share no axes. Comparing
            // the overlapping prefix produced a number rather than an error, and a plausible number
            // with no basis is worse than no answer: it ranks, so it looks like it worked.
            if (v == null || v.length != q.length) {
                continue;
            }
            // Both vectors are L2-normalised by the embedder, so the dot product IS the cosine.
            double dot = 0;
            for (int i = 0; i < v.length; i++) {
                dot += q[i] * v[i];
            }
            // Weighted before the comparison, so a document promoted by its kind is judged on the
            // score it will actually be shown with rather than on a raw cosine nobody sees.
            double score = dot * weightFactor(d);
            if (score < floor) {
                continue;
            }
            out.add(new Hit(d.id(), d.title(), d.kind(), score, true));
        }
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * The vector for a document, computed once and kept.
     *
     * <p>Keyed by a digest of the content, so editing a note re-embeds it and leaving it alone does
     * not. A cache keyed by filename would go quietly stale, which is worse than no cache.
     *
     * <p>The digest is SHA-256 and not {@link String#hashCode()}. A 32-bit hash over a corpus of
     * notes collides, and a collision here does not corrupt anything visibly -- it silently hands
     * back another note's vector, so the wrong document ranks first and nothing anywhere reports a
     * fault. Two notes are far more alike than two random strings, which is exactly the input a
     * 32-bit hash is worst at.
     *
     * <p>Each entry names the model and dimension that produced it. Vectors from different models
     * are not comparable, and an entry left behind by a previous model is indistinguishable from a
     * current one without saying so.
     */
    private double[] vectorFor(Doc d) throws IOException {
        Path f = VECTORS.resolve(digest(d.body()) + ".vec");
        if (Files.isRegularFile(f)) {
            double[] cached = readCached(f);
            if (cached != null) {
                return cached;
            }
        }
        double[] v = embedder.embed(d.body());
        StringBuilder sb = new StringBuilder();
        sb.append(Embeddings.MODEL).append(' ').append(v.length).append('\n');
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(v[i]);
        }
        Files.createDirectories(VECTORS);
        Files.writeString(f, sb.toString());
        return v;
    }

    /** A cached vector, or null when it is corrupt, stale or from another model -- all of which mean "recompute". */
    private static double[] readCached(Path f) {
        try {
            String body = Files.readString(f);
            int nl = body.indexOf('\n');
            if (nl < 0) {
                // Written before entries carried provenance. Unreadable by definition: it cannot say
                // which model produced it, so it cannot be trusted to be comparable with a new one.
                return null;
            }
            String[] head = body.substring(0, nl).trim().split(" ");
            if (head.length != 2 || !Embeddings.MODEL.equals(head[0])) {
                return null;
            }
            String[] parts = body.substring(nl + 1).trim().split(",");
            if (parts.length != Integer.parseInt(head[1]) || parts.length != Embeddings.DIMENSIONS) {
                return null;
            }
            double[] v = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Double.parseDouble(parts[i]);
            }
            return v;
        } catch (IOException | RuntimeException e) {
            // A corrupt cache entry is not worth failing a search over; recompute it.
            return null;
        }
    }

    /** Content digest, truncated to 40 hex characters -- 160 bits, which will not collide over a note corpus. */
    private static String digest(String body) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 40);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java runtime; if it is absent the platform is not one.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
