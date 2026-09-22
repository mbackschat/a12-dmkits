package io.github.mbackschat.a12.dm.modelkit;

import java.util.Optional;

/**
 * A DATE_RANGE's {@code interpretationOfYear} (the kernel's {@code Jahresinterpretation}) — which endpoint of
 * a <b>wrapping yearless</b> range keeps the base year. It matters for exactly one shape: a range whose two
 * endpoints do not share a year, such as a winter cover window {@code 01.11-31.01} on a {@code dd.MM} range.
 *
 * <p>{@link #STANDARD} completes both endpoints with the base year, so a wrapping value is simply
 * start-after-end. The other two say which endpoint the base year <em>labels</em>, and the opposite endpoint
 * moves — the reverse of what the names suggest: under {@link #FROM} the base year is the from-year and the
 * END rolls forward one; under {@link #TO} it is the to-year and the START rolls back one. Only when the
 * value actually wraps.
 *
 * <p><b>{@code STANDARD} has no DM-JSON spelling — it is expressed by OMITTING the property.</b> The
 * declaration admits {@code FROM} and {@code TO} only; the tool API's {@code STANDARD}/{@code PREVIOUS}/
 * {@code SUBSEQUENT} tokens are all refused by the kernel's model deserializer (measured 2026-08-19,
 * {@code adapter.laws.WrappingYearlessRangeDiffTest}). Two same-named kernel enums are why those spellings
 * tempt: {@code md-model-api}'s {@code IDateRangeType.InterpretationOfYear} is {@code {FROM, TO}} and only
 * <em>comments</em> the {@code SUBSEQUENT}/{@code PREVIOUS} semantics, while the separate
 * {@code kernel-conversion-java} {@code InterpretationOfYear} is {@code {STANDARD, FROM, TO}}.
 *
 * <p>Same name and same constants as the interpreter's {@code RangeYearInterpretation}, deliberately: one
 * vocabulary for one kernel concept across the whole project.
 */
public enum RangeYearInterpretation {

    /** The default: both endpoints take the base year. Emitted by omitting the property. */
    STANDARD,

    /** The base year is the range's from-year; a wrapping range's END rolls forward one year. */
    FROM,

    /** The base year is the range's to-year; a wrapping range's START rolls back one year. */
    TO;

    /** The DM-JSON {@code interpretationOfYear} token, or empty for {@link #STANDARD} (the key is omitted). */
    public Optional<String> json() {
        return this == STANDARD ? Optional.empty() : Optional.of(name());
    }

    /**
     * The declaration's meaning. An <b>absent</b> key is {@link #STANDARD} — that is how the declaration
     * expresses the default — but a PRESENT token this enum does not know is <b>refused</b>, not degraded.
     *
     * <p>Degrading it would re-emit the field with the key gone, which is the same silent drop this component
     * exists to close, relocated from "no slot" to "wrong slot". Failing fast is safe rather than over-strict
     * because the kernel refuses such a model itself: an empty token fails deserialization, measured
     * 2026-08-22 by {@code DateRangeYearInterpretationTest.theKernelRefusesAnEmptyTokenRatherThanReadingItAsAbsent},
     * so no kernel-legal model can carry one and the refusal cannot make a legal model unreadable.
     */
    public static RangeYearInterpretation fromJson(String token) {
        if (token == null) {
            return STANDARD;
        }
        return switch (token) {
            case "FROM" -> FROM;
            case "TO" -> TO;
            default -> throw new IllegalArgumentException(
                    "unsupported DateRangeType interpretationOfYear: '" + token + "' — the declaration admits "
                            + "FROM or TO, and expresses STANDARD by omitting the key");
        };
    }
}
