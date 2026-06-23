package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.ComputeResult;
import io.github.mbackschat.a12.dm.adapter.EvalMessage;
import io.github.mbackschat.a12.dm.adapter.EvalResult;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The public {@link KernelAdapter#evaluate} seam — the runtime "does this rule FIRE on a document
 * instance?" capability behind the {@code eval-doc} verb. The seam takes a <b>nested</b> document tree
 * (repeatable groups as arrays, fields as values) and derives the kernel's per-segment repetition
 * indices itself; this test pins that derivation against the <b>known-good hand-built indices</b> in
 * {@link RepetitionLawsTest} (the spike — a wrong index = wrong row attribution = a judge silently
 * mis-deciding equivalence). Shares the {@code laws} package so it can reuse {@link RuntimeLaws}' model
 * fixtures + {@code withRule} injection.
 */
class EvalDocSeamLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    // a rule in /Order whose error field reaches THROUGH the repeatable Items → iterates per row
    private static final String PER_ROW = RuntimeLaws.withRule(ORDER, "/Order", "CountCap", "../Items/Count",
            "COUNT_HIGH", "FieldFilled(Items/Count) And [Items/Count] > 50");

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The nested document for an Order with three Items rows carrying the given Count values (null = empty). */
    private static Map<String, Object> orderWithCounts(String c1, String c2, String c3) {
        return map("Order", map(
                "CustomerName", "Acme",
                "Items", List.of(
                        map("Sku", "A", "Count", c1),
                        map("Sku", "B", "Count", c2),
                        map("Sku", "C", "Count", c3))));
    }

    @Test
    void nestedWalkReproducesTheKnownGoodRowPointers() {
        // counts 10 / 80 / 90 → only rows 2 and 3 violate "> 50"; the SAME outcome RepetitionLawsTest
        // asserts via hand-placed indices — here the indices are DERIVED from the nested array order.
        EvalResult res = KernelAdapter.ofJson(PER_ROW).evaluate(orderWithCounts("10", "80", "90"), Locale.US);
        List<EvalMessage> countHigh = res.messages().stream()
                .filter(m -> m.errorCode().equals("COUNT_HIGH")).toList();

        assertEquals(2, countHigh.size(), () -> "fires once per offending row, got: " + countHigh);
        assertTrue(countHigh.stream().anyMatch(m -> m.field().reps().equals(List.of(1, 2, 1))),
                () -> "a message attaches to row 2 ([1, 2, 1]): " + countHigh);
        assertTrue(countHigh.stream().anyMatch(m -> m.field().reps().equals(List.of(1, 3, 1))),
                () -> "a message attaches to row 3 ([1, 3, 1]): " + countHigh);
    }

    @Test
    void aPerRowRuleDoesNotFireWhenEveryRowComplies() {
        EvalResult res = KernelAdapter.ofJson(PER_ROW).evaluate(orderWithCounts("10", "20", "30"), Locale.US);
        assertFalse(res.firedCodes().contains("COUNT_HIGH"), "no row exceeds 50 → fires nowhere");
    }

    @Test
    void anAuthorRuleMessageCarriesItsRulePath() {
        // the fire/no-fire signal a formal/kernel error lacks: an author rule's message names its rule
        EvalResult res = KernelAdapter.ofJson(PER_ROW).evaluate(orderWithCounts("99", null, null), Locale.US);
        assertTrue(res.messages().stream()
                        .anyMatch(m -> m.errorCode().equals("COUNT_HIGH") && m.rulePath().isPresent()),
                () -> "COUNT_HIGH attributes to its rule (rulePath present): " + res.messages());
    }

    @Test
    void presentEmptyRowFiresGroupFilledButAnAbsentGroupDoesNot() {
        // "filled" = the ROW EXISTS, not that it has data — distinguishes present-empty (null) from absent
        String model = RuntimeLaws.withRule(ORDER, "/Order", "RowExists", "../Items/Count", "ROW_EXISTS",
                "GroupFilled(Items)");

        Map<String, Object> rowExists = map("Order", map(
                "CustomerName", "Acme",
                "Items", List.of(map("Sku", "A", "Count", null))));   // Count present but empty
        Map<String, Object> noRow = map("Order", map("CustomerName", "Acme"));   // Items key absent → no row

        assertTrue(KernelAdapter.ofJson(model).evaluate(rowExists, Locale.US).firedCodes().contains("ROW_EXISTS"),
                "GroupFilled fires for a created row whose Count is empty (the row exists)");
        assertFalse(KernelAdapter.ofJson(model).evaluate(noRow, Locale.US).firedCodes().contains("ROW_EXISTS"),
                "GroupFilled does not fire when no Items row exists");
    }

    /** The order model + a {@code CoverageWindow = DateRange(OrderDate, DeliveryDate)} computation. */
    private static String withCoverageComputation() {
        BoundModel m = BoundModel.ofJson(ORDER);
        Computation comp = m.computation(m.field("/Order/CoverageWindow")).name("CoverageWindowComp")
                .calc(Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")))
                .message(Dsl.en("Coverage window.")).build();
        return m.add(comp).toJson();
    }

    @Test
    void computeProducesTheComputedValueFromItsOperands() {
        Map<String, Object> doc = map("Order", map("OrderDate", "2024-01-01", "DeliveryDate", "2024-02-01"));
        ComputeResult res = KernelAdapter.ofJson(withCoverageComputation()).compute(doc, Locale.US);
        assertTrue(res.computed().stream().anyMatch(c ->
                        c.path().equals("/Order/CoverageWindow") && c.outcome().equals("value") && c.value().isPresent()),
                () -> "CoverageWindow computes a value from both operands: " + res.computed());
    }

    @Test
    void computeProducesNoValueWhenAnOperandIsEmpty() {
        // the runtime-compute peer of the validation empty-operand law: an empty operand is not evaluated,
        // so the computed field gets NO value (KERNEL-FINDINGS §2 supplement; DateRangeComputationLawsTest)
        Map<String, Object> doc = map("Order", map("OrderDate", "2024-01-01", "DeliveryDate", null));
        ComputeResult res = KernelAdapter.ofJson(withCoverageComputation()).compute(doc, Locale.US);
        assertFalse(res.computed().stream().anyMatch(c ->
                        c.path().equals("/Order/CoverageWindow") && c.outcome().equals("value")),
                () -> "an empty date operand computes no value: " + res.computed());
    }

    @Test
    void validationDoesNotRunComputations() {
        // KERNEL FACT: validateFull validates the document's values AS-IS — it does NOT compute computed
        // fields first (compute() is a separate call). So a rule referencing a computed field sees it as
        // whatever the document supplies: empty when only the operands are present, the value when supplied.
        String model = RuntimeLaws.withRule(withCoverageComputation(), "/Order", "CwPresent", "../CoverageWindow",
                "CW_PRESENT", "FieldFilled(CoverageWindow)");

        // only the operands present → the kernel would COMPUTE CoverageWindow under compute(), but
        // validateFull does not, so FieldFilled(CoverageWindow) is false and CW_PRESENT does NOT fire
        Map<String, Object> operandsOnly = map("Order", map("OrderDate", "2024-01-01", "DeliveryDate", "2024-02-01"));
        assertFalse(KernelAdapter.ofJson(model).evaluate(operandsOnly, Locale.US).firedCodes().contains("CW_PRESENT"),
                "validateFull does not compute the computed field — it reads empty, so FieldFilled is false");

        // supply the computed field's value directly → validation sees it → CW_PRESENT fires
        Map<String, Object> supplied = map("Order", map("OrderDate", "2024-01-01", "DeliveryDate", "2024-02-01",
                "CoverageWindow", "01.01.2024-01.02.2024"));
        assertTrue(KernelAdapter.ofJson(model).evaluate(supplied, Locale.US).firedCodes().contains("CW_PRESENT"),
                "validation uses the document's supplied value for the computed field as-is");
    }

    @Test
    void anUnknownPathInTheDocumentIsRejected() {
        Map<String, Object> bad = map("Order", map("Nope", "x"));
        try {
            KernelAdapter.ofJson(ORDER).evaluate(bad, Locale.US);
            org.junit.jupiter.api.Assertions.fail("expected an unknown-path rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("/Order/Nope"), () -> "names the bad path: " + expected.getMessage());
        }
    }
}
