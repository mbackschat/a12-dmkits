package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The typed peer of {@code validate.laws.DateConstructionComponentDeclarationLawsTest}: a NUMBER component of
 * {@code Date(day, month, year)} must declare a lower bound and an upper bound equal to its SLOT's maximum.
 *
 * <p>Contextual, so it sits on {@link BoundModel} — a field's bounds are model data, which is why
 * {@link DateConstruction}'s own constructor can only gate the intrinsic extractor-position rule.
 */
class DateConstructionSlotBoundsGateTest {

    private static final BoundModel M = BoundModel.ofJson(
            DocumentModelDraft.named("date-slot-bounds").locale("en_US")
                    .rootGroup("Cover", c -> c
                            .field("Ref", FieldKind.STRING)
                            .numberField("MonthBounded", n -> n.min("1").max("12"))
                            .numberField("DayBounded", n -> n.min("1").max("31"))
                            .numberField("MonthMaxOnly", n -> n.max("12"))
                            .numberField("Unbounded", n -> { })
                            .numberField("MinTwoMaxTwelve", n -> n.min("2").max("12"))
                            .numberField("MonthUnsignedMax", n -> n.positivesOnly().max("12"))
                            .numberField("DayUnsignedMax", n -> n.positivesOnly().max("31"))
                            .numberField("MonthUnsignedLen2", n -> n.positivesOnly().maxLength(2))
                            .numberField("MonthSignedLen2", n -> n.maxLength(2))
                            .numberField("MonthNegativeMin", n -> n.min("-5").max("12"))
                            .numberField("MonthScaled", n -> n.min("1").max("12").fractionDigits(2))
                            .dateField("Anniversary", d -> d.format("yyyy-MM-dd")))
                    .build().toJson());

    private static Calculation field(String name) {
        return M.num("/Cover/" + name).asNumber().expression();
    }

    private static Calculation literal(String text) {
        return new StringLiteral(text);
    }

    @Test
    void aSlotMatchedNumberIsAdmitted() {
        assertDoesNotThrow(() -> M.dateOf(field("DayBounded"), field("MonthBounded"), literal("2009")));
    }

    @Test
    void aCeilingWithNoFloorIsRefused() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("MonthMaxOnly"), literal("2009")));
        assertTrue(thrown.getMessage().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("month"), thrown::getMessage);
    }

    @Test
    void anUnboundedNumberIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("Unbounded"), literal("2009")));
    }

    /**
     * THE PER-SLOT ROW. The same 1..31 field is admitted as a day and refused as a month, so the gate reads the
     * range against the position; and 1..12 is refused as a day although every value it holds is a legal day, so
     * the test is the slot's own maximum rather than containment.
     */
    @Test
    void theSameFieldPassesInOneSlotAndFailsInTheOther() {
        assertDoesNotThrow(() -> M.dateOf(field("DayBounded"), field("MonthBounded"), literal("2009")));
        assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("DayBounded"), literal("2009")));
        assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(field("MonthBounded"), field("MonthBounded"), literal("2009")));
    }

    /**
     * THE FOUR-COMPONENT FORM, whose day and month slots keep the same rule — measured, because the form has
     * rules of its own for the two YEAR positions it splits and inheriting the others would be a scope
     * transfer. Without this row, deleting both gate calls from that overload survives every other test.
     */
    @Test
    void theFourComponentFormAppliesTheSameSlotRule() {
        assertDoesNotThrow(() -> M.dateOf(
                field("DayBounded"), field("MonthBounded"), literal("19"), literal("63")));
        assertThrows(IllegalArgumentException.class, () -> M.dateOf(
                field("DayBounded"), field("DayBounded"), literal("19"), literal("63")));
        assertThrows(IllegalArgumentException.class, () -> M.dateOf(
                field("MonthBounded"), field("MonthBounded"), literal("19"), literal("63")));
    }

    /**
     * THE LOW SIDE HAS TWO FORMS, and reading only the minimum is what refused legal models: an UNSIGNED
     * component needs no minimum at all. The signed row beside it is the control that makes unsignedness the
     * operative property rather than the ceiling.
     */
    @Test
    void anUnsignedComponentNeedsNoMinimum() {
        assertDoesNotThrow(() -> M.dateOf(literal("01"), field("MonthUnsignedMax"), literal("2009")));
        assertDoesNotThrow(() -> M.dateOf(field("DayUnsignedMax"), field("MonthBounded"), literal("2009")));
    }

    /** THE CEILING HAS TWO FORMS TOO: a digit LENGTH of the slot's width, not only a value maximum. */
    @Test
    void aLengthCeilingSatisfiesTheSlotAsWellAsAValueMaximum() {
        assertDoesNotThrow(() -> M.dateOf(literal("01"), field("MonthUnsignedLen2"), literal("2009")));
        // The control: the same length ceiling while signs are permitted still fails the low side.
        assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("MonthSignedLen2"), literal("2009")));
    }

    /** A declared minimum must be NON-NEGATIVE; "a minimum exists" is not the requirement. */
    @Test
    void aNegativeMinimumDoesNotSatisfyTheLowSide() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("MonthNegativeMin"), literal("2009")));
        assertTrue(thrown.getMessage().contains("non-negative"), thrown::getMessage);
    }

    /** The third conjunct: a date component must be a whole number. */
    @Test
    void aDeclaredFractionIsRefused() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> M.dateOf(literal("01"), field("MonthScaled"), literal("2009")));
        assertTrue(thrown.getMessage().contains("fraction"), thrown::getMessage);
    }

    /**
     * NOT refused, deliberately: no measured row carries a lower bound other than 1, and a refusal may not be
     * wider than its basis. If the kernel turns out to require exactly 1, that is a row to add here first.
     */
    @Test
    void aLowerBoundOtherThanOneIsDelegated() {
        assertDoesNotThrow(() -> M.dateOf(literal("01"), field("MinTwoMaxTwelve"), literal("2009")));
    }

    /** A component that is not a declared NUMBER field delegates — the String and year rules are elsewhere. */
    @Test
    void nonNumberComponentsAreDelegated() {
        assertDoesNotThrow(() -> M.dateOf(literal("01"), literal("03"), literal("2009")));
        assertDoesNotThrow(() -> M.dateOf(literal("01"), literal("03"), field("Unbounded")));
    }
}
