package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * KERNEL-FINDINGS §5 (kf167) — the operand-list NUMERIC WRAPPERS ({@code Abs}, {@code RoundDown}, {@code RoundUp},
 * {@code RoundAccounting}) refuse an <em>immediate</em> numeric literal as their first operand with
 * {@code MVK_NO_CONST}, in BOTH consumers (a rule condition and a computation operation).
 *
 * <p>The boundary is what makes this falsifiable rather than a restatement of "sources must be fields" — which
 * is too broad in two directions, and both are pinned below:
 *
 * <ul>
 *   <li>a literal <em>inside an independently admitted arithmetic subtree</em> is legal
 *       ({@code Abs([BasePremium] - 1)}), so the operand need not be a field; and</li>
 *   <li>the refusal is the <em>immediate</em> child, so it is a position gate, not a ban on literals below the
 *       wrapper.</li>
 * </ul>
 *
 * <p>This locks the NUMERIC half only. The temporal half of the same code — a direct temporal literal beneath a
 * {@code Date(...)}/{@code Time(...)} component extractor — is a separate operand gate that merely shares the
 * code, and is already recorded (KERNEL-FINDINGS §6) and locked by {@link TemporalExtractorComponentLawsTest}.
 * Keying coverage by the code alone would have conflated the two.
 */
class NumericWrapperConstantLawsTest {

    private static final String NO_CONST = "MVK_NO_CONST";
    private static final String BASE_PREMIUM = "/Policy/BasePremium";
    private static final String ADJUSTMENT = "/Policy/Adjustment";
    /** Adjustment's declared scale — the computed target's, which the operation must match. */
    private static final int TARGET_SCALE = 2;

    private final String modelJson = DocumentModelDraft.named("premium-audit").locale("en_US")
            .rootGroup("Policy", group -> group
                    .numberField("BasePremium", field -> field.fractionDigits(2))
                    .numberField("Adjustment", field -> field.fractionDigits(2)))
            .build().toJson();
    private final BoundModel model = BoundModel.ofJson(modelJson);

    // --- the immediate-literal refusal, per wrapper, in both consumers ------------------------------

    @Test
    void everyNumericWrapperRefusesAnImmediateLiteralInARuleCondition() {
        wrappers(1).forEach((name, wrap) ->
                KernelLaws.rejectsModel(modelJson, rule(name + "Literal", wrap.apply(Dsl.number(1)).lt(5)), NO_CONST));
    }

    @Test
    void everyNumericWrapperRefusesAnImmediateLiteralInAComputationOperation() {
        // TARGET_SCALE, not 1: at any other scale the assignment-scale gate (MVK_INVALID_COMPARE_DEC_PLACES) can
        // co-fire, so the case would not isolate the constant boundary it is named for. It passed either way
        // (anyMatch), which is exactly why the mismatch with the fixture javadoc went unnoticed.
        wrappers(TARGET_SCALE).forEach((name, wrap) -> {
            var result = new RuleValidator().check(modelJson, computation(name + "LiteralComp", wrap.apply(Dsl.number(1))));
            assertFalse(result.ok(), () -> name + " must refuse an immediate literal operand");
            assertTrue(result.diagnostics().stream()
                            .anyMatch(d -> d.code().filter(NO_CONST::equals).isPresent()),
                    () -> name + " must refuse it as " + NO_CONST + ", got " + result.diagnostics());
        });
    }

    // --- the two directions in which "sources must be fields" is too broad -------------------------

    @Test
    void everyNumericWrapperAdmitsAFieldOperand() {
        wrappers(1).forEach((name, wrap) ->
                KernelLaws.acceptsModel(modelJson,
                        rule(name + "Field", wrap.apply(model.num(BASE_PREMIUM).asNumber()).lt(5))));
    }

    /** The discriminator: the operand is an expression, so a literal inside admitted arithmetic stays legal. */
    @Test
    void aLiteralInsideAnAdmittedArithmeticSubtreeIsLegal() {
        wrappers(1).forEach((name, wrap) -> KernelLaws.acceptsModel(modelJson,
                rule(name + "Arith", wrap.apply(model.num(BASE_PREMIUM).asNumber().minus(1)).lt(5))));
    }

    /** The literal is the POINT of this case — it rides beside a completed wrapper inside the outer call. */
    @Test
    void aLiteralIsAlsoLegalAsASiblingOperandOfAnOuterCall() {
        KernelLaws.acceptsModel(modelJson, rule("MinSibling",
                Dsl.min(Dsl.abs(model.num(BASE_PREMIUM).asNumber()),
                        model.num(ADJUSTMENT).asNumber(),
                        Dsl.number("1.250")).lt(5)));
    }

    /** The same boundary holds in the COMPUTATION context, on the accepting side as well as the refusing one. */
    @Test
    void theAdmittedFormsAreAlsoAcceptedAsAComputationOperation() {
        var validator = new RuleValidator();
        wrappers(TARGET_SCALE).forEach((name, wrap) -> {
            var field = validator.check(modelJson, computation(name + "FieldComp", wrap.apply(model.num(BASE_PREMIUM).asNumber())));
            assertTrue(field.ok(), () -> name + " over a field must be accepted as an operation: " + field.diagnostics());
            var arith = validator.check(modelJson,
                    computation(name + "ArithComp", wrap.apply(model.num(BASE_PREMIUM).asNumber().minus(1))));
            assertTrue(arith.ok(),
                    () -> name + " over arithmetic containing a literal must be accepted: " + arith.diagnostics());
        });
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /**
     * The four operand-list numeric wrappers, each as its single-operand form, rounded to {@code digits}.
     * The digit count is a PARAMETER because a computation additionally meets the assignment-scale gate: an
     * operation whose static scale differs from the computed target's is {@code MVK_INVALID_COMPARE_DEC_PLACES},
     * an unrelated gate. So the computation cases round to the target's own scale (2) to isolate the constant
     * boundary; a rule condition imposes no such scale, so 1 is fine there.
     */
    private static Map<String, UnaryOperator<NumberExpr>> wrappers(int digits) {
        // Insertion-ordered on purpose (a failure message should name the wrappers in a stable order). Note that
        // `new LinkedHashMap<>(Map.of(...))` would NOT achieve that: Map.of's iteration order is unspecified and
        // deliberately randomized per JVM, so the copy inherits an arbitrary order the source text does not show.
        Map<String, UnaryOperator<NumberExpr>> wrappers = new LinkedHashMap<>();
        wrappers.put("Abs", Dsl::abs);
        wrappers.put("RoundDown", operand -> Dsl.roundDown(operand, digits));
        wrappers.put("RoundUp", operand -> Dsl.roundUp(operand, digits));
        wrappers.put("RoundAccounting", operand -> Dsl.roundAccounting(operand, digits));
        return wrappers;
    }

    private Rule rule(String name, Condition condition) {
        return model.rule(model.group("/Policy"))
                .name(name)
                .errorField(model.num(BASE_PREMIUM))
                .when(condition)
                .code(name)
                .severity(Severity.ERROR)
                .message(Dsl.en("Premium adjustment is out of range."))
                .build();
    }

    private DraftComputation computation(String name, NumberExpr operation) {
        return model.computation(model.num(ADJUSTMENT))
                .name(name)
                .calc(operation.expression())
                .message(Dsl.en("Adjustment is derived from the base premium."))
                .build()
                .draft();
    }
}
