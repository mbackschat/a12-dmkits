package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — a computation's <b>declared target scale</b> is admitted by the same
 * constant-capability predicate the {@code ==}/{@code !=} comparison gate uses, <b>not</b> by equality
 * (the lean project's {@code SPEC-2026-08-04-01}; every verdict reproduced here against the real kernel).
 *
 * <p>The canonical prose claimed plain equality while the §5 scale summary in the same document already
 * defined the capability model, so the two statements contradicted each other. The predicate:
 *
 * <ul>
 * <li><b>Equal</b> derived and declared scale always passes.</li>
 * <li>A <b>smaller</b> derived scale passes <em>while the whole operation stays constant-capable</em> — the
 *     capable side pads up to the declared scale.</li>
 * <li>A <b>larger</b> derived scale is rejected regardless of capability: padding only adds digits.</li>
 * <li>Equality is the special case reached as soon as any <b>field</b> reference joins the operation, because a
 *     field is never constant-capable ({@code NumericScaleSummary}, §5).</li>
 * </ul>
 *
 * <p><b>The two discriminating pairs.</b> Direction is settled by the same capable operation at a larger versus a
 * smaller declared scale ({@code [Rate] * 100} into scale 4 versus {@code [Rate] * 1.50} into scale 0) — a gate
 * that merely allowed "any mismatch while capable" would pass both. Capability is settled by two operations with
 * the <em>same</em> derived scale 4, one capable and one not ({@code [Rate] * 1.50} versus
 * {@code [Rate] * [Factor]}) at declared scale 2 — a gate reading only the scales would treat them alike.
 *
 * <p>The authoring surface has no client-side scale check to lock here: {@code Computation} defers scale to the
 * kernel by design (D13), so a capability-carrying computation was always authorable and only the prose was
 * wrong.
 */
class ComputedTargetScaleLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String SCALE_GATE = "MVK_INVALID_COMPARE_DEC_PLACES";

    /**
     * A quote-pricing model: three scale-2 source fields, and one computation target at each of the declared
     * scales 0, 2, and 4 so one operation can be assigned to all three without changing anything else.
     */
    private static String quotePricing() {
        return DocumentModelDraft.named("quote-pricing").locale("en_US")
                .rootGroup("Quote", root -> root
                        .numberField("Rate", n -> n.fractionDigits(2))
                        .numberField("Factor", n -> n.fractionDigits(2))
                        .numberField("Surcharge", n -> n.fractionDigits(2))
                        .numberField("TotalScale0", n -> n.fractionDigits(0))
                        .numberField("TotalScale2", n -> n.fractionDigits(2))
                        .numberField("TotalScale4", n -> n.fractionDigits(4)))
                .build().toJson();
    }

    /** {@code checkConsistency} over one computation assigning {@code operation} to {@code target}. */
    private static ValidationResult assign(String target, Function<BoundModel, NumberExpr> operation) {
        String json = quotePricing();
        BoundModel m = BoundModel.ofJson(json);
        Computation comp = m.computation(m.num("/Quote/" + target)).name("TotalComp")
                .calc(operation.apply(m).expression())
                .message(en("The total restates the rated amount."))
                .build();
        return VALIDATOR.check(json, comp.draft());
    }

    private static void admits(String target, Function<BoundModel, NumberExpr> operation) {
        ValidationResult r = assign(target, operation);
        assertTrue(r.ok(), () -> "assignment to " + target + " must be admitted: " + r.diagnostics());
    }

    private static void refuses(String target, Function<BoundModel, NumberExpr> operation) {
        ValidationResult r = assign(target, operation);
        assertEquals(java.util.List.of(SCALE_GATE),
                r.diagnostics().stream().flatMap(d -> d.code().stream()).toList(),
                () -> "assignment to " + target + " must fail on the scale gate alone: " + r.diagnostics());
    }

    private static NumberExpr literal(String value) {
        return new NumberExpr(NumberLiteral.of(value));
    }

    // ── capable, derived scale 0: equal AND every larger declared scale ───────────────────────────────
    //
    // Rate is scale 2 and the integer literal 100 strips trailing zeros to signed scale -2, so the product's
    // derived scale is 0 and multiplication retains the literal's capability.

    private static Function<BoundModel, NumberExpr> ratePerHundred() {
        return m -> m.num("/Quote/Rate").asNumber().times(literal("100"));
    }

    @Test
    void aCapableOperationIsAdmittedAtItsOwnDeclaredScale() {
        admits("TotalScale0", ratePerHundred());
    }

    @Test
    void aCapableSmallerDerivedScaleIsAdmittedAtALargerDeclaredScale() {
        // The case plain equality would have refused, and the reason the prose was wrong.
        admits("TotalScale2", ratePerHundred());
        admits("TotalScale4", ratePerHundred());
    }

    // ── capable, derived scale 4: equal only DOWNWARD is allowed ──────────────────────────────────────

    private static Function<BoundModel, NumberExpr> rateTimesFraction() {
        return m -> m.num("/Quote/Rate").asNumber().times(literal("1.50"));
    }

    @Test
    void aCapableLargerDerivedScaleIsRejected() {
        // Same capability as above, opposite direction — so capability alone does not licence a mismatch.
        refuses("TotalScale0", rateTimesFraction());
        refuses("TotalScale2", rateTimesFraction());
    }

    @Test
    void thatSameCapableOperationIsAdmittedAtItsExactScale() {
        admits("TotalScale4", rateTimesFraction());
    }

    // ── a field reference in the operation collapses the predicate to exact equality ──────────────────

    private static Function<BoundModel, NumberExpr> rateTimesFactor() {
        return m -> m.num("/Quote/Rate").asNumber().times(m.num("/Quote/Factor").asNumber());
    }

    @Test
    void aFieldBearingOperationIsAdmittedOnlyAtItsExactScale() {
        admits("TotalScale4", rateTimesFactor());
        refuses("TotalScale0", rateTimesFactor());
        // Derived scale 4 exactly as rateTimesFraction, but not capable — so scale 2 flips from admitted to
        // refused. This is the pair that shows the gate reads capability and not only the numbers.
        refuses("TotalScale2", rateTimesFactor());
    }

    @Test
    void anAdditionOfTwoFieldsIsAdmittedOnlyAtItsExactScale() {
        // Addition takes the maximum operand scale and needs BOTH operands capable, so two scale-2 fields
        // derive scale 2 with no capability: the smaller declared scale is refused like the larger one.
        Function<BoundModel, NumberExpr> sum =
                m -> m.num("/Quote/Rate").asNumber().plus(m.num("/Quote/Surcharge").asNumber());
        admits("TotalScale2", sum);
        refuses("TotalScale0", sum);
        refuses("TotalScale4", sum);
    }
}
