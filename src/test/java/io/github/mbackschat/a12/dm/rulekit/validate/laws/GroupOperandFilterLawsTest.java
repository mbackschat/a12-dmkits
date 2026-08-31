package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §4/§11 — <b>a {@code Having} filter cannot attach to a GROUP operand</b>, on the carriers where
 * a group operand is admitted at all.
 *
 * <p><b>Why this file exists.</b> a12-kernel-lean's review of our group-operand handback listed three residuals;
 * two were measured and one was left open across four rounds: <em>"the reached-{@code Having} OMISSION contrast
 * for the group form"</em> — does a filter that reaches a row change the message type the way it does for the
 * starred FIELD form? We declined to assert it was reachable without trying to author it, which was right, and
 * then did not try, which was not.
 *
 * <p><b>Authored, it turns out to be UNASKABLE rather than unmeasured.</b> A filter belongs to a field operand
 * (the flatten of {@code Lines*} then {@code Amount}, with a {@code Having} on it); attaching one to a group
 * operand is refused outright, so there is no
 * reached-{@code Having} state for the group form whose message type could be contrasted. That closes the
 * residual as a non-question, the same shape §17.5 reached for the {@code Min}/{@code Max} carrier — and it is
 * a better answer than the measurement would have been, because it holds for every fill state at once.
 *
 * <p><b>The unfiltered controls are what make it a measurement.</b> Each refused row is paired with the same
 * operand list minus its filter, which is admitted — so the refusal is charged to the filter and not to the
 * group operand, which these carriers do accept.
 *
 * <p><b>Three axes were added 2026-08-31 on a12-kernel-lean's {@code SPEC-2026-08-31-09}, which claimed all
 * three.</b> Their entry names the group COUNT carrier, "either operand position", and "both the validation and
 * computation arms" — and every row this file and its two sibling locks held was a SINGLE-operand carrier on the
 * validation arm, so two thirds of that claim was untested here rather than confirmed. All three now measure as
 * claimed. The fourth axis is ours and no entry asked for it: the earlier locks caught the refusal as a THROW at
 * code generation, so which layer refuses had never been separated — static arbitration refuses it too, which is
 * the only answer a consumer authoring against {@code checkConsistency} ever sees.
 */
class GroupOperandFilterLawsTest {

    private static final String GROUP = "/StockCount";

    /**
     * Two repeatable groups, each with fields of one comparability category, so the operand list's own kind gate
     * cannot fire and mask the filter answer. {@code Marker} is the error field and {@code CountedTotal} the
     * computation target, both outside the repeatable groups. The second group exists so a two-operand list can
     * carry the filter at either position. {@code Adjustments} is all-numeric so a VALUE aggregate can take it,
     * which is what lets the layer row below vary the carrier without varying the operand's admissibility.
     */
    private static final String MODEL = DocumentModelDraft.named("stock-count").locale("en_US")
            .rootGroup("StockCount", root -> root
                    .stringField("Marker", s -> { })
                    .field("CountedTotal", FieldKind.NUMBER)
                    .group("Counts", counts -> counts.repeatable(50)
                            .stringField("CounterInitials", s -> { })
                            .stringField("BinCode", s -> { }))
                    .group("Recounts", recounts -> recounts.repeatable(50)
                            .stringField("RecounterInitials", s -> { })
                            .stringField("BinCode", s -> { }))
                    .group("Adjustments", adj -> adj.repeatable(50)
                            .field("Qty", FieldKind.NUMBER)
                            .field("Delta", FieldKind.NUMBER)))
            .build().toJson();

    /**
     * One filter per group, over that group's OWN field — so a positional row varies only where the filter
     * attaches. A single shared filter would have varied two things at the second position, since a filter
     * naming another group's field is a different question with a refusal of its own.
     */
    private static final String COUNTS_FILTER = " Having [Counts/BinCode] == \"A1\"";
    private static final String RECOUNTS_FILTER = " Having [Recounts/BinCode] == \"A1\"";
    private static final String ADJUSTMENTS_FILTER = " Having [Adjustments/Qty] > 1";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String ADMITTED = "admitted";

    @Test
    void aHavingFilterCannotAttachToAGroupOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("FieldValuesNotUnique(group) — unfiltered control", verdict(
                "FieldValuesNotUnique(Counts*)"));
        actual.put("FieldValuesNotUnique(group Having …)", verdict(
                "FieldValuesNotUnique(Counts*" + COUNTS_FILTER + ")"));
        actual.put("FirstFilledValue(group) — unfiltered control", verdict(
                "FirstFilledValue(Counts*) == \"x\""));
        actual.put("FirstFilledValue(group Having …)", verdict(
                "FirstFilledValue(Counts*" + COUNTS_FILTER + ") == \"x\""));
        // The FIELD form of the same filter, so the refusals above cannot be read as "this model cannot carry a
        // Having at all" — which is the control that turns the pair into an attribution.
        actual.put("the FIELD form of the same filter — the shape a Having belongs to", verdict(
                "FieldValuesNotUnique(Counts*/CounterInitials" + COUNTS_FILTER + ")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FieldValuesNotUnique(group) — unfiltered control", ADMITTED);
        expected.put("FieldValuesNotUnique(group Having …)", NO_GROUPS);
        expected.put("FirstFilledValue(group) — unfiltered control", ADMITTED);
        expected.put("FirstFilledValue(group Having …)", NO_GROUPS);
        expected.put("the FIELD form of the same filter — the shape a Having belongs to", ADMITTED);
        assertEquals(expected, actual,
                "a filter attaches to a FIELD operand, never to a group one — so the reached-Having message-type "
                        + "contrast has no group-form state to be measured in");
    }

    /**
     * The carrier a12-kernel-lean names in {@code SPEC-2026-08-31-09} — the group COUNT, whose operands are all
     * group paths — at <b>both</b> operand positions. The two carriers already locked above take a single group
     * operand, so position was untestable on them and this is what the entry actually asked.
     *
     * <p>Three controls, all in this one run. The unfiltered pair holds everything fixed but the filter, so the
     * refusal is charged to attaching a filter rather than to the list; and each filter is shown well-formed by
     * admitting it over the FIELD list of its own group, so neither row can be read as a malformed filter.
     */
    @Test
    void theGROUPCOUNTRefusesAFilterAtEitherOperandPosition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the unfiltered pair — control", verdict(
                "NumberOfFilledGroups(Counts*, Recounts*) > 0"));
        actual.put("filter on the FIRST operand", verdict(
                "NumberOfFilledGroups(Counts*" + COUNTS_FILTER + ", Recounts*) > 0"));
        actual.put("filter on the SECOND operand", verdict(
                "NumberOfFilledGroups(Counts*, Recounts*" + RECOUNTS_FILTER + ") > 0"));
        actual.put("the first filter over ITS field list — well-formedness control", verdict(
                "NumberOfFilledFields(Counts*/CounterInitials" + COUNTS_FILTER + ") > 0"));
        actual.put("the second filter over ITS field list — well-formedness control", verdict(
                "NumberOfFilledFields(Recounts*/RecounterInitials" + RECOUNTS_FILTER + ") > 0"));
        // The operator-level spelling: a filter AFTER the operand list, which would filter the carrier rather
        // than an operand. a12-kernel-lean's SPEC-2026-08-31-09 claims it is a parse error, so there is no
        // spelling that attaches a filter to a group-operand carrier at all.
        actual.put("a filter after the operand LIST — the operator-level spelling", verdict(
                "NumberOfFilledGroups(Counts*, Recounts*)" + COUNTS_FILTER + " > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the unfiltered pair — control", ADMITTED);
        expected.put("filter on the FIRST operand", NO_GROUPS);
        expected.put("filter on the SECOND operand", NO_GROUPS);
        expected.put("the first filter over ITS field list — well-formedness control", ADMITTED);
        expected.put("the second filter over ITS field list — well-formedness control", ADMITTED);
        expected.put("a filter after the operand LIST — the operator-level spelling", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "the filter site is refused wherever it sits in the operand list");
    }

    /**
     * The COMPUTATION arm of the same shape. It needs its own rows because the two arms elaborate an operand list
     * through different code — the compute path expands a starred group operand while validation's group-aware
     * arm is gated on the absence of a filter ({@code adapter.laws.FilteredGroupStarFillDiffTest}) — so an arm
     * agreeing on a refusal is a measurement, not a corollary.
     */
    @Test
    void theCOMPUTATIONArmRefusesTheSameShapeAtEitherPosition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the unfiltered pair — control", computationVerdict(
                "NumberOfFilledGroups(Counts*, Recounts*)"));
        actual.put("filter on the FIRST operand", computationVerdict(
                "NumberOfFilledGroups(Counts*" + COUNTS_FILTER + ", Recounts*)"));
        actual.put("filter on the SECOND operand", computationVerdict(
                "NumberOfFilledGroups(Counts*, Recounts*" + RECOUNTS_FILTER + ")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the unfiltered pair — control", ADMITTED);
        expected.put("filter on the FIRST operand", NO_GROUPS);
        expected.put("filter on the SECOND operand", NO_GROUPS);
        assertEquals(expected, actual, "the computation arm refuses the filter site the validation arm refuses");
    }

    /**
     * The diagnostic names the FILTER as the site, not the operand — which is what makes the code actionable, and
     * what a consumer of the code alone cannot know. Asserted on the text because no structured field carries it,
     * and on the quoted-site form rather than the bare word: the summary does not echo the condition, so a
     * looser match could have passed on the filter merely appearing in a rendered clause.
     */
    @Test
    void theDiagnosticNamesTheFilterAsTheSite() {
        ValidationResult r = KernelLaws.checkModel(MODEL, GROUP, "../Marker",
                "FieldFilled(Marker) And NumberOfFilledGroups(Counts*" + COUNTS_FILTER + ", Recounts*) > 0");
        String summaries = r.diagnostics().stream().map(Diagnostic::summary).collect(Collectors.joining(" | "));
        assertTrue(summaries.contains("At 'Having'"),
                () -> "the refusal must name the filter site, else the code alone cannot be acted on: " + summaries);
    }

    /**
     * <b>Which LAYER refuses it</b> — the axis every row above and every earlier lock left implicit. The two
     * carriers whose refusal this repository measured first were both caught by a THROW at code generation
     * ({@code adapter.laws.FilteredGroupStarFillDiffTest} for the fill quantifiers,
     * {@code adapter.laws.GroupStarValueAggregateDiffTest.havingOnGroupStarIsRejectedByTheKernel} for
     * {@code Sum}), which says nothing about whether static arbitration also sees it — and a consumer authoring
     * against {@code checkConsistency} only ever meets the static answer.
     *
     * <p>Each row is paired with its unfiltered control on the same carrier, because these two carriers accept a
     * group operand for different reasons and a shared refusal would otherwise be unattributable.
     */
    @Test
    void theSTATICArbitrationRefusesItOnTheCarriersOnlyMeasuredAtCodegenBefore() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("fill quantifier — unfiltered control", verdict("AtLeastOneFieldFilled(Counts*)"));
        actual.put("fill quantifier — filtered", verdict("AtLeastOneFieldFilled(Counts*" + COUNTS_FILTER + ")"));
        actual.put("value aggregate — unfiltered control", verdict("Sum(Adjustments*) > 0"));
        actual.put("value aggregate — filtered",
                verdict("Sum(Adjustments*" + ADJUSTMENTS_FILTER + ") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("fill quantifier — unfiltered control", ADMITTED);
        expected.put("fill quantifier — filtered", NO_GROUPS);
        expected.put("value aggregate — unfiltered control", ADMITTED);
        expected.put("value aggregate — filtered", NO_GROUPS);
        assertEquals(expected, actual, "static arbitration refuses the filter site, not only code generation");
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, GROUP, "../Marker",
                "FieldFilled(Marker) And " + condition);
        return codes(r);
    }

    /** The same verdict for one COMPUTATION whose operation carries the candidate shape. */
    private static String computationVerdict(String operation) {
        return codes(new RuleValidator().check(MODEL,
                new DraftComputation(GROUP, "TotalComp", "../CountedTotal", null,
                        List.of(new DraftComputation.Alternative(null, operation)))));
    }

    private static String codes(ValidationResult r) {
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(" + "));
    }
}
