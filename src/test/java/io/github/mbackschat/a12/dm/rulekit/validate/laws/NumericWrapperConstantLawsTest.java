package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
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

    private final String modelJson = DocumentModelDraft.named("premium-audit").locale("en_US")
            .rootGroup("Policy", group -> group
                    .numberField("BasePremium", field -> field.fractionDigits(2))
                    .numberField("Adjustment", field -> field.fractionDigits(2)))
            .build().toJson();
    private final BoundModel model = BoundModel.ofJson(modelJson);

    // --- the immediate-literal refusal, per wrapper, in both consumers ------------------------------

    @Test
    void everyNumericWrapperRefusesAnImmediateLiteralInARuleCondition() {
        wrappers().forEach((name, wrap) ->
                KernelLaws.rejectsModel(modelJson, rule(name + "Literal", wrap.apply(Dsl.number(1)).lt(5)), NO_CONST));
    }

    @Test
    void everyNumericWrapperRefusesAnImmediateLiteralInAComputationOperation() {
        wrappers().forEach((name, wrap) -> {
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
        wrappers().forEach((name, wrap) ->
                KernelLaws.acceptsModel(modelJson,
                        rule(name + "Field", wrap.apply(model.num(BASE_PREMIUM).asNumber()).lt(5))));
    }

    /** The discriminator: the operand is an expression, so a literal inside admitted arithmetic stays legal. */
    @Test
    void aLiteralInsideAnAdmittedArithmeticSubtreeIsLegal() {
        wrappers().forEach((name, wrap) -> KernelLaws.acceptsModel(modelJson,
                rule(name + "Arith", wrap.apply(model.num(BASE_PREMIUM).asNumber().minus(1)).lt(5))));
    }

    @Test
    void aLiteralIsAlsoLegalAsASiblingOperandOfAnOuterCall() {
        KernelLaws.acceptsModel(modelJson, rule("MinSibling",
                Dsl.min(Dsl.abs(model.num(BASE_PREMIUM).asNumber()), model.num(ADJUSTMENT).asNumber()).lt(5)));
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** The four operand-list numeric wrappers, each as its single-operand form. */
    private static java.util.Map<String, UnaryOperator<NumberExpr>> wrappers() {
        return new java.util.LinkedHashMap<>(java.util.Map.of(
                "Abs", Dsl::abs,
                "RoundDown", operand -> Dsl.roundDown(operand, 1),
                "RoundUp", operand -> Dsl.roundUp(operand, 1),
                "RoundAccounting", operand -> Dsl.roundAccounting(operand, 1)));
    }

    private Rule rule(String name, io.github.mbackschat.a12.dm.rulekit.dsl.Condition condition) {
        return model.rule(model.group("/Policy"))
                .name(name)
                .errorField(model.num(BASE_PREMIUM))
                .when(condition)
                .code(name)
                .severity(Severity.ERROR)
                .message(Dsl.en("Premium adjustment is out of range."))
                .build();
    }

    private io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation computation(String name, NumberExpr operation) {
        return model.computation(model.num(ADJUSTMENT))
                .name(name)
                .calc(operation.expression())
                .message(Dsl.en("Adjustment is derived from the base premium."))
                .build()
                .draft();
    }
}
