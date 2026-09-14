package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — <b>the six rounding operators are two constructs with two different operand gates and
 * one shared DIGITS gate, and the digits gate is asked LAST</b>. {@code CheckRundenOpImpl} takes an entity
 * spec, {@code CheckRundenOperOpImpl} takes an operation, and both end at
 * {@code CheckOpUtils.isSpecificationForRounding}.
 *
 * <p><b>The digit ceiling is 14</b>, a raise the kernel source dates to {@code A12K-3691}, and the gate
 * accepts a non-negative integer written as digits and nothing else.
 *
 * <p><b>Which construct an operator is decides what it refuses its operand for</b>. The {@code …Value}
 * spelling takes a field and refuses a group, a wildcard, a category and a non-number; the bare spelling
 * takes an operation and refuses a CONSTANT, which the field spelling has no way to express. Neither refusal
 * set is derivable from the other, and a consumer treating the six as one operator is wrong on both.
 *
 * <p><b>The digits gate runs behind the operand gate on both constructs</b>, which the paired rows fix: the
 * same out-of-range digit count is invisible behind a non-number operand and behind a constant one.
 *
 * <p><b>Every row is compared against a LITERAL</b>, because the rounded result's scale is the digits
 * argument while a field comparand fixes a scale of its own: under {@code [UnitPrice] != RoundUpValue(…, 2)}
 * the number-comparison gate reports {@code MVK_INVALID_COMPARE_DEC_PLACES} on a four-digit declaration and
 * no rounding gate is reached at all. That is the comparand confound {@link ExtremumOperandKindGridLawsTest}
 * names, on a different axis, and it masked every admitted cell of this grid on the first run.
 *
 * <p>Own domain (an e-commerce price list).
 */
class RoundingOperandLawsTest {

    private static final String GROUP = "/PriceList";
    private static final String ERROR_REL = "../Sku";

    private static final String DIGITS = "MVK_INVALID_DIGIT_FOR_ROUNDING";
    private static final String NO_CONST = "MVK_NO_CONST";
    private static final String NO_NUMBER = "MVK_NO_NUMBER";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String NO_WILDCARDS = "MVK_NO_WILDCARDS_ALLOWED";
    private static final String NO_CATEGORY = "MVK_CATEGORY_NOT_ALLOWED";

    private static final String PRICES = DocumentModelDraft.named("price-list").locale("en_US")
            .rootGroup("PriceList", list -> list
                    .stringField("Sku", f -> { })
                    .stringField("Label", f -> { })
                    .numberField("UnitPrice", f -> f.fractionDigits(4))
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .enumField("Tier", f -> f.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .group("Bundle", g -> g.numberField("BundleQty", f -> f.fractionDigits(0)))
                    .group("Lines", g -> g.repeatable(20).numberField("LineQty", f -> f.fractionDigits(0))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(PRICES, GROUP, ERROR_REL, "FieldFilled(Sku) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** The operators that take an entity SPEC, and the ones that take an OPERATION. */
    private static final List<String> FIELD_SPELLINGS =
            List.of("RoundUpValue", "RoundDownValue", "RoundAccountingValue");

    private static final List<String> OPERATION_SPELLINGS =
            List.of("RoundUp", "RoundDown", "RoundAccounting");

    /** The shared digits gate, over both constructs and every spelling of the six operators. */
    @Test
    void theDigitCeilingIsFourteenAndHoldsAcrossBothConstructs() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String op : FIELD_SPELLINGS) {
            for (String digits : List.of("0", "2", "14", "15", "99")) {
                String cell = op + "(UnitPrice, " + digits + ")";
                actual.put(cell, verdict(cell + " > 0"));
                expected.put(cell, Integer.parseInt(digits) <= 14 ? KernelLaws.ADMITTED : DIGITS);
            }
        }
        for (String op : OPERATION_SPELLINGS) {
            for (String digits : List.of("0", "2", "14", "15", "99")) {
                String cell = op + "([UnitPrice], " + digits + ")";
                actual.put(cell, verdict(cell + " > 0"));
                expected.put(cell, Integer.parseInt(digits) <= 14 ? KernelLaws.ADMITTED : DIGITS);
            }
        }
        assertEquals(expected, actual, "0 through 14 are admitted and everything above is refused");
    }

    /** The field construct's own operand gates, and the order that hides the digits one behind them. */
    @Test
    void theValueSpellingTakesAFieldAndRefusesTheOtherShapes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a number field", verdict("0 < RoundUpValue(UnitPrice, 2)"));
        actual.put("a text field", verdict("0 < RoundUpValue(Label, 2)"));
        actual.put("a group", verdict("0 < RoundUpValue(Bundle, 2)"));
        actual.put("a starred field", verdict("0 < RoundUpValue(Lines*/LineQty, 2)"));
        actual.put("an enum category", verdict("0 < RoundUpValue(Tier -> Band, 2)"));
        actual.put("a text field with an out-of-range digit count",
                verdict("0 < RoundUpValue(Label, 99)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a number field", KernelLaws.ADMITTED);
        expected.put("a text field", NO_NUMBER);
        expected.put("a group", NO_GROUPS);
        expected.put("a starred field", NO_WILDCARDS);
        expected.put("an enum category", NO_CATEGORY);
        // The digits gate runs last, so the operand's own class is what a doubly-wrong call reports.
        expected.put("a text field with an out-of-range digit count", NO_NUMBER);
        assertEquals(expected, actual, "the field spelling refuses four operand shapes before it counts digits");
    }

    /** The operation construct, whose one extra refusal — a constant — the field spelling cannot express. */
    @Test
    void theBareSpellingTakesAnOperationAndRefusesAConstant() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field value", verdict("RoundUp([UnitPrice], 2) > 0"));
        actual.put("an arithmetic expression", verdict("RoundUp([UnitPrice] * [Quantity], 2) > 0"));
        actual.put("a numeric constant", verdict("RoundUp(5, 2) > 0"));
        actual.put("a text value", verdict("RoundUp([Label], 2) > 0"));
        actual.put("a constant with an out-of-range digit count", verdict("RoundUp(5, 99) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field value", KernelLaws.ADMITTED);
        expected.put("an arithmetic expression", KernelLaws.ADMITTED);
        expected.put("a numeric constant", NO_CONST);
        expected.put("a text value", NO_NUMBER);
        // The constant gate is the first of the three, so it outranks the digits one too.
        expected.put("a constant with an out-of-range digit count", NO_CONST);
        assertEquals(expected, actual, "the bare spelling refuses a constant operand before typing it");
    }
}
