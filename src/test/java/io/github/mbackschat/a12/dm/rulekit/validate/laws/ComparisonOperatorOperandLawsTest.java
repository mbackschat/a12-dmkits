package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * What each COMPARISON OPERATOR demands of the two sides it joins.
 *
 * <p>{@code CheckVergleichsBedingungImpl.check} opens with three questions asked in a fixed order and
 * answered before any type-agreement gate runs: are both sides constants, does a tolerance operator have
 * numbers on both sides, and does an ordering operator have numbers or dates. The order is the reason the
 * grid moves the operator while holding the operands still — {@code 1 &gt; 2} is refused for being two
 * constants, not for being an ordering comparison, and one column could not say which.
 *
 * <p>The tolerance and ordering arms RETURN once satisfied, so a comparison that clears them is never asked
 * the agreement questions at all. That is measured here as an admitted row rather than assumed.
 */
class ComparisonOperatorOperandLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.numberField("Backordered", n -> n.fractionDigits(0));
                g.field("PlacedOn", FieldKind.DATE);
                g.field("ShippedOn", FieldKind.DATE);
                g.field("Expedited", FieldKind.BOOLEAN);
            }).build().toJson();

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** Both sides constant, and the one shape that exempts it. */
    @Test
    void theGateThatRefusesTwoConstants() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field against a constant — the control", verdict("[Quantity] == 1"));
        actual.put("two number constants", verdict("1 == 2"));
        actual.put("two number constants under an ORDERING operator", verdict("1 > 2"));
        actual.put("two string constants", verdict("\"a\" == \"b\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field against a constant — the control", KernelLaws.ADMITTED);
        expected.put("two number constants", "MVK_INVALID_COMPARE_TO_CONST");
        // The constant gate is asked FIRST, so this is not an ordering-type verdict even though the
        // operands would also fail that question.
        expected.put("two number constants under an ORDERING operator", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("two string constants", "MVK_INVALID_COMPARE_TO_CONST");
        assertEquals(expected, actual, "the gate that refuses two constants");
    }

    /** A tolerance operator wants numbers on both sides and returns the moment it has them. */
    @Test
    void whatAToleranceOperatorDemands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two number fields — the control",
                verdict("[Quantity] DiffersWithToleranceRange1 [Backordered]"));
        actual.put("a number field against a number constant",
                verdict("[Quantity] DiffersWithToleranceRange1 5"));
        actual.put("a text field on the right",
                verdict("[Quantity] DiffersWithToleranceRange1 [GiftNote]"));
        actual.put("a text field on the left",
                verdict("[GiftNote] DiffersWithToleranceRange1 [Quantity]"));
        actual.put("a date field on the right",
                verdict("[Quantity] DiffersWithToleranceRange10 [PlacedOn]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two number fields — the control", KernelLaws.ADMITTED);
        expected.put("a number field against a number constant", KernelLaws.ADMITTED);
        expected.put("a text field on the right", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        expected.put("a text field on the left", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        expected.put("a date field on the right", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        assertEquals(expected, actual, "what a tolerance operator demands");
    }

    /** An ordering operator wants a number or a date on both sides. */
    @Test
    void whatAnOrderingOperatorDemands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two number fields — the control", verdict("[Quantity] < [Backordered]"));
        actual.put("two date fields", verdict("[PlacedOn] <= [ShippedOn]"));
        actual.put("two text fields", verdict("[GiftNote] < [Reference]"));
        actual.put("a text field against a number one", verdict("[GiftNote] < [Quantity]"));
        actual.put("a Boolean field against a number one", verdict("[Expedited] < [Quantity]"));
        actual.put("two text fields under EQUALITY — the control", verdict("[GiftNote] == [Reference]"));
        actual.put("a text field against ARITHMETIC", verdict("[GiftNote] < {[Quantity] + 1}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two number fields — the control", KernelLaws.ADMITTED);
        expected.put("two date fields", KernelLaws.ADMITTED);
        expected.put("two text fields", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("a text field against a number one", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("a Boolean field against a number one", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("two text fields under EQUALITY — the control", KernelLaws.ADMITTED);
        // The verdict is the same, but the side that carries it is an EXPRESSION whose result type A12
        // computes; the portable checker leaves such a comparison unjudged rather than typing it from the
        // one reference it can see.
        expected.put("a text field against ARITHMETIC", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual, "what an ordering operator demands");
    }
}
