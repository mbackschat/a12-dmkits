package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * What a rounding places token does ABOVE the 0..14 scale gate — where a SECOND boundary sits.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-08-06}. {@link RoundingOperandLawsTest} owns the
 * ordinary range and the gate's position behind the operand gates; this file owns only the ladder above it,
 * because the interesting fact is that the places token is READ as a signed 64-bit integer before the scale
 * gate sees it, so the two refusals have different causes and different classes.
 *
 * <p><b>{@code MVK_INTERNAL_ERROR} is a real static verdict here, not a harness accident.</b> A token one
 * above {@code Long.MAX_VALUE} is still a well-formed digit sequence to the grammar, so the refusal happens
 * inside the read rather than at the gate — which is why leading zeros, which change the token's length but
 * not its value, never reach it.
 */
class RoundingPlacesOverflowLawsTest {

    private static final String GROUP = "/PriceList";

    private static final String PRICES = DocumentModelDraft.named("price-list").locale("en_US")
            .rootGroup("PriceList", list -> list
                    .stringField("Sku", f -> { })
                    .numberField("UnitPrice", f -> f.fractionDigits(4)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(PRICES);

    private static final FieldRef SKU = M.field("/PriceList/Sku");
    private static final NumberRef UNIT_PRICE = M.num("/PriceList/UnitPrice");

    /**
     * The text path, and here it is the point rather than a fallback: this law's subject is the TOKEN in the
     * places slot, and the typed slot is an {@code int} — so a value past a signed 64-bit read and a leading
     * zero, the two shapes the grid exists to separate, have no typed spelling by construction.
     */
    private static String verdict(String places) {
        return classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku",
                "FieldFilled(Sku) And 0 < RoundUpValue(UnitPrice, " + places + ")"));
    }

    /**
     * The typed path, for the in-range places the kit will build — the ladder's live boundary. Only 14 comes
     * through here: the kit refuses 15 itself, which {@link #theKitsOwnPlacesBoundHasThisKernelRowAsItsBasis}
     * pins rather than leaving as an unstated reason for a text row.
     */
    private static String verdict(int places) {
        return classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku",
                Dsl.fieldFilled(SKU)
                        .and(Dsl.number(0).lt(Dsl.roundUpValue(UNIT_PRICE, places)))));
    }

    private static String classes(ValidationResult r) {
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void thePlacesLadder() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("14 — the top of the scale gate", verdict(14));
        actual.put("15 — one above it", verdict("15"));
        actual.put("Long.MAX_VALUE", verdict("9223372036854775807"));
        actual.put("Long.MAX_VALUE + 1", verdict("9223372036854775808"));
        actual.put("far beyond a signed 64-bit read", verdict("99999999999999999999999999"));
        actual.put("leading zeros on an in-range value", verdict("00000002"));
        actual.put("leading zeros on an out-of-range value", verdict("0000000015"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("14 — the top of the scale gate", KernelLaws.ADMITTED);
        expected.put("15 — one above it", "MVK_INVALID_DIGIT_FOR_ROUNDING");
        // Still readable as a long, so the SCALE gate answers — the whole range 15..2^63-1 is one class.
        expected.put("Long.MAX_VALUE", "MVK_INVALID_DIGIT_FOR_ROUNDING");
        // One more, and the read itself fails before the gate is reached.
        expected.put("Long.MAX_VALUE + 1", "MVK_INTERNAL_ERROR");
        expected.put("far beyond a signed 64-bit read", "MVK_INTERNAL_ERROR");
        // Leading zeros lengthen the token without changing its value, so they cross neither boundary.
        expected.put("leading zeros on an in-range value", KernelLaws.ADMITTED);
        expected.put("leading zeros on an out-of-range value", "MVK_INVALID_DIGIT_FOR_ROUNDING");
        assertEquals(expected, actual, "the places ladder crosses two boundaries, not one");
    }
}
