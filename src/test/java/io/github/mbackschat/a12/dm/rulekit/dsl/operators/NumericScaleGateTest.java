package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRounding;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rounding;

/**
 * The typed numeric equality gate carries the kernel's checked signed scale plus the independent
 * multiplicative-constant capability. It must not special-case syntax as “all literals are exempt”.
 */
class NumericScaleGateTest {

    private final BoundModel model = BoundModel.ofJson(Fixtures.orderJson());

    @Test
    void onlyAnExpandableSmallerScaleSideMayBePadded() {
        var scale0 = model.num("/Order/Quantity").asNumber();
        var scale2 = model.num("/Order/UnitPrice").asNumber();

        assertDoesNotThrow(() -> scale2.eq(Dsl.number("0")));
        assertDoesNotThrow(() -> Dsl.number("0").eq(scale2));
        var negativeScaleCapable = Dsl.number("1000").times(scale2);
        assertDoesNotThrow(() -> scale0.eq(negativeScaleCapable),
                "1000's stripped scale -3 plus the field's scale 2 yields a capable derived scale -1");
        assertDoesNotThrow(() -> negativeScaleCapable.eq(scale0));

        assertScaleGate(() -> scale0.eq(Dsl.number("0.00")));
        assertScaleGate(() -> Dsl.number("0.00").eq(scale0));
        var positiveScaleCapable = Dsl.number("1").times(scale2);
        assertScaleGate(() -> scale0.eq(positiveScaleCapable));
        assertScaleGate(() -> positiveScaleCapable.eq(scale0));
    }

    @Test
    void scaleZeroPowerIsKnownOnlyForASimpleNonnegativeConstantExponent() {
        var scale0 = model.num("/Order/Quantity").asNumber();
        var scale2 = model.num("/Order/UnitPrice").asNumber();

        assertDoesNotThrow(() -> scale0.eq(scale0.toThePowerOf(Dsl.number("0"))));
        assertDoesNotThrow(() -> scale0.eq(scale0.toThePowerOf(Dsl.number("1000"))));

        assertScaleGate(() -> scale0.eq(scale0.toThePowerOf(Dsl.number("-1"))));
        assertScaleGate(() -> scale0.eq(scale0.toThePowerOf(scale0)));
        assertScaleGate(() -> scale0.eq(scale2.toThePowerOf(Dsl.number("2"))));
        // Power clears multiplicative-constant capability even when its derived scale is known.
        assertScaleGate(() -> scale2.eq(Dsl.number("0").toThePowerOf(Dsl.number("2"))));
    }

    @Test
    void orderingDeliberatelyBypassesTheScaleGate() {
        var scale0 = model.num("/Order/Quantity").asNumber();
        var scale2 = model.num("/Order/UnitPrice").asNumber();
        assertDoesNotThrow(() -> scale0.lt(Dsl.number("0.00")));
        assertDoesNotThrow(() -> scale0.lt(scale2.dividedBy(Dsl.number("3"))));
    }

    @Test
    void fixedScaleRoundingAndScalePreservingAbsoluteValueCarryMetadata() {
        var scale0 = model.num("/Order/Quantity");
        var scale2 = model.num("/Order/UnitPrice");

        assertDoesNotThrow(() -> scale0.eq(Dsl.roundDownValue(scale2)));
        assertDoesNotThrow(() -> scale2.eq(Dsl.roundDownValue(scale2, 2)));
        assertDoesNotThrow(() -> scale2.eq(Dsl.roundDown(scale2.asNumber(), 2)));
        assertScaleGate(() -> scale0.eq(Dsl.roundDownValue(scale2, 2)));
        assertScaleGate(() -> scale0.eq(Dsl.roundDown(scale2.asNumber(), 2)));
        assertDoesNotThrow(() -> scale2.eq(Dsl.absValue(scale2)));
        assertScaleGate(() -> scale0.eq(Dsl.absValue(scale2)));
    }

    @Test
    void publicRoundingAstConstructionDerivesOptionalPlaces() {
        var scale0 = model.num("/Order/Quantity");
        var scale2 = model.num("/Order/UnitPrice");
        var expressionRound = new NumberExpr(new Rounding(Rounding.Mode.DOWN, scale2.value(), 2));
        var fieldRound = new NumberExpr(
                new FieldRounding(FieldRounding.Mode.ROUND_DOWN, scale2.field(), 2));

        assertDoesNotThrow(() -> scale2.eq(expressionRound));
        assertDoesNotThrow(() -> scale2.eq(fieldRound));
        assertScaleGate(() -> scale0.eq(expressionRound));
        assertScaleGate(() -> scale0.eq(fieldRound));
    }

    private static void assertScaleGate(org.junit.jupiter.api.function.Executable executable) {
        var thrown = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(thrown.getMessage().contains("MVK_INVALID_COMPARE_DEC_PLACES"), thrown::getMessage);
    }
}
