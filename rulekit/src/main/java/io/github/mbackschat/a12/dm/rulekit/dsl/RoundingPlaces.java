package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * The decimal-places slot the rounding expression ({@link Rounding}) and its bare-field sibling
 * ({@link FieldRounding}) share, so the one measured bound is stated once rather than once per class.
 *
 * <p>The slot is an UNSIGNED integer token, which makes its two refusals different kernel classes rather than
 * two ends of one range: a sign is a token the grammar cannot read ({@code MVK_UNEXPECTED_TOKEN}), while a
 * value above 14 is read and then refused by the scale gate ({@code MVK_INVALID_DIGIT_FOR_ROUNDING}). Both
 * are measured on both constructs in {@code validate.laws.RoundingPlacesOverflowLawsTest}.
 */
final class RoundingPlaces {

    private RoundingPlaces() {
    }

    static void check(int places) {
        if (places < 0) {
            throw Refusals.of(KernelCode.MVK_UNEXPECTED_TOKEN, refusal(places));
        }
        if (places > 14) {
            throw Refusals.of(KernelCode.MVK_INVALID_DIGIT_FOR_ROUNDING, refusal(places));
        }
    }

    private static String refusal(int places) {
        return "rounding decimal places must be in 0..14, was " + places;
    }
}
