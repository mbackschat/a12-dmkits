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

    /**
     * One component a declared format can carry, named by its format letter. This is the granularity the
     * kernel's operand gates actually read — {@link Shape} is deliberately coarser and cannot express them,
     * since it calls {@code yyyy-MM} and {@code yyyy-MM-dd} both {@link Shape#DATE} while the kernel refuses
     * {@code DayFromDate} on the first and admits it on the second (KERNEL-FINDINGS KF1001).
     */
    enum Component {
        YEAR('y'), MONTH('M'), DAY('d'), HOUR('H'), MINUTE('m'), SECOND('s');

        private final char letter;

        Component(char letter) {
            this.letter = letter;
        }

        static Component of(char letter) {
            for (Component component : values()) {
                if (component.letter == letter) {
                    return component;
                }
            }
            return null;
        }
    }

    private TemporalFormats() {
    }

    /**
     * The components {@code format} declares, read off its letters with a single-quoted section skipped as a
     * literal — so the {@code 'T'} of the ISO datetime names nothing. Case-sensitive, because {@code M} is the
     * month and {@code m} the minute.
     */
    static java.util.Set<Component> componentsOf(String format) {
        java.util.EnumSet<Component> components = java.util.EnumSet.noneOf(Component.class);
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\'') {                                  // skip a quoted literal section
                i++;
                while (i < format.length() && format.charAt(i) != '\'') {
                    i++;
                }
                continue;
            }
            Component component = Component.of(c);
            if (component != null) {
                components.add(component);
            }
        }
        return components;
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

    /**
     * Whether {@code format} carries a date component AND a time component — the split family's requirement,
     * which over the admitted vocabulary only the ISO date-time meets (KF1001). Stated as one-of-each rather
     * than as two named components, because that is what was measured.
     */
    static boolean carriesBothHalves(String format) {
        java.util.Set<Component> carried = componentsOf(format);
        boolean date = carried.contains(Component.YEAR) || carried.contains(Component.MONTH)
                || carried.contains(Component.DAY);
        boolean time = carried.contains(Component.HOUR) || carried.contains(Component.MINUTE)
                || carried.contains(Component.SECOND);
        return date && time;
    }

    /**
     * The format a temporal field gets when it declares none — its kind's own default, not a restriction.
     * Measured: a field declaring no format answers exactly what these components allow (KF1001).
     */
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

    /**
     * The kinds whose extraction operands the component rule decides — {@link #isTemporal}'s three plus
     * {@link ValueKind#DATE_FRAGMENT}, since the kernel gates a fragment the same way (a {@code yyyy} fragment
     * answers {@code YearFromDate} and is refused {@code MonthFromDate}, KF1001).
     *
     * <p>Separate from {@link #isTemporal} rather than folded into it: that predicate's caller substitutes one
     * kind's typed handle for another when the declared SHAPE matches, and whether a fragment may stand in for
     * a date that way is a different question, unmeasured. {@link ValueKind#DATE_RANGE} is in neither — a span
     * carries no component to extract, and the family reports {@code MVK_NO_DATE} for it.
     */
    static boolean readsComponents(ValueKind kind) {
        return isTemporal(kind) || kind == ValueKind.DATE_FRAGMENT;
    }
}
