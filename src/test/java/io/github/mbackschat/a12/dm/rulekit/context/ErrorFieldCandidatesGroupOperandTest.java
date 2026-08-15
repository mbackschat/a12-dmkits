package io.github.mbackschat.a12.dm.rulekit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * {@code CG16} — the error-field candidate list over a condition whose operands are all <b>GROUPS</b>.
 *
 * <p>The kernel requires a rule's error field to be referenced by its own condition, and a group operand
 * satisfies that for the fields the group expands to (KERNEL-FINDINGS §9 — {@code GroupFilled(G)} covers
 * {@code G}'s members indirectly). {@code errorFieldCandidates} filtered the condition's <i>field</i>
 * references only, so an all-group condition produced an empty list plus the note <i>"the condition
 * references no field inside its iteration scope — reference one there"</i>. That advice is worse than
 * silence: it steers the author away from the only picks the kernel accepts, toward editing a correct
 * condition.
 *
 * <p><b>Each row is kernel-measured first, then asserted against the suggestion.</b> A suggestion list is a
 * claim about what the kernel will accept, so a test that only compares it against itself proves nothing —
 * every row here validates the suggested pick through {@code RuleValidator} before requiring it to be offered.
 */
class ErrorFieldCandidatesGroupOperandTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** {@code Assessment} is the flat group; {@code Visits} the repeatable one, so the scope filter is exercised. */
    private static String claimJson() {
        return DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .group("Assessment", a -> a
                                .numberField("Deductible", n -> n.fractionDigits(0, 2))
                                .numberField("Excess", n -> n.fractionDigits(0, 2)))
                        .group("Visits", v -> v.repeatable(20)
                                .stringField("Purpose", f -> { })
                                .stringField("Outcome", f -> { })))
                .build().toJson();
    }

    private static final String CLAIM_JSON = claimJson();
    private static final BoundModel CLAIM = BoundModel.ofJson(CLAIM_JSON);

    /**
     * A NON-repeatable group operand: every field it expands to is a valid error field, and the condition
     * iterates over nothing, so the scope filter admits them all.
     */
    @Test
    void anAllGroupConditionOffersTheFieldsTheGroupExpandsTo() {
        Condition condition = Dsl.notAllFieldsFilled(CLAIM.flattenGroup("/Claim/Assessment"));

        assertKernelAccepts(condition, "../Assessment/Deductible");
        assertEquals(List.of("/Claim/Assessment/Deductible", "/Claim/Assessment/Excess"),
                new ModelContext(CLAIM).errorFieldCandidates(condition),
                "a group operand references its expansion; those fields are the valid error fields");
    }

    /**
     * A STARRED group operand: the group is the iteration scope, so its own fields qualify — the same answer
     * by a different route, and the row that proves the scope filter is applied to the expansion rather than
     * skipped for it.
     */
    @Test
    void aStarredGroupOperandOffersItsOwnFields() {
        Condition condition = Dsl.notAllFieldsFilled(CLAIM.flattenGroup("/Claim/Visits*"));

        assertKernelAccepts(condition, "../Visits/Purpose");
        assertEquals(List.of("/Claim/Visits/Outcome", "/Claim/Visits/Purpose"),
                new ModelContext(CLAIM).errorFieldCandidates(condition),
                "the starred group is the iteration scope; the fields inside it are the per-row error fields");
    }

    /**
     * The SCOPE row, and it measures a NARROWING rather than a refusal — which is the correction this row
     * exists to record. With a starred group setting the iteration scope, a field reached through a
     * <i>different</i>, non-repeating group is outside that scope and is not offered. It was written expecting
     * the kernel to refuse that pick; <b>the kernel accepts it</b>. So the list is a per-ROW suggestion, not a
     * validity verdict — exactly what {@code rule check --suggest-error-field}'s own note tells the author when
     * their {@code --field} is absent from it, and why {@code fieldIsCandidate:false} is not a rejection.
     *
     * <p>Keeping the kernel row here rather than deleting it is the point: without it the exclusion reads as
     * "the kernel would refuse this", which is what the first draft asserted and what one run refuted.
     */
    @Test
    void aGroupOutsideTheIterationScopeIsNarrowedOutButStaysKernelLegal() {
        Condition condition = Dsl.notAllFieldsFilled(CLAIM.flattenGroup("/Claim/Visits*"))
                .and(Dsl.notAllFieldsFilled(CLAIM.flattenGroup("/Claim/Assessment")));

        assertEquals(List.of("/Claim/Visits/Outcome", "/Claim/Visits/Purpose"),
                new ModelContext(CLAIM).errorFieldCandidates(condition),
                "the suggestion is scoped to the iterated group's own fields");
        ValidationResult outside = check(condition, "../Assessment/Deductible");
        assertTrue(outside.ok(),
                () -> "MEASURED: the kernel admits a model-level error field the suggestion narrows away — "
                        + "the list is a per-row aid, never a verdict: " + outside.diagnostics());
    }

    /** A FIELD reference beside a group operand keeps contributing — the group arm adds, it does not replace. */
    @Test
    void aFieldReferenceBesideAGroupOperandStillCounts() {
        Condition condition = CLAIM.str("/Claim/Reference").filled()
                .and(Dsl.notAllFieldsFilled(CLAIM.flattenGroup("/Claim/Assessment")));

        assertEquals(List.of("/Claim/Assessment/Deductible", "/Claim/Assessment/Excess", "/Claim/Reference"),
                new ModelContext(CLAIM).errorFieldCandidates(condition));
    }

    private static void assertKernelAccepts(Condition condition, String errorEntityRelPath) {
        ValidationResult r = check(condition, errorEntityRelPath);
        assertTrue(r.ok(), () -> "the kernel must accept the suggested error field " + errorEntityRelPath
                + " for " + RENDER.render(condition) + " — got " + r.diagnostics());
    }

    private static ValidationResult check(Condition condition, String errorEntityRelPath) {
        return new RuleValidator().check(CLAIM_JSON, new DraftRule("/Claim", "Candidates",
                errorEntityRelPath, RENDER.render(condition), "CAND", "ERROR"));
    }
}
