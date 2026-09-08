package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * Which family of temporal value a declared {@code format} string accepts — the kernel's actual gate on a
 * computation's constant or temporal operation, which reads the format and never the field's declared kind
 * (KERNEL-FINDINGS {@code KF222}). A DATE-kind field declared {@code HH:mm:ss} takes a clock value and refuses
 * a date one; a TIME-kind field declared {@code yyyy-MM-dd} inverts both.
 *
 * <p><b>The classification is measured, not inferred.</b> Every one of the twelve format strings the kernel
 * admits ({@code docs/TEMPORAL-FORMAT-VOCABULARY.md}, derived and guard-locked) was offered all three constant
 * families, and the map below is what came back — {@code adapter.laws.ConstantTargetFormatLawsTest}
 * {@code .everyAdmittedFormatAdmitsExactlyOneConstantFamily}. Reading the components rather than matching the
 * twelve strings is deliberate: a kernel upgrade that widens the vocabulary classifies correctly without an
 * edit here, and the sweep re-derives the whole map against the kernel so a widening that broke the reading
 * would fail the build rather than pass silently.
 *
 * <p>The datetime row is the one a component-COUNTING reading gets wrong. {@code yyyy-MM-dd'T'HH:mm:ss}
 * contains the clock pattern, so "has clock components" would classify it as clock-accepting; measured, it
 * refuses a bare clock constant and takes only the datetime spelling. Presence of BOTH component sets is what
 * makes a format datetime, which is why this reads as a three-way split and not as two flags.
 */
enum TemporalFormatFamily {

    /** Takes a German date constant (`05.03.2024`) — every admitted format carrying only date components. */
    DATE,

    /** Takes a clock constant (`12:30:00`) — the single admitted clock-only format, `HH:mm:ss`. */
    CLOCK,

    /** Takes the datetime spelling (`05.03.2024T12:30:00`) — a format carrying both component sets. */
    DATE_TIME;

    private static final String DATE_COMPONENTS = "yMdD";
    private static final String CLOCK_COMPONENTS = "Hhms";

    /**
     * The family of a declared format string, or {@code null} when it carries no temporal component at all
     * (so nothing about it is known and the caller must not refuse on it).
     *
     * <p>Text inside the grammar's single quotes is a LITERAL, not a component — the {@code 'T'} separator of
     * the datetime format is the live case, and reading it as a component would misclassify the one format
     * whose classification is least obvious.
     */
    static TemporalFormatFamily of(String format) {
        if (format == null || format.isEmpty()) {
            return null;
        }
        String components = withoutQuotedLiterals(format);
        boolean date = components.chars().anyMatch(c -> DATE_COMPONENTS.indexOf(c) >= 0);
        boolean clock = components.chars().anyMatch(c -> CLOCK_COMPONENTS.indexOf(c) >= 0);
        if (date && clock) {
            return DATE_TIME;
        }
        if (clock) {
            return CLOCK;
        }
        return date ? DATE : null;
    }

    /**
     * The family a temporal {@link ValueKind} means when the field declares NO format and so takes its kind's
     * default; {@code null} for a kind this gate does not govern.
     *
     * <p>{@code DATE_RANGE} is deliberately absent. Its declaration is a {@code (format, separator)} pair
     * rather than a bare format, and no measurement here covers assigning across it, so it keeps the plain
     * kind-equality check instead of being folded in on the strength of the others.
     */
    static TemporalFormatFamily ofDefaultFor(ValueKind kind) {
        return switch (kind) {
            case DATE, DATE_FRAGMENT -> DATE;
            case TIME -> CLOCK;
            case DATE_TIME -> DATE_TIME;
            default -> null;
        };
    }

    /** Erases `'…'` literal sections so their letters are not read as format components. */
    private static String withoutQuotedLiterals(String format) {
        StringBuilder out = new StringBuilder(format.length());
        boolean inLiteral = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
