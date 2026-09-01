package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * Which component set a declared temporal format carries — the question A12 actually asks about a temporal
 * field, since its format vocabulary is kind-independent and everything below the declaration reads the format
 * rather than the kind (KERNEL-FINDINGS KF211: over the complete 8-format × 3-kind grid, all 24 cells are
 * model-legal and every format behaves identically on DATE, TIME and DATE_TIME).
 *
 * <p>Deliberately a few lines of letter scanning rather than a shared utility: the interpreter owns an
 * equivalent classifier, but it is a kernel-free Kotlin-Multiplatform module this JVM kit does not and must not
 * depend on. The two are locked independently against the same admitted vocabulary.
 */
final class TemporalFormats {

    /** The classifier a declaration selects. {@link #DATE} also covers the component-omitting date formats. */
    enum Shape { DATE, TIME, DATE_TIME }

    private TemporalFormats() {
    }

    /**
     * The shape of {@code format}, read off its component letters — {@code y}/{@code M}/{@code d} are date
     * components and {@code H}/{@code m}/{@code s} time ones, with a single-quoted section (the {@code 'T'} of
     * the ISO datetime) skipped as a literal. A format carrying no recognizable component is {@link Shape#DATE},
     * which is also where an unrecognizable declaration lands: the kernel refuses it at the model level, so no
     * shape claim about it is load-bearing.
     */
    static Shape shapeOf(String format) {
        boolean date = false;
        boolean time = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\'') {                                  // skip a quoted literal section
                i++;
                while (i < format.length() && format.charAt(i) != '\'') {
                    i++;
                }
                continue;
            }
            if (c == 'y' || c == 'M' || c == 'd') {
                date = true;
            } else if (c == 'H' || c == 'm' || c == 's') {
                time = true;
            }
        }
        if (date && time) {
            return Shape.DATE_TIME;
        }
        return time ? Shape.TIME : Shape.DATE;
    }

    /** The format a temporal field gets when it declares none — its kind's own default, not a restriction. */
    static String defaultFormat(ValueKind kind) {
        return switch (kind) {
            case TIME -> "HH:mm:ss";
            case DATE_TIME -> "yyyy-MM-dd'T'HH:mm:ss";
            default -> "yyyy-MM-dd";
        };
    }

    /** Whether a temporal kind can carry a declared format at all — the kinds the format vocabulary spans. */
    static boolean isTemporal(ValueKind kind) {
        return kind == ValueKind.DATE || kind == ValueKind.TIME || kind == ValueKind.DATE_TIME;
    }
}
