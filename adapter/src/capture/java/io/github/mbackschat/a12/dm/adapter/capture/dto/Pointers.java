package io.github.mbackschat.a12.dm.adapter.capture.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE pointer rendering every runner's observation uses (CONFORMANCE-CORPUS-SPEC §5, re-implemented
 * neutrally so no runner's internal formatter leaks into the DTO boundary): split the entity path on
 * {@code /}, append {@code [n]} (1-based) to each NON-terminal segment whose index is known; the terminal
 * segment is never indexed; fewer indices than non-terminal segments leaves the tail bare (a partially
 * known pointer).
 */
public final class Pointers {

    private Pointers() {
    }

    /** Render {@code fullName} (e.g. {@code /Quote/Premium}) + one index per segment (the terminal's is
     *  accepted and ignored, per the placement convention) to the §5 pointer form. */
    public static String format(String fullName, List<Integer> indices) {
        String[] segments = fullName.substring(1).split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            out.append('/').append(segments[i]);
            if (i < segments.length - 1 && i < indices.size()) {
                out.append('[').append(indices.get(i)).append(']');
            }
        }
        return out.toString();
    }

    /** A parsed pointer: the bare name-path and the per-segment indices (terminal defaulted to 1 — the
     *  placement convention of one index per path part, the field included). */
    public record Parsed(String namePath, List<Integer> reps) {
    }

    /** Parse a §5 pointer (e.g. {@code /Quote[1]/Premium}) into its name-path + placement indices. */
    public static Parsed parse(String pointer) {
        String[] segments = pointer.substring(1).split("/");
        StringBuilder path = new StringBuilder();
        List<Integer> reps = new ArrayList<>();
        for (String segment : segments) {
            int bracket = segment.indexOf('[');
            if (bracket >= 0) {
                path.append('/').append(segment, 0, bracket);
                reps.add(Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1)));
            } else {
                path.append('/').append(segment);
                reps.add(1);
            }
        }
        return new Parsed(path.toString(), List.copyOf(reps));
    }
}
