package io.github.mbackschat.a12.dm.cli;

import java.util.Set;

/**
 * Edit-distance "did you mean?" — the closest valid token to a likely typo. Shared by the spec/arg key
 * validators ({@link SpecKeys}, {@link ApplyCommand}) so a corrective suggestion is computed one way.
 */
public final class Suggestions {

    private Suggestions() {
    }

    /** The closest valid key to {@code key} by edit distance, if reasonably near (else null — no false suggestion). */
    public static String nearest(String key, Set<String> valid) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String v : valid) {
            int d = levenshtein(key, v);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return (best != null && bestD <= Math.max(2, best.length() / 2)) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
