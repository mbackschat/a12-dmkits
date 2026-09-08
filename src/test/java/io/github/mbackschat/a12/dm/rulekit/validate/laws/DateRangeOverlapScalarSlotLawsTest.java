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
 * KERNEL-FINDINGS §9 — <b>{@code AtLeastOneDateRangeOverlaps} refuses every non-scalar form in its SCALAR slot
 * with one class</b>, {@code MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON}, and a kind gate reports ahead
 * of it.
 *
 * <p><b>The refusal belongs to the SLOT, not to the operand form, and the {@code In}-slot pair is what proves
 * it.</b> Each refused shape is measured again in the list slot on the same fixture and admitted there, so
 * nothing here is explained by the operator rejecting a shape outright. That pairing is load-bearing rather
 * than decorative: [KF187] records this operator ACCEPTING a starred group in the {@code In} slot, so a
 * scalar-slot refusal read alone would contradict a measured row.
 *
 * <p><b>[KF187] has no scalar-slot column</b> — its two columns are the unstarred and starred GROUP shapes,
 * and its {@code AtLeastOneDateRangeOverlaps} row is explicitly scoped "group in the {@code In} slot". This
 * class is the missing column rather than a filled-in cell.
 *
 * <p><b>A HOMOGENEOUS group is the row that separates the two gates.</b> A group whose expansion mixes kinds
 * draws {@code MVK_VARYING_TYPES_NOT_ALLOWED} in BOTH slots — so the kind gate is not slot-specific — while a
 * group expanding to date ranges alone reaches the slot gate and draws its class. Measured with a mixed group
 * beside a homogeneous one, since a mixed group alone cannot distinguish "this slot refuses a group" from
 * "this operand offends the kind gate first".
 *
 * <p><b>The plural sibling is a different operator at this gate</b> and is included as the contrast [KF187]
 * already records: {@code DateRangesOverlap} refuses a group outright, {@code MVK_NO_GROUPS_ALLOWED}, in both
 * repetition shapes, and admits a starred field.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-06-01}; measured here on our own fixture.
 *
 * <p>Own domain (an insurance coverage schedule).
 */
class DateRangeOverlapScalarSlotLawsTest {

    private static final String GROUP = "/Cover";
    private static final String ERROR_REL = "../Ref";
    private static final String SCALAR_SLOT = "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON";
    private static final String KIND_GATE = "MVK_VARYING_TYPES_NOT_ALLOWED";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String SCALAR = "PolicyWindow";

    private static final String COVERAGE = DocumentModelDraft.named("coverage-windows").locale("en_US")
            .rootGroup("Cover", c -> c
                    .stringField("Ref", s -> { })
                    .dateRangeField("PolicyWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                    .group("Periods", g -> g.repeatable(10)
                            .dateRangeField("PeriodWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                            .numberField("Gate", n -> n.fractionDigits(0)))
                    .group("Spans", g -> g.repeatable(10)
                            .dateRangeField("SpanA", r -> r.format("dd.MM.yyyy").separator("-"))
                            .dateRangeField("SpanB", r -> r.format("dd.MM.yyyy").separator("-")))
                    .group("HomFixed", g -> g
                            .dateRangeField("HomA", r -> r.format("dd.MM.yyyy").separator("-"))
                            .dateRangeField("HomB", r -> r.format("dd.MM.yyyy").separator("-")))
                    .group("MixedG", g -> g
                            .dateRangeField("MixedWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                            .numberField("MixedCount", n -> n.fractionDigits(0)))
                    .group("Outer", g -> g.repeatable(10)
                            .group("Leaf", l -> l
                                    .dateRangeField("LeafWindow", r -> r.format("dd.MM.yyyy").separator("-")))))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(COVERAGE, GROUP, ERROR_REL, "FieldFilled(Ref) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    private record Form(String label, String operand) { }

    private static final List<Form> NON_SCALAR = List.of(
            new Form("a plain star", "Periods*/PeriodWindow"),
            new Form("a Having-filtered star", "Periods*/PeriodWindow Having [Periods/Gate] > 0"),
            new Form("a fixed homogeneous group", "HomFixed"),
            new Form("a homogeneous starred group", "Spans*"),
            new Form("a star above a nonrepeatable terminal", "Outer*/Leaf/LeafWindow"));

    /** Every non-scalar form in the scalar slot, each paired with the same operand admitted in the list slot. */
    @Test
    void everyNonScalarFormIsRefusedInTheScalarSlotAndAdmittedInTheListSlot() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Form form : NON_SCALAR) {
            String scalarSlot = form.label() + " in the SCALAR slot";
            String listSlot = form.label() + " in the In slot";
            actual.put(scalarSlot, verdict("AtLeastOneDateRangeOverlaps(" + form.operand() + " In " + SCALAR + ")"));
            actual.put(listSlot, verdict("AtLeastOneDateRangeOverlaps(" + SCALAR + " In " + form.operand() + ")"));
            expected.put(scalarSlot, SCALAR_SLOT);
            expected.put(listSlot, KernelLaws.ADMITTED);
        }
        actual.put("scalar first, star second — the admitted shape",
                verdict("AtLeastOneDateRangeOverlaps(" + SCALAR + " In Periods*/PeriodWindow)"));
        expected.put("scalar first, star second — the admitted shape", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the class belongs to the slot, not to the operand form");
    }

    /** A mixed expansion reaches the kind gate first, and that gate is not slot-specific. */
    @Test
    void aMixedKindExpansionReportsTheKindGateInBothSlots() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("mixed-kind group in the SCALAR slot",
                verdict("AtLeastOneDateRangeOverlaps(MixedG In " + SCALAR + ")"));
        actual.put("mixed-kind group in the In slot",
                verdict("AtLeastOneDateRangeOverlaps(" + SCALAR + " In MixedG)"));
        actual.put("homogeneous group in the SCALAR slot — the gate it pre-empts",
                verdict("AtLeastOneDateRangeOverlaps(HomFixed In " + SCALAR + ")"));
        actual.put("homogeneous group in the In slot — the gate it pre-empts",
                verdict("AtLeastOneDateRangeOverlaps(" + SCALAR + " In HomFixed)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("mixed-kind group in the SCALAR slot", KIND_GATE);
        expected.put("mixed-kind group in the In slot", KIND_GATE);
        expected.put("homogeneous group in the SCALAR slot — the gate it pre-empts", SCALAR_SLOT);
        expected.put("homogeneous group in the In slot — the gate it pre-empts", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the kind gate reports ahead of the slot gate, in either slot");
    }

    /** The singular sibling refuses a group outright — the split [KF187] records, kept adjacent to it. */
    @Test
    void thePluralSiblingRefusesAGroupOutrightWhereThisOperatorAdmitsOneInItsListSlot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DateRangesOverlap over a fixed group", verdict("DateRangesOverlap(HomFixed)"));
        actual.put("DateRangesOverlap over a starred group", verdict("DateRangesOverlap(Spans*)"));
        actual.put("DateRangesOverlap over a starred field", verdict("DateRangesOverlap(Periods*/PeriodWindow)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DateRangesOverlap over a fixed group", NO_GROUPS);
        expected.put("DateRangesOverlap over a starred group", NO_GROUPS);
        expected.put("DateRangesOverlap over a starred field", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the singular and plural forms disagree at this gate");
    }
}
