package com.osscli;

/**
 * Fallback values used when configuration has not been set yet.
 *
 * <p>These exist in one place because they used to exist in five. The guidance model was written
 * as a literal in the schema seed and again in four commands, so changing it meant finding all of
 * them and the copies drifted apart in the meantime.
 *
 * <p>Nothing personal belongs here. A seeded default ships to every machine that installs this, so
 * a value that made sense on the author's laptop becomes a value a stranger inherits without ever
 * choosing it. The GitHub username used to be seeded that way -- every fresh database on every
 * machine came preloaded with one person's account, and {@code sync --me} would have harvested
 * their contribution history onto somebody else's laptop. Identity is now left blank and asked for
 * during {@code setup}.
 */
public final class Defaults {

    /**
     * A small, instruction-tuned model chosen so a first run has a chance of working unattended:
     * roughly 2 GB, widely pulled, and NOT a reasoning model -- reasoning models return their
     * output in a separate field, which reads here as an empty answer and escalates every time.
     *
     * <p>It is a starting point, not a recommendation. The right model depends on the machine and
     * the work; {@code setup} asks, and {@code doctor} reports what is actually installed.
     */
    public static final String GUIDANCE_MODEL = "llama3.2:3b";

    /** Small enough to run anywhere, only ever used for cheap high-volume classification. */
    public static final String TRIAGE_MODEL = "qwen2.5:0.5b";

    /**
     * Embeddings matter more than the chat model: no corpus fits in a context window, so retrieval
     * quality sets the ceiling and a larger chat model cannot recover text the embedder never read.
     * This one is tiny and fast; larger embedders retrieve better if you can afford them.
     */
    public static final String EMBEDDING_MODEL = "all-minilm";

    public static final String OLLAMA_URL = "http://localhost:11434";

    private Defaults() {}
}
