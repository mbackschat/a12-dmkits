package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Runtime behavioral law (F-LAW): an <b>empty operand in a computation operation is not evaluated</b> —
 * the computed field gets <em>no value</em>, and a previously-computed (stale) value is <em>cleared</em>.
 * Locks the {@code DateRange} computation case (KERNEL-FINDINGS §2 supplement, "empty operand in a
 * computation operation"), the analog of the validation-side empty-date "not evaluated" rule (§2).
 *
 * <p>Consequence the eval relies on (the T13 judge split, 2026-06-08): the unguarded
 * {@code CoverageWindow = DateRange(OrderDate, DeliveryDate)} and the same computation with a
 * {@code commonPrecondition AllFieldsFilled(OrderDate, DeliveryDate)} guard compute the <b>same value
 * (or none) for every document</b> — the guard is <b>redundant</b>. This test pins that equivalence
 * against the real kernel ({@code IDocumentRtService.compute}).
 */
class DateRangeComputationLawsTest {

    private static final String COVERAGE = "/Order/CoverageWindow";
    private static final String RANGE = "01.01.2024-01.02.2024"; // dd.MM.yyyy-dd.MM.yyyy (the DateRangeType format)

    private static String unguarded() {
        BoundModel m = BoundModel.ofJson(RuntimeLaws.model("models/order.dm.json"));
        Computation comp = m.computation(m.field(COVERAGE)).name("CoverageWindowComp")
                .calc(Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")))
                .message(en("Coverage window.")).build();
        return m.add(comp).toJson();
    }

    private static String guarded() {
        BoundModel m = BoundModel.ofJson(RuntimeLaws.model("models/order.dm.json"));
        Computation comp = m.computation(m.field(COVERAGE)).name("CoverageWindowComp")
                .commonPrecondition(Dsl.allFieldsFilled(m.field("/Order/OrderDate"), m.field("/Order/DeliveryDate")))
                .calc(Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")))
                .message(en("Coverage window.")).build();
        return m.add(comp).toJson();
    }

    private static RuntimeLaws.Doc bothFilled() {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/OrderDate", "2024-01-01", 1, 1)
                .field("/Order/DeliveryDate", "2024-02-01", 1, 1);
    }

    private static RuntimeLaws.Doc emptyOperandFresh() {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/OrderDate", "2024-01-01", 1, 1)
                .emptyField("/Order/DeliveryDate", 1, 1);
    }

    private static RuntimeLaws.Doc emptyOperandStale() {
        return RuntimeLaws.doc().group("/Order", 1)
                .field("/Order/OrderDate", "2024-01-01", 1, 1)
                .emptyField("/Order/DeliveryDate", 1, 1)
                .field("/Order/CoverageWindow", "01.01.2020-01.01.2021", 1, 1);
    }

    @Test
    void computesTheRangeWhenBothOperandsAreFilled() {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(unguarded(), bothFilled());
        assertEquals(RANGE, RuntimeLaws.computedValue(out, COVERAGE).orElse(null),
                () -> "both filled computes the range; got " + out);
    }

    @Test
    void anEmptyOperandComputesNoValue() {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(unguarded(), emptyOperandFresh());
        assertTrue(RuntimeLaws.computedValue(out, COVERAGE).isEmpty(),
                () -> "an empty date operand is not evaluated — no value computed; got " + out);
    }

    @Test
    void anEmptyOperandClearsAStaleComputedValue() {
        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(unguarded(), emptyOperandStale());
        assertTrue(RuntimeLaws.cleared(out, COVERAGE),
                () -> "an empty operand clears the previously-computed value; got " + out);
        assertTrue(RuntimeLaws.computedValue(out, COVERAGE).isEmpty(),
                () -> "and leaves no value; got " + out);
    }

    @Test
    void theAllFieldsFilledGuardIsRedundant() {
        // The unguarded and guarded computations are indistinguishable across every scenario:
        // same value when both filled, no value (and stale cleared) when an operand is empty.
        // (Doc carries its instances, so each compute() gets a freshly-built doc.)
        String unguarded = unguarded(), guarded = guarded();
        assertEquals(RuntimeLaws.computedValue(RuntimeLaws.compute(unguarded, bothFilled()), COVERAGE),
                RuntimeLaws.computedValue(RuntimeLaws.compute(guarded, bothFilled()), COVERAGE),
                "both-filled: guarded computes the same value as unguarded");
        assertEquals(RuntimeLaws.computedValue(RuntimeLaws.compute(unguarded, emptyOperandFresh()), COVERAGE),
                RuntimeLaws.computedValue(RuntimeLaws.compute(guarded, emptyOperandFresh()), COVERAGE),
                "empty operand: guarded computes the same (nothing) as unguarded");
        assertEquals(RuntimeLaws.cleared(RuntimeLaws.compute(unguarded, emptyOperandStale()), COVERAGE),
                RuntimeLaws.cleared(RuntimeLaws.compute(guarded, emptyOperandStale()), COVERAGE),
                "stale value: guarded clears it exactly as unguarded does");
        assertFalse(RuntimeLaws.computedValue(RuntimeLaws.compute(guarded, emptyOperandFresh()), COVERAGE).isPresent(),
                "sanity: the guarded form also computes nothing on an empty operand");
    }
}
