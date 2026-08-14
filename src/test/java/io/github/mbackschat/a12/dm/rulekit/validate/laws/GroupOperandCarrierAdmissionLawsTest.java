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
 * <p><b>Every group a row uses AS a group operand is homogeneous in field kind</b>, and that is load-bearing rather
 * than tidy: a mixed group's expansion offends the kind gate first, so its rejection could not distinguish "this
 * carrier refuses a group operand" from "this carrier refuses these kinds" — the trap
 * {@code FieldValuesNotUniqueAdmissionLawsTest} documents on its own {@code Counts} group. The claim is scoped to
 * those groups deliberately: {@code Lines} mixes a NUMBER and a STRING and is reached only through
 * the flatten of {@code Lines*} then {@code Amount}, and {@code [Lines/Sku]}, never as a group operand. A blanket "every group here is
 * homogeneous" was the wording a review corrected, because it is the sentence a later reader would rely on when
 * adding a group-operand row over exactly that group.
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
                        .group("Note", g -> g.stringField("Text", f -> { }))
                        .group("Lines", g -> g.repeatable(20)
                                .numberField("Amount", n -> n.fractionDigits(0, 2))
                                .stringField("Sku", f -> { })))
                .build().toJson();
    }

    private static final String CLAIM_JSON = claim();

    /**
     * One swept shape: its label, the condition body, and the measured verdict.
     *
     * <p>The verdict lives <b>in</b> the record rather than in a lookup keyed by label, because the lookup form
     * this replaced ended in a {@code default -> "ACCEPT"} arm and was therefore <b>fail-OPEN</b>: a typo or a
     * rename in any label silently converted a refusal expectation into an ACCEPT expectation and the assertion
     * still passed. With eleven hand-written labels and two that are string-concatenated in loops, that was one
     * character away from a silent flip.
     */
    private record Shape(String label, String body, String verdict) {

        static Shape accepted(String label, String body) {
            return new Shape(label, body, "ACCEPT");
        }

        static Shape refused(String label, String body, String code) {
            return new Shape(label, body, code);
        }
    }

    /**
     * Every candidate, guarded so the rule references its own error field
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) — the gate that would otherwise mask every verdict in the table.
     */
    private static List<Shape> shapes() {
        List<Shape> s = new ArrayList<>();
        String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";

        // ── The operand-list Min/Max, which are NOT the MinValue/MaxValue aggregates measured elsewhere. Their
        //    operands are VALUE expressions, so an entity rides in brackets.
        //
        //    A GATE-ORDER CAVEAT that a cold review caught, and it narrows what the starred row can claim:
        //    `CheckFeldWertOpImpl.check` runs `checkIsField` (which throws the group code) BEFORE
        //    `checkNoWildCards`, and `[...]` admits NO wildcard operand of any kind. So the starred-group row
        //    below cannot distinguish "a starred group is refused" from "brackets refuse wildcards", and no field
        //    control for it can exist — which is why the starred FIELD row is here to state the broader gate
        //    instead of pretending to be that control. MEASURED: the starred FIELD draws MVK_NO_WILDCARDS_ALLOWED — a
        //    DIFFERENT code from the group rows, which is what makes the two gates separable at all. The honest
        //    joint claim is the stronger one: Min/Max admit no group operand and no wildcard operand. ──
        s.add(Shape.refused("Min/operand-list/non-repeatable-group", "Min([Assessment], 5) > 0", NO_GROUPS));
        s.add(Shape.refused("Max/operand-list/non-repeatable-group", "Max([Assessment], 5) > 0", NO_GROUPS));
        s.add(Shape.refused("Min/operand-list/starred-repeatable-group", "Min([Payouts*], 5) > 0", NO_GROUPS));
        s.add(Shape.refused("Max/operand-list/starred-repeatable-group", "Max([Payouts*], 5) > 0", NO_GROUPS));
        s.add(Shape.refused("Min/operand-list/starred-FIELD-not-a-control",
                "Min([Payouts*/Gross], 5) > 0", "MVK_NO_WILDCARDS_ALLOWED"));
        s.add(Shape.accepted("Min/operand-list/field-control", "Min([Assessment/Deductible], 5) > 0"));
        s.add(Shape.accepted("Max/operand-list/field-control", "Max([Assessment/Deductible], 5) > 0"));

        // ── The aggregates over a DATE group. Compared against a DATE field of the same declared format: a
        //    `> 0` comparison draws MVK_INVALID_COMPARE_TO_DATE on the field control too, so it would measure the
        //    comparison rather than the operand. Both carriers appear in both repetition shapes, because a table
        //    that measured one and asserted both is what a review caught here. ──
        s.add(Shape.accepted("MinValue/date-group/non-repeatable", "[ClaimedOn] > MinValue(Milestones)"));
        s.add(Shape.accepted("MaxValue/date-group/non-repeatable", "[ClaimedOn] > MaxValue(Milestones)"));
        s.add(Shape.accepted("MinValue/date-group/starred-repeatable", "[ClaimedOn] > MinValue(Inspections*)"));
        s.add(Shape.accepted("MaxValue/date-group/starred-repeatable", "[ClaimedOn] > MaxValue(Inspections*)"));
        s.add(Shape.accepted("MinValue/date-field-control", "[ClaimedOn] > MinValue(Inspections*/VisitedOn)"));
        s.add(Shape.accepted("MaxValue/date-field-control", "[ClaimedOn] > MaxValue(Inspections*/VisitedOn)"));

        // ── The three shapes `dateGroupFill` turns out to reach but does not name, all admission-first because a
        //    refusal closes each without any runtime work (IG136 item (m)). ──
        s.add(Shape.refused("Sum/date-group", "Sum(Milestones) > 0", "MVK_NO_NUMBER"));
        s.add(Shape.refused("MinValue/mixed-kind-group", "MinValue(Lines*) > 0", "MVK_NOT_SORTABLE"));
        s.add(Shape.accepted("MinValue/date-group-plus-date-field",
                "[ClaimedOn] > MinValue(Milestones, ClaimedOn)"));

        // ── the seven field-list FILL quantifiers over a group operand, each with its explicit-field control ──
        for (String op : new String[] {
            "AllFieldsFilled", "NoFieldFilled", "AtLeastOneFieldFilled", "NotAllFieldsFilled",
            "FieldsNotCollectivelyFilled", "NotExactlyOneFieldFilled", "MoreThanOneFieldFilled",
        }) {
            s.add(Shape.accepted(op + "/non-repeatable-group", op + "(Contact)"));
            s.add(Shape.accepted(op + "/starred-repeatable-group", op + "(Adjusters*)"));
            s.add(Shape.accepted(op + "/field-control", op + "(Contact/Email, Contact/Phone)"));
        }

        // ── the value-list quantifiers over a group operand ──
        for (String op : new String[] {
            "AtLeastOneFieldValueIncludedInValueList", "NoFieldValueIncludedInValueList",
            "NotAllFieldValuesIncludedInValueList",
        }) {
            s.add(Shape.accepted(op + "/non-repeatable-group", op + "(Contact In \"x\", \"y\")"));
            s.add(Shape.accepted(op + "/starred-repeatable-group", op + "(Adjusters* In \"x\", \"y\")"));
            s.add(Shape.accepted(op + "/field-control", op + "(Contact/Email In \"x\", \"y\")"));
        }

        // ── THE EXPANSION-VISIBILITY DISCRIMINATORS. Every ACCEPT above is over a HOMOGENEOUS group, so on its
        //    own the table is equally consistent with "this carrier EXPANDS the group and type-checks the result"
        //    and "this carrier waves anything reached through a group past its type gates". The runtime work
        //    assumes the first, so the difference has to be measured, not assumed. A DATE group against a STRING
        //    value list is refusable only if the expansion is what gets checked. (The sibling
        //    `FieldValuesNotUniqueAdmissionLawsTest.aGroupWhoseExpansionMixesCategoriesReportsTheMixingGate` is
        //    the same discriminator for its own carrier; this table had no analogue until a review said so.) ──
        s.add(Shape.refused("AtLeastOneFieldValueIncludedInValueList/date-group-vs-string-list",
                "AtLeastOneFieldValueIncludedInValueList(Milestones In \"x\", \"y\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"));
        s.add(Shape.accepted("AtLeastOneFieldValueIncludedInValueList/string-group-control",
                "AtLeastOneFieldValueIncludedInValueList(Contact In \"x\", \"y\")"));

        // ── The ARITY discriminator: `Note` expands to exactly ONE field. If the param law counted the
        //    expansion, a one-field group would fail it exactly as the bare field does; if a group slot is
        //    "already many" the way a starred slot is, it passes. MEASURED: the group is ACCEPTED and the bare
        //    field draws MVK_PARAMSIZE_INVALIDN, so the arity gate does NOT count the expansion — the pair is
        //    what shows it, and neither row alone does. ──
        s.add(Shape.accepted("MoreThanOneFieldFilled/single-field-group", "MoreThanOneFieldFilled(Note)"));
        s.add(Shape.refused("MoreThanOneFieldFilled/that-single-field-directly",
                "MoreThanOneFieldFilled(Note/Text)", "MVK_PARAMSIZE_INVALIDN"));

        // ── FirstFilledValue's STARRED group form (the non-repeatable one is measured in the doc snippets) ──
        s.add(Shape.accepted("FirstFilledValue/starred-repeatable-group", "FirstFilledValue(Adjusters*) == \"x\""));
        s.add(Shape.accepted("FirstFilledValue/field-control", "FirstFilledValue(Adjusters*/Name) == \"x\""));

        // ── NumberOfFilledFields over the unstarred non-repeatable group ──
        s.add(Shape.accepted("NumberOfFilledFields/non-repeatable-group", "NumberOfFilledFields(Contact) > 0"));
        s.add(Shape.accepted("NumberOfFilledFields/field-control",
                "NumberOfFilledFields(Contact/Email, Contact/Phone) > 0"));

        // ── The date-range family, whose two forms DISAGREE — the row a reader would least expect, and the one
        //    that reopened a question IF287 had closed on the strength of "unmeasured". ──
        s.add(Shape.refused("DateRangesOverlap/non-repeatable-group", "DateRangesOverlap(Cover)", NO_GROUPS));
        s.add(Shape.refused("DateRangesOverlap/starred-repeatable-group", "DateRangesOverlap(Periods*)", NO_GROUPS));
        s.add(Shape.accepted("DateRangesOverlap/field-control",
                "DateRangesOverlap(Cover/PolicyWindow, Cover/ExtensionWindow)"));
        s.add(Shape.accepted("AtLeastOneDateRangeOverlaps/starred-repeatable-group",
                "AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*)"));
        s.add(Shape.accepted("AtLeastOneDateRangeOverlaps/field-control",
                "AtLeastOneDateRangeOverlaps(Cover/PolicyWindow In Periods*/Window)"));

        // ── the MIXED fill-quantifier list: one plain operand beside one FILTERED one (fillScopeInfo). The
        //    third row is the CONTROL, not a live case: with a single Having-bearing star nothing diverts the
        //    dispatch onto the scope path, so the filter is honoured there. ──
        s.add(Shape.accepted("AtLeastOneFieldFilled/plain-plus-filtered-field",
                "AtLeastOneFieldFilled(Charges*/Amount, Lines*/Amount Having [Lines/Sku] == \"A\")"));
        s.add(Shape.accepted("AllFieldsFilled/plain-plus-filtered-field",
                "AllFieldsFilled(Charges*/Amount, Lines*/Amount Having [Lines/Sku] == \"A\")"));
        s.add(Shape.accepted("AtLeastOneFieldFilled/filtered-field-alone-CONTROL",
                "AtLeastOneFieldFilled(Lines*/Amount Having [Lines/Sku] == \"A\")"));

        // ── The `$` OUTER marker. These rows measure a UNIVERSAL gate, not anything about aggregates, and
        //    saying so is a correction: a cold review traced MVK_INVALID_OUTER_ITERATION to its single locus,
        //    `CheckEntitySpecImpl.checkOuter` (`ctx.outer != null && !isInFilterCondition()`), which names no
        //    carrier at all — and KERNEL-FINDINGS already records "`$` is filter-only". The non-aggregate row is
        //    what makes that visible in the table: the same marker on a bare comparison draws the same code, so
        //    the aggregate rows say nothing about `Sum`. The reachable spelling is `$` INSIDE a filter, which
        //    these rows do NOT cover — see the class javadoc. ──
        s.add(Shape.refused("outer-marker/bare-comparison-NOT-an-aggregate", "[$/Claim/ExcessDue] > 0",
                "MVK_INVALID_OUTER_ITERATION"));
        s.add(Shape.refused("outer-marker/under-Sum-group-operand", "Sum($/Claim/Assessment, [ExcessDue]) > 0",
                "MVK_INVALID_OUTER_ITERATION"));
        s.add(Shape.refused("outer-marker/under-Sum-field-operands",
                "Sum($/Claim/Assessment/Deductible, $/Claim/Assessment/Excess) > 0",
                "MVK_INVALID_OUTER_ITERATION"));
        s.add(Shape.accepted("outer-marker/unmarked-control",
                "Sum(Assessment/Deductible, Assessment/Excess) > 0"));

        // ── The `$` marker WHERE IT IS LEGAL: inside a filter. The rows above establish only that it is refused
        //    outside one, so they cannot say whether an aggregate operand can carry it — and that question is the
        //    precondition for `aggOperandVals`' context mismatch being reachable at all. A first attempt drew
        //    MVK_NO_ITERATION_FOR_WILDCARD in its marked AND unmarked form, which means its filter body simply
        //    referenced no field on a starred level; the filter here does, so the marker is the only variable. ──
        String inFilter = "[/Claim/Lines/Sku] == \"A\"";
        s.add(Shape.accepted("outer-marker/in-filter-unmarked-control",
                "Sum(Lines*/Amount Having (" + inFilter
                        + " And Sum(Assessment/Deductible, Assessment/Excess) > 0)) > 0"));
        s.add(Shape.accepted("outer-marker/in-filter-field-operands",
                "Sum(Lines*/Amount Having (" + inFilter
                        + " And Sum($/Claim/Assessment/Deductible, $/Claim/Assessment/Excess) > 0)) > 0"));
        s.add(Shape.accepted("outer-marker/in-filter-GROUP-operand",
                "Sum(Lines*/Amount Having (" + inFilter
                        + " And Sum($/Claim/Assessment, $/Claim/ExcessDue) > 0)) > 0"));

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

    @Test
    void everyCandidateCarriersVerdictIsTheMeasuredOne() {
        List<String> mismatches = new ArrayList<>();
        for (Shape shape : shapes()) {
            String actual = verdict(shape);
            if (!shape.verdict().equals(actual)) {
                mismatches.add("%s: expected %s, got %s%n    condition: %s"
                        .formatted(shape.label(), shape.verdict(), actual, shape.body()));
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), mismatches,
                "the group-operand admission table shifted; each line is one carrier whose gate moved");
    }
}
