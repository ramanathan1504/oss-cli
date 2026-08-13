package com.osscli.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tokenizer all-MiniLM-L6-v2 was trained with.
 *
 * <p>This has to match the training tokenizer exactly. It is the one part of running a model
 * locally that fails <em>quietly</em>: wrong token ids produce a perfectly well-formed vector of the
 * right shape, cosine similarity still returns plausible numbers, and the results are simply subtly
 * wrong forever. Nothing crashes and no error appears — which is why this is written out rather than
 * approximated.
 *
 * <p>BERT uncased, so: lowercase, strip accents, split on punctuation, then greedy longest-match
 * WordPiece with {@code ##} continuations. Unknown pieces become {@code [UNK]} rather than being
 * dropped, because dropping them shortens the sequence and shifts everything after it.
 */
final class WordPiece {

    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final String UNK = "[UNK]";
    private static final String PAD = "[PAD]";

    /** 256 rather than the model's 512: titles and bodies are short, and attention is quadratic. */
    static final int MAX_TOKENS = 256;

    private final Map<String, Integer> vocab;

    WordPiece(Path vocabFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabFile);
        Map<String, Integer> v = new HashMap<>(lines.size() * 2);
        for (int i = 0; i < lines.size(); i++) {
            v.put(lines.get(i).trim(), i);
        }
        this.vocab = v;
        for (String required : new String[] {CLS, SEP, UNK, PAD}) {
            if (!vocab.containsKey(required)) {
                throw new IOException("vocab is missing " + required + " — wrong file for this model");
            }
        }
    }

    /** Token ids for one text, already wrapped in [CLS]/[SEP] and padded to {@link #MAX_TOKENS}. */
    Encoded encode(String text) {
        List<Integer> ids = new ArrayList<>(MAX_TOKENS);
        ids.add(vocab.get(CLS));

        for (String word : basicTokenise(text)) {
            if (ids.size() >= MAX_TOKENS - 1) {
                break; // leave room for [SEP]
            }
            for (int id : wordPiece(word)) {
                if (ids.size() >= MAX_TOKENS - 1) {
                    break;
                }
                ids.add(id);
            }
        }
        ids.add(vocab.get(SEP));

        long[] inputIds = new long[MAX_TOKENS];
        long[] mask = new long[MAX_TOKENS];
        int pad = vocab.get(PAD);
        for (int i = 0; i < MAX_TOKENS; i++) {
            boolean real = i < ids.size();
            inputIds[i] = real ? ids.get(i) : pad;
            // The mask is what stops padding contributing to the mean. Getting it wrong dilutes
            // every short text towards the same vector, which looks like "everything is similar".
            mask[i] = real ? 1 : 0;
        }
        return new Encoded(inputIds, mask);
    }

    /** Lowercase, strip accents, and separate punctuation into its own tokens. */
    private static List<String> basicTokenise(String text) {
        String normalised = java.text.Normalizer.normalize(
                        text == null ? "" : text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : normalised.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
            } else {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (!Character.isWhitespace(ch)) {
                    out.add(String.valueOf(ch));
                }
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** Greedy longest-match-first, with {@code ##} on every piece after the first. */
    private List<Integer> wordPiece(String word) {
        List<Integer> out = new ArrayList<>();
        if (word.length() > 100) {
            out.add(vocab.get(UNK));
            return out;
        }
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String piece = (start == 0 ? "" : "##") + word.substring(start, end);
                Integer id = vocab.get(piece);
                if (id != null) {
                    found = id;
                    break;
                }
                end--;
            }
            if (found == null) {
                // The whole word is unknown. Emitting one [UNK] for it, rather than per character,
                // is what BERT does -- and length matters, because it shifts every later position.
                out.clear();
                out.add(vocab.get(UNK));
                return out;
            }
            out.add(found);
            start = end;
        }
        return out;
    }

    record Encoded(long[] inputIds, long[] attentionMask) {}
}
