package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * {@code CG15} — the {@code NumberOfFilledGroups(Group*)} arm, executing the ledger's own completion gate:
 * <i>"a round-trip row proves each converted group operand renders back to the authored condition"</i>.
 *
 * <p><b>Why this file exists as a measurement rather than as a fix.</b> The gap row states that
 * {@code ConditionConverter}'s single-group arm converts through {@code model.group(e.getFullName())} and thereby
 * "drops the star its own comment promises", so the round trip renders the unstarred form the kernel REJECTS
 * ({@code MVK_NO_WILDCARD}). Reading the converter alone supports that: {@code GroupRef} genuinely carries no
 * star. Reading one layer further does not — the star is re-derived from the MODEL, today by
 * {@code CountAggregate.scopeOf}, which stars a group exactly when it repeats, and the renderer's own comment
 * records that being fixed on 2026-08-12 for exactly this class of defect. Two adjacent reads, opposite conclusions, which is precisely the situation this
 * repo's rules say to settle by executing rather than by reading.
 *
 * <p>The guard naming {@code PatientRef} is not decoration: a rule's error field must be referenced by
 * its own condition ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) and a bare group operand does not reference
 * one, which is {@code CG16}'s exactness/reachability split showing up as a fixture constraint.
 *
 * <p>The round trip is the oracle: author → serialize → convert back → render, then compare against the authored
 * text. A star lost anywhere in that chain shows up as a mismatch regardless of which layer dropped it.
 */
class NumberOfFilledGroupsRoundTripTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** {@code Visits} is REPEATABLE — its star is the subject; {@code Summary} is a non-repeatable control. */
    private static String clinicJson() {
        return DocumentModelDraft.named("clinic").locale("en_US")
                .rootGroup("Clinic", c -> c
                        .stringField("PatientRef", b -> { })
                        .group("Visits", v -> v.repeatable(12).stringField("Reason", b -> { }))
                        .group("Summary", s -> s.stringField("Outcome", b -> { })))
                .build().toJson();
    }

    /**
     * <b>THE ROUND TRIP.</b> The authored condition stars the repeatable group; the read-back must render the
     * same text. If the converter's {@code GroupRef} truly lost the star with nothing restoring it, the rendered
     * form would be {@code NumberOfFilledGroups(/Clinic/Visits)} — which the kernel refuses outright.
     */
    @Test
    void aStarredSingleGroupRendersBackToTheAuthoredCondition() {
        String authored = "FieldFilled(/Clinic/PatientRef) And NumberOfFilledGroups(/Clinic/Visits*) > 0";
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(inject(clinicJson(), authored)).read("/Clinic/CountRule"),
                "a single starred group operand converts");

        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("/Clinic/Visits*"),
                () -> "the star must survive the round trip — without it the rule names a repeatable group "
                        + "unstarred, which the kernel refuses MVK_NO_WILDCARD: " + rendered);
        assertEquals(authored, rendered,
                "the completion gate is exact: the converted operand renders back to the authored condition");
    }

    /**
     * The CONTROL. {@code NumberOfFilledGroups} over a NON-repeatable group must gain no star — otherwise
     * "always append one" would satisfy the row above while corrupting every non-repeatable scope. It also
     * pins the star as a property of the OPERAND's repeatability rather than of the operator.
     */
    @Test
    void aNonRepeatableGroupInTheSameOperatorGainsNoStar() {
        String authored = "FieldFilled(/Clinic/PatientRef) And NumberOfFilledGroups(/Clinic/Visits*, /Clinic/Summary) > 0";
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(inject(clinicJson(), authored)).read("/Clinic/CountRule"),
                "a mixed repeatable/non-repeatable group list converts");

        String rendered = RENDER.render(converted.condition());
        assertEquals(authored, rendered,
                "one operand keeps its star and the other must not acquire one — the star is the operand's");
    }

    private static String inject(String modelJson, String rawCondition) {
        return new RuleValidator().inject(modelJson,
                new DraftRule("/Clinic", "CountRule", "../PatientRef", rawCondition, "C", "ERROR"));
    }
}
