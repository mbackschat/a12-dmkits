package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
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
 *
 * <p><b>The second law in this file is the gate's PLACE in the sequence</b> (the lean project's
 * {@code SPEC-2026-08-10-02}, KERNEL-FINDINGS KF181): over an operation that reads the computed field itself,
 * this scale comparison is decided <em>before</em> the target-self-reference refusal. Its rows are authored as
 * DSL text through {@link DraftComputation}, the route the CLI uses, because the typed builder pre-checks a
 * self-reference away — deliberately, and correctly, since the kernel rejects every one of these shapes; what
 * the rows establish is <em>which</em> rejection an author is shown first.
 */
class ComputedTargetScaleLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String SCALE_GATE = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final String SELF_REFERENCE = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

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
                        // scale-0 like TotalScale0, so an addition of the two derives the target's own scale
                        .numberField("Units", n -> n.fractionDigits(0))
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
        assertEquals(List.of(SCALE_GATE),
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

    // ── KF181: the scale comparison is decided BEFORE the target-self-reference refusal ───────────────
    //
    // Each pair below changes ONE thing — the operand that decides the derived scale — over an operation
    // that reads the computed field either way. So the code that flips is attributable to the scale gate
    // and to nothing else, and suppressing that gate re-exposes the self-reference underneath it.

    /** {@code checkConsistency} over a text-authored operation assigned to {@code target}. */
    private static List<String> codes(String target, String operation, String... suppress) {
        return check(target, operation, null, suppress);
    }

    /** The same, with a common precondition — the slot that carries the self-read in one row below. */
    private static List<String> codesGuardedBy(String target, String operation, String commonPrecondition) {
        return check(target, operation, commonPrecondition);
    }

    /**
     * The complete emitted code list. The size is asserted against the codes because a kernel notification
     * carrying no {@code MVK_*} token would otherwise be invisible to these rows.
     */
    private static List<String> check(String target, String operation, String commonPrecondition,
            String... suppress) {
        String json = quotePricing();
        DraftComputation comp = new DraftComputation("/Quote", "TotalComp", "../" + target, commonPrecondition,
                List.of(new DraftComputation.Alternative(null, operation)), List.of(suppress));
        ValidationResult result = VALIDATOR.check(json, comp);
        List<String> codes = result.diagnostics().stream().flatMap(d -> d.code().stream()).toList();
        assertEquals(result.diagnostics().size(), codes.size(),
                () -> "a diagnostic without an MVK code would be invisible to this row: " + result.diagnostics());
        return codes;
    }

    private static void reports(String expected, String operation, String... suppress) {
        assertEquals(List.of(expected), codes("TotalScale0", operation, suppress),
                () -> "'" + operation + "' into the scale-0 target must report " + expected + " alone");
    }

    @Test
    void aScaleMatchedSelfReferenceReportsTheSelfReferenceAlone() {
        reports(SELF_REFERENCE, "[/Quote/TotalScale0] * 2");
        reports(SELF_REFERENCE, "[/Quote/TotalScale0] + [/Quote/Units]");
    }

    @Test
    void changingOnlyTheLiteralToMismatchTheScaleReportsTheScaleGateAlone() {
        // …and the self-reference is still there, unchanged — it is simply not what the author is shown.
        reports(SCALE_GATE, "[/Quote/TotalScale0] * 1.5");
        reports(SCALE_GATE, "[/Quote/TotalScale0] + 1.5");
    }

    @Test
    void suppressingTheScaleGateReExposesTheSelfReferenceUnderneathIt() {
        // The kernel's own escape hatch is the discriminator: suppression cannot LEGALIZE the computation,
        // so what surfaces is the refusal the scale gate had been reported ahead of.
        reports(SELF_REFERENCE, "[/Quote/TotalScale0] * 1.5", SCALE_GATE);
        reports(SELF_REFERENCE, "[/Quote/TotalScale0] + 1.5", SCALE_GATE);
    }

    @Test
    void theOrderIsIndependentOfOperandPositionAndOfTheOperator() {
        reports(SCALE_GATE, "1.5 * [/Quote/TotalScale0]");
        reports(SELF_REFERENCE, "2 * [/Quote/TotalScale0]");
        reports(SCALE_GATE, "[/Quote/TotalScale0] - 1.5");
        reports(SELF_REFERENCE, "[/Quote/TotalScale0] - 2");
    }

    /**
     * …and independent of which slot carries the self-read: a mismatch in the operation is reported ahead of a
     * self-reference in the precondition, and removing the mismatch exposes that precondition self-reference.
     */
    @Test
    void theOrderHoldsWhenTheSelfReadSitsInAPreconditionInstead() {
        String selfReadingGuard = "[/Quote/TotalScale0] > 0";
        assertEquals(List.of(SCALE_GATE),
                codesGuardedBy("TotalScale0", "[/Quote/Rate] * 1.50", selfReadingGuard),
                "the operation's scale mismatch is reported ahead of the precondition's self-reference");
        assertEquals(List.of(SELF_REFERENCE),
                codesGuardedBy("TotalScale0", "[/Quote/Units] * 2", selfReadingGuard),
                "with the scale matched, the precondition's self-reference is what surfaces");
    }

    /**
     * The unrelated-mismatch and accepted controls for these rows, on the same target: a scale mismatch with
     * NO self-reference reports the same gate (so the gate is not self-reference-specific), and the
     * scale-matched no-self-reference shape is accepted outright (so the model itself is not the rejection).
     */
    @Test
    void theUnrelatedMismatchAndAcceptedControls() {
        reports(SCALE_GATE, "[/Quote/Rate] * 1.50");
        admits("TotalScale0", m -> m.num("/Quote/Units").asNumber().times(literal("2")));
    }
}
