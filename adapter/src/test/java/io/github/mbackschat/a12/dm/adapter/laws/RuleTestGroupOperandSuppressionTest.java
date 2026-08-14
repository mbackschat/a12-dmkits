package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.RuleTestOutcome;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * {@code rule eval}'s SUPPRESSED verdict when the rule's operand is a <b>GROUP</b> — {@code CG14}.
 *
 * <p><b>Why this is the one path where a wrong verdict is worst.</b> {@code testRule}'s whole stated purpose is
 * the three-way distinction, and its own javadoc says why: <i>"reporting a suppressed rule as a pass would
 * mislead"</i>. So a {@code PASSED} that should be {@code SUPPRESSED} is not a missing feature — it is the
 * failure mode the method was written to prevent.
 *
 * <p><b>The mechanism.</b> The suppression scan compares {@code referenced} entries against the formal-check
 * message's field path with {@code equals}. For a group operand, {@code referencedFields} returns the
 * <b>GROUP</b> path (the kernel's {@code getDirectlyReferencedEntities} reports what the condition NAMES), while
 * a formal-check message necessarily carries the <b>FIELD</b> whose stored value could not be parsed. Those two
 * strings can never be equal, so the scan finds nothing and the verdict falls through to {@code PASSED} — for a
 * rule the kernel never evaluated.
 *
 * <p>Same root as the {@code CG16} readers one module over: an exact match asked to answer a containment
 * question. Here the direction is reversed — there a field looked up its ancestors, here a referenced group must
 * recognize a descendant — but the comparison must still be at a path-segment boundary either way.
 */
class RuleTestGroupOperandSuppressionTest {

    private static final Locale EN = Locale.forLanguageTag("en-US");

    /**
     * {@code Assessment} holds two 2-decimal numbers, so a 3-decimal input is formally invalid — the same
     * device {@code RuleTestLawsTest} uses, moved below a group so the rule can name the group instead.
     */
    private static final String CLAIM = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", g -> {
                g.stringField("Reference", f -> { });
                g.group("Assessment", a -> {
                    a.numberField("Deductible", f -> f.fractionDigits(0, 2));
                    a.numberField("Excess", f -> f.fractionDigits(0, 2));
                });
            }).build().toJson();

    /** The rule's operand is the GROUP — it names `/Claim/Assessment` and no field inside it. */
    private static final String MODEL = RuntimeLaws.withRule(CLAIM, "/Claim", "AssessmentTotal", "../Reference",
            "ASSESS_HIGH", "FieldFilled(/Claim/Reference) And Sum(/Claim/Assessment) > 100");

    private static RuleTestOutcome test(String deductible, String excess) {
        return KernelAdapter.ofJson(MODEL).testRule("/Claim/AssessmentTotal",
                Map.of("Claim", Map.of("Reference", "C-1",
                        "Assessment", Map.of("Deductible", deductible, "Excess", excess))), EN);
    }

    /**
     * <b>THE DISCRIMINATOR.</b> {@code Deductible} carries three decimals on a two-decimal field, so it is
     * formally invalid and the kernel never evaluates a rule whose operand expansion includes it. Reporting
     * {@code PASSED} tells the author their rule ran and was satisfied, which is false in both halves.
     */
    @Test
    void aFormallyInvalidFieldInsideTheREFERENCEDGROUPSuppressesTheRule() {
        RuleTestOutcome outcome = test("10.999", "5.00");
        assertEquals(RuleTestOutcome.Verdict.SUPPRESSED, outcome.verdict(),
                () -> "the rule's operand is the GROUP holding the formally-invalid field, so the kernel skipped "
                        + "it — reporting a pass says it ran AND that it was satisfied, both false: " + outcome);
        assertTrue(outcome.suppressedBy().stream().anyMatch(s -> s.field().endsWith("Deductible")),
                () -> "the suppressing field must be named, or the author cannot find it: " + outcome.suppressedBy());
    }

    /**
     * <b>POSITIVE CONTROL — a genuine pass must stay PASSED.</b> Without it, "report SUPPRESSED whenever any
     * formal error exists anywhere" satisfies the row above, and that over-report is the mirror defect: it would
     * tell an author their rule was skipped when it ran and was satisfied.
     */
    @Test
    void aWellFormedInstanceBelowTheBarStillPasses() {
        RuleTestOutcome outcome = test("10.00", "5.00");
        assertEquals(RuleTestOutcome.Verdict.PASSED, outcome.verdict(),
                () -> "15.00 is not > 100 and nothing is malformed — a genuine pass: " + outcome);
        assertTrue(outcome.suppressedBy().isEmpty(),
                () -> "a genuine pass has nothing suppressing it: " + outcome.suppressedBy());
    }

    /** The other control: the rule still FIRES over a group operand, so the fixture evaluates at all. */
    @Test
    void theSameRuleStillFiresOverTheGroupOperand() {
        RuleTestOutcome outcome = test("90.00", "50.00");
        assertEquals(RuleTestOutcome.Verdict.FIRED, outcome.verdict(),
                () -> "140.00 > 100 — the group-operand rule fires: " + outcome);
    }

    /**
     * <b>THE SEGMENT-BOUNDARY CONTROL.</b> A formal error in a field that is NOT inside the referenced group must
     * not suppress. Without it, a containment test written as {@code startsWith} would pass every row above while
     * treating {@code /Claim/AssessmentNotes/X} as inside {@code /Claim/Assessment}.
     */
    @Test
    void aFormalErrorOutsideTheReferencedGroupDoesNotSuppress() {
        String withSibling = DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", g -> {
                    g.stringField("Reference", f -> { });
                    g.group("Assessment", a -> a.numberField("Deductible", f -> f.fractionDigits(0, 2)));
                    g.group("AssessmentNotes", n -> n.numberField("Weight", f -> f.fractionDigits(0, 2)));
                }).build().toJson();
        String model = RuntimeLaws.withRule(withSibling, "/Claim", "AssessmentTotal", "../Reference",
                "ASSESS_HIGH", "FieldFilled(/Claim/Reference) And Sum(/Claim/Assessment) > 100");

        RuleTestOutcome outcome = KernelAdapter.ofJson(model).testRule("/Claim/AssessmentTotal",
                Map.of("Claim", Map.of("Reference", "C-1",
                        "Assessment", Map.of("Deductible", "10.00"),
                        "AssessmentNotes", Map.of("Weight", "1.999"))), EN);

        assertEquals(RuleTestOutcome.Verdict.PASSED, outcome.verdict(),
                () -> "/Claim/AssessmentNotes is a string prefix of /Claim/Assessment but not inside it, so its "
                        + "malformed field must not suppress this rule: " + outcome);
    }
}
