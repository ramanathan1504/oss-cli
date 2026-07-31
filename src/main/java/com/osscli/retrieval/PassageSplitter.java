package com.osscli.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a note into overlapping passages small enough for an embedding model to read whole.
 *
 * <p>Embedding models take a few hundred tokens. Feeding one a document longer than that does not
 * fail -- it silently truncates, so the resulting vector describes the document's opening and
 * nothing else. With a median note of ~11k characters, that made most of the corpus unreachable by
 * search: a note whose answer sat in the middle could not be found by asking about that answer.
 *
 * <p>Two details matter. Passages OVERLAP, because a fact split across a boundary would otherwise
 * appear in neither passage intact. And boundaries prefer paragraph breaks, then line breaks, then
 * sentence ends, so passages tend to begin and end at natural seams rather than mid-word.
 */
public final class PassageSplitter {

    /** Comfortably inside the input window of common embedding models. */
    public static final int DEFAULT_PASSAGE_CHARS = 1500;

    /** Enough to carry a sentence or two across a boundary. */
    public static final int DEFAULT_OVERLAP_CHARS = 200;

    private PassageSplitter() {}

    public static List<String> split(String text) {
        return split(text, DEFAULT_PASSAGE_CHARS, DEFAULT_OVERLAP_CHARS);
    }

    public static List<String> split(String text, int passageChars, int overlapChars) {
        List<String> passages = new ArrayList<>();
        if (text == null) {
            return passages;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return passages;
        }
        if (s.length() <= passageChars) {
            passages.add(s);
            return passages;
        }
        if (overlapChars >= passageChars) {
            overlapChars = passageChars / 4; // never let the window fail to advance
        }

        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + passageChars, s.length());
            if (end < s.length()) {
                end = preferredBreak(s, start, end);
            }
            String piece = s.substring(start, end).strip();
            if (!piece.isEmpty()) {
                passages.add(piece);
            }
            if (end >= s.length()) {
                break;
            }
            // Step forward by at least one character even in pathological input,
            // so a bad break can never spin this loop forever.
            start = Math.max(start + 1, end - overlapChars);
        }
        return passages;
    }

    /**
     * Finds a natural boundary in the last quarter of the window, falling back to the hard cut when
     * the text has no seam there (minified JSON, a base64 blob, a giant single line).
     */
    private static int preferredBreak(String s, int start, int hardEnd) {
        int floor = start + (hardEnd - start) * 3 / 4;
        for (String seam : new String[] {"\n\n", "\n", ". "}) {
            int idx = s.lastIndexOf(seam, hardEnd);
            if (idx > floor) {
                return idx + seam.length();
            }
        }
        return hardEnd;
    }
}
