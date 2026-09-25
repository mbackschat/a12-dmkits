package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — where the {@code $} outer marker may stand, and what pre-empts the gate that says so.
 *
 * <p>{@code CheckEntitySpecImpl.checkOuter} is the marker's single locus and names no carrier:
 * {@code ctx.outer != null && !isInFilterCondition()} refuses, so the law is positional rather than
 * operator-specific — already measured on an aggregate and a bare comparison alike
 * ({@code GroupOperandCarrierAdmissionLawsTest}). What those rows do NOT settle is what a checker needs
 * before it may report the class: whether an unresolvable path or a defect earlier in the text answers
 * first. {@code checkOuter} runs inside {@code checkCommon}, so both are open questions a source read
 * cannot close — the reference is already built by then.
 *
 * <p>Own domain (insurance claims).
 */
class OuterMarkerPositionLawsTest {

    private static final String CLAIM = DocumentModelDraft.named("claim-outer-marker").locale("en_US")
            .rootGroup("Claim", c -> c
                    .stringField("Reference", f -> { })
                    .numberField("ExcessDue", f -> f.fractionDigits(2))
                    .group("Assessments", a -> a.repeatable(20)
                            .stringField("Code", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2))))
            .build().toJson();

    /** Rule group {@code /Claim}, error field held constant so every row differs in the condition alone. */
    private static String verdict(String condition) {
        return KernelLaws.verdictModel(CLAIM, "/Claim", "../Reference", condition);
    }

    @Test
    void whereTheOuterMarkerMayStandAndWhatAnswersBeforeIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("no marker anywhere — the control", verdict("FieldFilled(Reference)"));
        actual.put("a marker OUTSIDE any filter, on a path that resolves",
                verdict("FieldFilled(Reference) And [$/Claim/ExcessDue] > 0"));
        actual.put("a marker INSIDE a filter, as the body's ONLY reference",
                verdict("FieldFilled(Reference) "
                        + "And Sum(Assessments*/Amount Having [$/Claim/ExcessDue] > 0) > 0"));
        actual.put("a marker INSIDE a filter, beside an in-scope reference — the legal position",
                verdict("FieldFilled(Reference) And Sum(Assessments*/Amount Having "
                        + "([Assessments/Code] == \"X\" And [$/Claim/ExcessDue] > 0)) > 0"));
        actual.put("a marker outside a filter, on a path that RESOLVES TO NOTHING",
                verdict("FieldFilled(Reference) And [$/Claim/Nowhere] > 0"));
        actual.put("a marker outside a filter, BEHIND an unrelated defect in the text",
                verdict("FieldFilled(Nowhere) And [$/Claim/ExcessDue] > 0"));
        actual.put("a marker outside a filter, AHEAD of an unrelated defect in the text",
                verdict("[$/Claim/ExcessDue] > 0 And FieldFilled(Nowhere)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("no marker anywhere — the control", KernelLaws.ADMITTED);
        expected.put("a marker OUTSIDE any filter, on a path that resolves", "MVK_INVALID_OUTER_ITERATION");
        // PREDICTED ADMITTED and measured otherwise, which [KF374] already states and this prediction
        // failed to apply: a marked reference is SKIPPED by the filter's index walk, so it contributes no
        // ridden level and cannot clear the scope gate. The marker is legal here and the body is still
        // short of what the gate before the marker's own demands.
        expected.put("a marker INSIDE a filter, as the body's ONLY reference", "MVK_NO_ITERATION_FOR_WILDCARD");
        expected.put("a marker INSIDE a filter, beside an in-scope reference — the legal position",
                KernelLaws.ADMITTED);
        // The two rows a CHECKER needs before it may report this class without resolving anything. The
        // marker's gate sits in `checkCommon`, AFTER the reference is built, so an unresolvable path could
        // answer first — and the pair of text-order rows says whether position in the text decides.
        // RESOLUTION PRE-EMPTS THE MARKER'S GATE. `checkOuter` runs inside `checkCommon`, after the
        // reference has been built, so an address naming nothing answers first — the row that stops a
        // checker from reporting this class on text position alone.
        expected.put("a marker outside a filter, on a path that RESOLVES TO NOTHING", "MVK_INVALID_ENTITY");
        expected.put("a marker outside a filter, BEHIND an unrelated defect in the text", "MVK_INVALID_ENTITY");
        expected.put("a marker outside a filter, AHEAD of an unrelated defect in the text",
                "MVK_INVALID_OUTER_ITERATION");
        assertEquals(expected, actual, "where the outer marker may stand, and what answers before it");
    }

    /**
     * A conjunct OUTSIDE the filter that spells the filter's body verbatim, marker first: A12 reads the
     * position, so the marker outside is refused even though the same characters inside the filter are legal.
     * The row a reader that cut bodies out by their text rather than their position gets wrong.
     */
    @Test
    void aMarkerOutsideIsRefusedEvenWhereItsConjunctSpellsTheFilterBody() {
        String body = "([$/Claim/ExcessDue] > 0 And [Assessments/Code] == \"X\")";
        String filtered = "FieldFilled(Reference) And Sum(Assessments*/Amount Having " + body + ") > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the filter alone — the control", verdict(filtered));
        actual.put("the filter, then its body's text outside it", verdict(filtered + " And " + body));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the filter alone — the control", KernelLaws.ADMITTED);
        expected.put("the filter, then its body's text outside it", "MVK_INVALID_OUTER_ITERATION");
        assertEquals(expected, actual, "a marker in a conjunct that spells the filter body");
    }
}
