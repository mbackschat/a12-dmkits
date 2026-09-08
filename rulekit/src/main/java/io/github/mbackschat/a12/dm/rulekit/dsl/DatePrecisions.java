package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a declared {@code datePrecision} GUARANTEES about a date value's components.
 *
 * <p>Kept separate from {@link TemporalFormats} on purpose: that class classifies a format STRING — which
 * components a value is written with — while this classifies a precision, which says which of those written
 * components a value is allowed to leave unknown. A field passes or fails the two independently, and the
 * kernel gates them with different codes.
 *
 * <p>The rule is the measured one: a component is guaranteed iff the precision does not admit its omission
 * ({@code validate.laws.DateExtractionLawsTest}). {@code DAY_OPTIONAL} guarantees the year and the month,
 * {@code MONTH_OPTIONAL} the year alone, {@code YEAR_OPTIONAL} nothing at all.
 */
final class DatePrecisions {

    private DatePrecisions() {
    }

    /** The three components a precision can speak about; a {@code DateType} carries no others. */
    private static final Set<TemporalFormats.Component> DATE_TRIPLE = EnumSet.of(
            TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH, TemporalFormats.Component.DAY);

    /** The precision tokens this class has a measured rule for. */
    private static final Set<String> KNOWN = Set.of("DAY_OPTIONAL", "MONTH_OPTIONAL", "YEAR_OPTIONAL");

    /**
     * The components the precision guarantees are present. An unrecognised or absent declaration yields the
     * full triple: absence means FULL (A12 omits the key for the default), and a string outside the measured
     * vocabulary has no measured rule.
     *
     * <p>The full triple is the safe answer only for a gate that refuses a PARTLY-KNOWN operand — it makes
     * that gate decline. A gate refusing the opposite must consult {@link #isRecognised} first, or an unknown
     * token becomes a refusal on no measured basis.
     */
    static Set<TemporalFormats.Component> guaranteedComponents(String precision) {
        String normalised = precision == null ? "" : precision.trim().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "DAY_OPTIONAL" -> EnumSet.of(TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH);
            case "MONTH_OPTIONAL" -> EnumSet.of(TemporalFormats.Component.YEAR);
            case "YEAR_OPTIONAL" -> EnumSet.noneOf(TemporalFormats.Component.class);
            default -> EnumSet.copyOf(DATE_TRIPLE);
        };
    }

    /**
     * Whether the declaration makes any date component optional. The two sibling gates read this in opposite
     * directions — the extraction family and the entity-list aggregates refuse a partly-known operand, while
     * {@code ValueAsDate} requires one — so both go through this one predicate.
     */
    static boolean isPartiallyKnown(String precision) {
        return !guaranteedComponents(precision).containsAll(DATE_TRIPLE);
    }

    /**
     * Whether this declaration is one the measured vocabulary covers. Absence counts: A12 omits the key for
     * the default and FULL is what that means. A token outside the vocabulary does not, so a gate that
     * REQUIRES a partly-known operand can delegate rather than refuse on a rule nothing measured — the
     * asymmetry {@link #guaranteedComponents} cannot express, since its safe default is the refusing
     * direction's.
     */
    static boolean isRecognised(String precision) {
        return precision == null || precision.isBlank()
                || KNOWN.contains(precision.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Whether this precision has anything to say about {@code component}. Only the date triple is measured; an
     * hour/minute/second extraction over a date field is refused by the format gate, and a precision verdict
     * on it would state a rule nothing measured.
     */
    static boolean speaksAbout(TemporalFormats.Component component) {
        return DATE_TRIPLE.contains(component);
    }
}
