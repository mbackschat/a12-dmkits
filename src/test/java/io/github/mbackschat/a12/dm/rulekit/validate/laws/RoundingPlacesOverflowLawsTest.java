package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * What a rounding places token does OUTSIDE the 0..14 scale gate — a second boundary above it, and a
 * different class altogether below it.
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
        actual.put("-1 — a sign on the token", verdict("-1"));
        actual.put("0 — the bottom of the scale gate", verdict("0"));
        actual.put("14 — the top of the scale gate", verdict(14));
        actual.put("15 — one above it", verdict("15"));
        actual.put("Long.MAX_VALUE", verdict("9223372036854775807"));
        actual.put("Long.MAX_VALUE + 1", verdict("9223372036854775808"));
        actual.put("far beyond a signed 64-bit read", verdict("99999999999999999999999999"));
        actual.put("leading zeros on an in-range value", verdict("00000002"));
        actual.put("leading zeros on an out-of-range value", verdict("0000000015"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The slot is an UNSIGNED integer token: a sign is not a scale the gate judges but a token the
        // grammar cannot read, so the two ends of 0..14 are two different classes.
        expected.put("-1 — a sign on the token", "MVK_UNEXPECTED_TOKEN");
        expected.put("0 — the bottom of the scale gate", KernelLaws.ADMITTED);
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

    /**
     * <b>The kit's own 0..14 refusal, pinned BESIDE the kernel row that is its basis.</b>
     *
     * <p>A client-side refusal may never be broader than something measured, and delegation is not available
     * here: the kit throws before the kernel is asked, so nothing downstream can discover the bound is wrong.
     * The two assertions are the whole contract — 14 reaches the kernel and is admitted, 15 never leaves the
     * kit, and the ladder above shows the kernel independently refusing 15 with
     * {@code MVK_INVALID_DIGIT_FOR_ROUNDING}. Same boundary, measured on both sides of the call.
     *
     * <p>The READ path is asserted too, because a bound in a constructor also fires when a kernel model is
     * converted inbound: an over-strict check there makes a legal model unreadable rather than merely
     * unauthorable, which is the sharper half and the one no authoring test would notice.
     *
     * <p><b>Why this test exists as a correction.</b> It was advertised by name from {@link #verdict(int)}'s
     * javadoc and never written — a dangling {@code @link} and an unused {@code assertThrows} import were all
     * that remained of it, and a session summary reported it as landed. A cold review found it by resolving
     * the link.
     */
    @Test
    void theKitsOwnPlacesBoundHasThisKernelRowAsItsBasis() {
        assertEquals(KernelLaws.ADMITTED, verdict(14), "14 is admitted by the kernel, so the kit must build it");

        var refusal = assertThrows(KernelCodeRefusal.class,
                () -> Dsl.roundUpValue(UNIT_PRICE, 15),
                "the kit must refuse 15 rather than hand the kernel a token it rejects");
        assertEquals("MVK_INVALID_DIGIT_FOR_ROUNDING", refusal.code().name(),
                "and cite the class the ladder measures for 15");

        assertEquals("MVK_UNEXPECTED_TOKEN",
                assertThrows(KernelCodeRefusal.class, () -> Dsl.roundUpValue(UNIT_PRICE, -1)).code().name(),
                "the lower end cites the ladder's -1 row, which is a different class");

        // The inbound direction: the identical constructor runs when a kernel model is READ, so a bound that
        // is too narrow silently costs readability. 14 must survive the round trip that 15 never enters.
        assertEquals(KernelLaws.ADMITTED,
                classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku",
                        Dsl.fieldFilled(SKU).and(Dsl.number(0).lt(Dsl.roundDownValue(UNIT_PRICE, 14))))),
                "the sibling rounding operator carries the same bound and the same kernel row");
    }

    /**
     * <b>The same bound on the EXPRESSION arm, because it is reached from a second class.</b>
     *
     * <p>{@code roundUpValue(NumberRef, int)} builds a {@code FieldRounding} while {@code roundUp(NumberExpr,
     * int)} builds a {@code Rounding}. Both now call one shared check, so the bound cannot drift between them —
     * but each must still CALL it, and a mutant dropping the call from one class survives a test of the other,
     * so one row per class stays the minimum.
     */
    @Test
    void theExpressionArmReachesTheSameBound() {
        var expression = Dsl.number(1).plus(2);

        assertEquals(KernelLaws.ADMITTED,
                classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku",
                        Dsl.fieldFilled(SKU).and(Dsl.number(0).lt(Dsl.roundUp(expression, 14))))),
                "14 is admitted by the kernel on this arm too");

        assertEquals("MVK_INVALID_DIGIT_FOR_ROUNDING",
                assertThrows(KernelCodeRefusal.class, () -> Dsl.roundUp(expression, 15)).code().name(),
                "the expression arm must refuse 15 as the field arm does, citing the same class");
        assertEquals("MVK_UNEXPECTED_TOKEN",
                assertThrows(KernelCodeRefusal.class, () -> Dsl.roundDown(expression, -1)).code().name(),
                "the lower end, on a second mode of the same arm");
        assertEquals("MVK_UNEXPECTED_TOKEN",
                classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku",
                        "FieldFilled(Sku) And 0 < RoundUp([UnitPrice], -1)")),
                "and the kernel reads a sign on this construct's token the same way");
    }

    /**
     * {@code AbsValue} is the one {@code …Value} spelling with NO digits slot, so any digits argument — zero
     * included — is a token its production cannot place; the bare form is the positive control that keeps the
     * refusal attributable to the argument.
     */
    @Test
    void absValueHasNoDigitsSlot_soEvenZeroIsAnUnplaceableToken() {
        for (String form : new String[]{"AbsValue(UnitPrice, 2)", "AbsValue(UnitPrice, 0)"}) {
            assertEquals("MVK_UNEXPECTED_TOKEN",
                    classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku", "FieldFilled(Sku) And 0 < " + form)),
                    form);
        }
        assertEquals(KernelLaws.ADMITTED,
                classes(KernelLaws.checkModel(PRICES, GROUP, "../Sku", "FieldFilled(Sku) And 0 < AbsValue(UnitPrice)")));

        assertEquals("MVK_UNEXPECTED_TOKEN",
                assertThrows(KernelCodeRefusal.class, () -> new FieldRounding(
                        FieldRounding.Mode.ABS, UNIT_PRICE.field(), Optional.of(0))).code().name(),
                "rulekit refuses the same shape with the same class");
    }
}
