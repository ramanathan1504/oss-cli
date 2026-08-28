package com.osscli.retrieval;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.osscli.AppPaths;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Embeddings with no server, no daemon and no account.
 *
 * <p>Search by meaning previously required a running model server, so with none the whole of it
 * disappeared. This runs the same model — {@code all-MiniLM-L6-v2}, the one that server would have
 * served — inside this process instead.
 *
 * <p><b>Fetched on first use, not bundled.</b> The jar is 19 MB and most people will never embed
 * anything; putting 22 MB of weights inside it would tax everyone for a feature some use, and
 * contradict the claim that Java and a token are the whole requirement. Downloaded once into
 * {@link AppPaths#BASE_DIR}, it is then offline forever.
 *
 * <p>The quantized model is used deliberately: 22 MB against 86 MB, for a difference in retrieval
 * quality that does not show up when the job is ranking issues you already have.
 */
public final class LocalEmbedder implements AutoCloseable {

    /** Where weights live. Beside the database, never inside a clone. */
    private static final Path DIR = AppPaths.BASE_DIR.resolve("models").resolve("all-MiniLM-L6-v2");

    private static final Path MODEL = DIR.resolve("model.onnx");
    private static final Path VOCAB = DIR.resolve("vocab.txt");

    private static final String MODEL_URL =
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx";
    private static final String VOCAB_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt";

    /** all-MiniLM-L6-v2 produces 384 dimensions. Stated so a mismatch is caught, not averaged. */
    public static final int DIMENSIONS = 384;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPiece tokenizer;

    private LocalEmbedder(OrtEnvironment env, OrtSession session, WordPiece tokenizer) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
    }

    /** True once the weights are on disk, so callers can avoid a surprise download mid-command. */
    public static boolean isDownloaded() {
        return Files.isRegularFile(MODEL) && Files.isRegularFile(VOCAB);
    }

    /**
     * Load the model, downloading it the first time.
     *
     * @param onProgress told what is happening, because a silent 22 MB download looks like a hang
     */
    /**
     * How many threads the model may use.
     *
     * <p>Two, and the reason is not the obvious one. ONNX Runtime takes every core by default, and
     * an indexing run sat at 393% CPU on an eight-core laptop with the fan up -- so the first fix
     * was to cap the threads. Measured, that made it <em>worse on both axes</em>: the same work
     * took 22.9s instead of 8.9s and burned 33.2 CPU-seconds instead of 9.1.
     *
     * <p>The cost was spin-waiting, not parallelism. ONNX's pool spins before sleeping, so fewer
     * threads meant more waiting and the waiting was busy. Turning spinning off (below) costs
     * nothing and the numbers converge: 9.2 CPU-seconds on two threads, 9.1 on eight. Same energy,
     * a quarter of the cores, and a machine that stays usable while it runs.
     *
     * <p>So two is chosen for headroom rather than for power. Raise it with
     * {@code OSS_EMBED_THREADS} when indexing a large archive on mains power and the wait matters.
     */
    static int embedThreads() {
        String configured = System.getenv("OSS_EMBED_THREADS");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured.strip()));
            } catch (NumberFormatException e) {
                // A number that will not parse must not silently become "all of them".
            }
        }
        return Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Session options that leave the machine usable while this runs.
     *
     * <p>The spinning entry is the one that matters. With it on and the thread count reduced, this
     * burned 3.6x the CPU to do the same work -- the shape that empties a battery while looking
     * like a small job. {@code OSS_EMBED_SPIN=1} restores the library default.
     */
    static OrtSession.SessionOptions sessionOptions() throws ai.onnxruntime.OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(embedThreads());
        options.setInterOpNumThreads(1);
        if (!"1".equals(System.getenv("OSS_EMBED_SPIN"))) {
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
        }
        return options;
    }

    public static LocalEmbedder load(java.util.function.Consumer<String> onProgress) throws IOException {
        if (!isDownloaded()) {
            onProgress.accept("first use: fetching the embedding model (~22 MB), once");
            Files.createDirectories(DIR);
            download(MODEL_URL, MODEL, onProgress);
            download(VOCAB_URL, VOCAB, onProgress);
            onProgress.accept("model ready — this will not happen again");
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession session = env.createSession(MODEL.toString(), sessionOptions());
            return new LocalEmbedder(env, session, new WordPiece(VOCAB));
        } catch (Exception e) {
            throw new IOException(
                    "could not load the local model: " + e.getMessage()
                            + unsupportedPlatformNote(
                                    System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
                    e);
        }
    }

    /**
     * Why it may not load on this machine, when the machine is the reason.
     *
     * <p>onnxruntime 1.29 ships no {@code osx-x64} native. The 1.22 jar carried a 35.7 MB
     * {@code libonnxruntime.dylib} for Intel Macs; the 1.29 jar has none, so on that hardware the
     * loader fails with a message about a missing library, which reads as a broken install.
     *
     * <p>It is not broken and it is not fixable here — upstream dropped the platform. What the
     * reader needs is the difference between "something is wrong with my setup" and "this hardware
     * no longer has a build", because only one of those is worth an hour of their evening. Search
     * still works by term, which is the floor this tool is built on.
     */
    static String unsupportedPlatformNote(String osName, String arch) {
        boolean mac = osName.toLowerCase(java.util.Locale.ROOT).contains("mac");
        boolean intel = arch.equals("x86_64") || arch.equals("amd64");
        if (!mac || !intel) {
            return "";
        }
        return System.lineSeparator()
                + "       onnxruntime ships no Intel-Mac build after 1.22, so there is no library here"
                + System.lineSeparator()
                + "       to load. Nothing is misconfigured. Search still answers by term, and the"
                + System.lineSeparator()
                + "       cloud engines are unaffected: oss claude, oss gemini, oss codex.";
    }

    /** Download to a temporary file and move into place, so an interrupted fetch is never loaded. */
    private static void download(String url, Path target, java.util.function.Consumer<String> onProgress)
            throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(tmp) < 1024) {
            Files.deleteIfExists(tmp);
            throw new IOException("download from " + url + " was too small to be real");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        onProgress.accept("  " + target.getFileName() + "  " + (Files.size(target) / 1_048_576) + " MB");
    }

    /** One text to one unit-length vector. */
    public double[] embed(String text) throws IOException {
        WordPiece.Encoded enc = tokenizer.encode(text == null ? "" : text);
        int n = enc.inputIds().length;
        long[] shape = {1, n};

        try (OnnxTensor ids = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.inputIds()), shape);
                OnnxTensor mask = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask()), shape);
                OnnxTensor types = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[n]), shape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", ids);
            inputs.put("attention_mask", mask);
            // Some exports omit token_type_ids. Supplying it when unwanted is an error, so it is
            // only added when the model actually declares it.
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", types);
            }

            try (OrtSession.Result out = session.run(inputs)) {
                float[][][] hidden = (float[][][]) out.get(0).getValue();
                return meanPool(hidden[0], enc.attentionMask());
            }
        } catch (Exception e) {
            throw new IOException("embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Mean-pool over real tokens only, then L2-normalise.
     *
     * <p>Both halves matter. Averaging over padding pulls every short text toward the same vector,
     * which reads as "everything is similar to everything"; and without normalising, cosine
     * similarity is dominated by length rather than content.
     */
    private static double[] meanPool(float[][] hidden, long[] mask) {
        double[] sum = new double[hidden[0].length];
        int counted = 0;
        for (int t = 0; t < hidden.length && t < mask.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            counted++;
            for (int d = 0; d < sum.length; d++) {
                sum[d] += hidden[t][d];
            }
        }
        if (counted == 0) {
            return sum;
        }
        double norm = 0;
        for (int d = 0; d < sum.length; d++) {
            sum[d] /= counted;
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < sum.length; d++) {
                sum[d] /= norm;
            }
        }
        return sum;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // Closing a session that already failed to open is not worth reporting.
        }
    }
}
