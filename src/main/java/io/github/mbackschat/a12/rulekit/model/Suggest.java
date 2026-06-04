package io.github.mbackschat.a12.rulekit.model;

import java.util.Collection;
import java.util.Optional;

/**
 * Nearest-name suggestions for the F4a "did you mean" diagnostics: given a fumbled name (a field
 * path, an enum value) and the valid candidates from the model index, return the closest by
 * Levenshtein distance — but only when it is close enough to be a plausible typo, so a wholly wrong
 * name draws no misleading hint.
 */
public final class Suggest {

    private Suggest() {
    }

    /** The closest candidate to {@code target} within a typo-plausible distance, if any. */
    public static Optional<String> nearest(String target, Collection<String> candidates) {
        if (target == null || target.isEmpty()) {
            return Optional.empty();
        }
        int threshold = Math.max(1, target.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(target)) {
                continue;
            }
            int distance = distance(target, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best != null && bestDistance <= threshold ? Optional.of(best) : Optional.empty();
    }

    /** A {@code " — did you mean 'X'?"} clause to append to an error message, or {@code ""} if nothing is close. */
    public static String hint(String target, Collection<String> candidates) {
        return nearest(target, candidates).map(s -> " — did you mean '" + s + "'?").orElse("");
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), substitution);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
