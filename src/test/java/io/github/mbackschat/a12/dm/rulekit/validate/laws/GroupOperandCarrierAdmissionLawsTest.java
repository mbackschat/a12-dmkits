package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>which CARRIERS admit a group-scope operand at all</b>, swept 2026-08-14 across every
 * carrier [IG136] names as an unmeasured group-expansion candidate.
 *
 * <p><b>Why a sweep rather than a row per fix.</b> The 2026-08-14 group-expansion session settled nine defects and
 * one non-defect, and the non-defect is the reason this file exists: a filtered group star reads as a live
 * validation/compute divergence in the source and is simply <b>refused</b> by the kernel
 * ({@code MVK_NO_GROUPS_ALLOWED}), so the divergence is unreachable by authoring. Four of that session's nine
 * predictions were settled only by asking the kernel first. Admission is a question no amount of source reading
 * answers, and a runtime row over a rejected shape compares two silences while passing — so the whole candidate
 * list is swept here BEFORE any of it earns a differential.
 *
 * <p><b>The table is asserted as a whole, not row by row</b>, using {@link KernelLaws#checkModel} — a row-by-row
 * assertion aborts at the first divergence and hides the rest of the matrix, which is exactly what a reader needs
 * to see when a kernel upgrade shifts one gate.
 *
 * <p>Every group in the fixture is <b>homogeneous</b> in field kind. That is load-bearing rather than tidy: a mixed
 * group's expansion offends the kind gate first, so its rejection cannot distinguish "this carrier refuses a group
 * operand" from "this carrier refuses these kinds" — the trap {@code FieldValuesNotUniqueAdmissionLawsTest}
 * documents on its own {@code Counts} group.
 *
 * <p>Already measured elsewhere and therefore NOT repeated here: the four value aggregates over a NUMBER group
 * ({@code AggregateGroupOperandLawsTest}), {@code FieldValuesNotUnique} over both group shapes
 * ({@code FieldValuesNotUniqueAdmissionLawsTest}), {@code FirstFilledValue} over a non-repeatable group
 * ({@code docsnippets.NewShapesSnippetsTest}), and the filtered group star's refusal
 * ({@code adapter.laws.FilteredGroupStarFillDiffTest}).
 */
class GroupOperandCarrierAdmissionLawsTest {

    private static final String GROUP = "/Claim";

    /**
     * A motor-claim model carrying one HOMOGENEOUS group per field kind, in both repetition shapes, because the
     * repetition shape decides which spelling is even legal: a repeatable group operand must carry its star
     * ({@code MVK_NO_WILDCARD}) and a non-repeatable one must not ({@code MVK_INVALID_WILDCARD}), so a carrier
     * needs both fixtures to be swept at all.
     */
    private static String claim() {
        return DocumentModelDraft.named("motor-claim").locale("en_US")
                .rootGroup("Claim", root -> root
                        .stringField("Reference", f -> { })
                        .numberField("ExcessDue", n -> n.fractionDigits(0, 2))
                        .dateField("ClaimedOn", d -> d.format("dd.MM.yyyy"))
                        .group("Assessment", g -> g
                                .numberField("Deductible", n -> n.fractionDigits(0, 2))
                                .numberField("Excess", n -> n.fractionDigits(0, 2)))
                        .group("Payouts", g -> g.repeatable(20)
                                .numberField("Gross", n -> n.fractionDigits(0, 2))
                                .numberField("Net", n -> n.fractionDigits(0, 2)))
                        .group("Milestones", g -> g
                                .dateField("ReportedOn", d -> d.format("dd.MM.yyyy"))
                                .dateField("SettledOn", d -> d.format("dd.MM.yyyy")))
                        .group("Inspections", g -> g.repeatable(20)
                                .dateField("VisitedOn", d -> d.format("dd.MM.yyyy"))
                                .dateField("ClosedOn", d -> d.format("dd.MM.yyyy")))
                        .group("Contact", g -> g
                                .stringField("Email", f -> { })
                                .stringField("Phone", f -> { }))
                        .group("Adjusters", g -> g.repeatable(20)
                                .stringField("Name", f -> { })
                                .stringField("Region", f -> { }))
                        .group("Cover", g -> g
                                .dateRangeField("PolicyWindow", r -> r.format("dd.MM.yyyy").separator("-"))
                                .dateRangeField("ExtensionWindow", r -> r.format("dd.MM.yyyy").separator("-")))
                        .group("Periods", g -> g.repeatable(20)
                                .dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"))
                                .dateRangeField("Grace", r -> r.format("dd.MM.yyyy").separator("-")))
                        .group("Charges", g -> g.repeatable(20)
                                .numberField("Amount", n -> n.fractionDigits(0, 2)))
                        .group("Lines", g -> g.repeatable(20)
                                .numberField("Amount", n -> n.fractionDigits(0, 2))
                                .stringField("Sku", f -> { })))
                .build().toJson();
    }

    private static final String CLAIM_JSON = claim();

    /** One swept shape: its label, the condition body, and the verdict the sweep records. */
    private record Shape(String label, String body) { }

    /**
     * Every candidate, guarded so the rule references its own error field
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) — the gate that would otherwise mask every verdict in the table.
     */
    private static List<Shape> shapes() {
        List<Shape> s = new ArrayList<>();

        // ── the operand-list Min/Max, which are NOT the MinValue/MaxValue aggregates already measured.
        //    Their operands are VALUE expressions, so a field rides in brackets and a group must too. ──
        s.add(new Shape("Min/operand-list/non-repeatable-group", "Min([Assessment], 5) > 0"));
        s.add(new Shape("Max/operand-list/non-repeatable-group", "Max([Assessment], 5) > 0"));
        s.add(new Shape("Min/operand-list/starred-repeatable-group", "Min([Payouts*], 5) > 0"));
        s.add(new Shape("Min/operand-list/field-control", "Min([Assessment/Deductible], 5) > 0"));

        // ── the aggregates over a DATE group. Compared against a DATE field of the same declared format:
        //    a `> 0` comparison draws MVK_INVALID_COMPARE_TO_DATE on the field control too, so it measures
        //    the comparison rather than the operand. ──
        s.add(new Shape("MinValue/date-group/non-repeatable", "[ClaimedOn] > MinValue(Milestones)"));
        s.add(new Shape("MinValue/date-group/starred-repeatable", "[ClaimedOn] > MinValue(Inspections*)"));
        s.add(new Shape("MaxValue/date-group/starred-repeatable", "[ClaimedOn] > MaxValue(Inspections*)"));
        s.add(new Shape("MinValue/date-field-control", "[ClaimedOn] > MinValue(Inspections*/VisitedOn)"));

        // ── the seven field-list FILL quantifiers over a group operand ──
        for (String op : new String[] {
            "AllFieldsFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "NotAllFieldsFilled",
            "FieldsNotCollectivelyFilled", "NotExactlyOneFieldFilled", "MoreThanOneFieldFilled",
        }) {
            s.add(new Shape(op + "/non-repeatable-group", op + "(Contact)"));
            s.add(new Shape(op + "/starred-repeatable-group", op + "(Adjusters*)"));
        }

        // ── the value-list quantifiers over a group operand ──
        for (String op : new String[] {
            "AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",
            "NotAllFieldValuesIncludedInValueList",
        }) {
            s.add(new Shape(op + "/non-repeatable-group", op + "(Contact In \"x\", \"y\")"));
            s.add(new Shape(op + "/starred-repeatable-group", op + "(Adjusters* In \"x\", \"y\")"));
            s.add(new Shape(op + "/field-control", op + "(Contact/Email In \"x\", \"y\")"));
        }

        // ── FirstFilledValue's STARRED group form (the non-repeatable one is measured in the doc snippets) ──
        s.add(new Shape("FirstFilledValue/starred-repeatable-group", "FirstFilledValue(Adjusters*) == \"x\""));

        // ── NumberOfFilledFields over the unstarred non-repeatable group ──
        s.add(new Shape("NumberOfFilledFields/non-repeatable-group", "NumberOfFilledFields(Contact) > 0"));

        // ── DateRangesOverlap: filteredStarCells' other caller, which IF287 left deliberately unexpanded ──
        s.add(new Shape("DateRangesOverlap/non-repeatable-group", "DateRangesOverlap(Cover)"));
        s.add(new Shape("DateRangesOverlap/starred-repeatable-group", "DateRangesOverlap(Periods*)"));
        s.add(new Shape("DateRangesOverlap/field-control",
                "DateRangesOverlap(Cover/PolicyWindow, Cover/ExtensionWindow)"));
        s.add(new Shape("AtLeastOneDateRangeOverlaps/starred-repeatable-group",
                "AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*)"));
        s.add(new Shape("AtLeastOneDateRangeOverlaps/field-control",
                "AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*/Window)"));

        // ── the MIXED fill-quantifier list: one plain operand beside one FILTERED one (fillScopeInfo) ──
        s.add(new Shape("AtLeastOneFieldFilled/plain-plus-filtered-field",
                "AtLeastOneFieldFilled(Charges*/Amount, Lines*/Amount Having [Lines/Sku] == \"A\")"));
        s.add(new Shape("AllFieldsFilled/plain-plus-filtered-field",
                "AllFieldsFilled(Charges*/Amount, Lines*/Amount Having [Lines/Sku] == \"A\")"));
        s.add(new Shape("AtLeastOneFieldFilled/filtered-field-alone",
                "AtLeastOneFieldFilled(Lines*/Amount Having [Lines/Sku] == \"A\")"));

        // ── the `$` OUTER operand shapes. Whether an aggregate can carry one at all is the precondition for
        //    `aggOperandVals`' context mismatch being reachable: it decides the operand's FILL on the ambient
        //    context while reading its VALUE through `ctxOf`, which is the outer one for a `$` node. Each row
        //    is authored with TWO operands, because a one-operand aggregate draws the arity gate first and that
        //    masks the verdict this table is asking for. ──
        s.add(new Shape("Sum/outer-marked-group-operand", "Sum($/Claim/Assessment, [ExcessDue]) > 0"));
        s.add(new Shape("Sum/outer-marked-field-operands",
                "Sum($/Claim/Assessment/Deductible, $/Claim/Assessment/Excess) > 0"));
        s.add(new Shape("Sum/unmarked-field-operands-control",
                "Sum(Assessment/Deductible, Assessment/Excess) > 0"));

        return s;
    }

    /** The verdict line a shape produces: {@code ACCEPT} or the first {@code MVK_} code it draws. */
    private static String verdict(Shape shape) {
        ValidationResult r = KernelLaws.checkModel(CLAIM_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + shape.body());
        if (r.ok()) {
            return "ACCEPT";
        }
        return r.diagnostics().stream().flatMap(d -> d.code().stream()).filter(c -> c.startsWith("MVK_"))
                .findFirst().orElseGet(() -> "REJECT/" + r.diagnostics().stream()
                        .flatMap(d -> d.code().stream()).findFirst().orElse("no-code"));
    }

    /**
     * The measured verdict per shape, asserted as one table.
     *
     * <p><b>What each verdict decides</b>, since this table is the gate the runtime work is planned against:
     *
     * <ul>
     * <li><b>The operand-list {@code Min}/{@code Max} refuse a group operand</b> — so the missing group branch in
     *     {@code minMaxOperandVals} is unreachable by authoring. Widening it "on symmetry" with its measured
     *     {@code aggOperandVals} twin would have been a change to dead code, which is exactly the widening
     *     {@code IF287} declined to make on the strength of a source reading.</li>
     * <li><b>{@code MinValue}/{@code MaxValue} over a DATE group are admitted</b> — so {@code dateAggregate}'s
     *     group-blindness (it asks {@code declaredKind} of a group path, which is null, and takes the NUMERIC
     *     branch with its {@code 0} empty identity) is a live runtime question.</li>
     * <li><b>Both date-range forms disagree with each other</b>, which is the row a reader would least expect:
     *     {@code DateRangesOverlap} refuses a group operand outright, while the plural
     *     {@code AtLeastOneDateRangeOverlaps} admits one in its {@code In} slot. IF287 left the shared
     *     {@code filteredStarCells} unexpanded because the date-range caller's group behaviour was unmeasured;
     *     half of that is now measured as unreachable and the other half as reachable.</li>
     * <li><b>A {@code $}-marked aggregate operand is refused</b> {@code MVK_INVALID_OUTER_ITERATION}, for a group
     *     and a field operand alike, while the same list unmarked is admitted — so the refusal is about the
     *     marker and not about the operands. That bears on the context mismatch in {@code aggOperandVals}, which
     *     decides an operand's fill on the ambient context while reading its value through {@code ctxOf}, the
     *     OUTER context for a {@code $} node. <b>Scope of this row, stated because it is narrower than the claim
     *     it supports:</b> the refusal is measured at the TOP LEVEL of a condition only. The nested spelling — a
     *     {@code Sum} inside an overlap predicate's {@code Having}, where {@code $} is otherwise meaningful — is
     *     NOT measured here: both the marked and the unmarked form of it draw
     *     {@code MVK_NO_ITERATION_FOR_WILDCARD}, so that probe measured the {@code Having} construction rather
     *     than the marker, exactly as two discarded rows above did. Whether some other construct can place an
     *     {@code outer} node into an aggregate's operand list is therefore open, and it is a question about the
     *     PARSER rather than about this table.</li>
     * <li><b>Every fill quantifier, every value-list quantifier, and {@code FirstFilledValue} admit both group
     *     shapes</b> — 24 rows, and the reason [IG135] and the value-list candidate are runtime work rather
     *     than speculation.</li>
     * </ul>
     */
    private static String expected(String label) {
        return switch (label) {
            case "Min/operand-list/non-repeatable-group",
                 "Max/operand-list/non-repeatable-group",
                 "Min/operand-list/starred-repeatable-group",
                 "DateRangesOverlap/non-repeatable-group",
                 "DateRangesOverlap/starred-repeatable-group" -> "MVK_NO_GROUPS_ALLOWED";
            case "Sum/outer-marked-group-operand",
                 "Sum/outer-marked-field-operands" -> "MVK_INVALID_OUTER_ITERATION";
            default -> "ACCEPT";
        };
    }

    @Test
    void everyCandidateCarriersVerdictIsTheMeasuredOne() {
        List<String> mismatches = new ArrayList<>();
        for (Shape shape : shapes()) {
            String actual = verdict(shape);
            if (!expected(shape.label()).equals(actual)) {
                mismatches.add("%s: expected %s, got %s%n    condition: %s"
                        .formatted(shape.label(), expected(shape.label()), actual, shape.body()));
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), mismatches,
                "the group-operand admission table shifted; each line is one carrier whose gate moved");
    }
}
