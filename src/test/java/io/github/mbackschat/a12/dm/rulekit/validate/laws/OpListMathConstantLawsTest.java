package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * KERNEL-FINDINGS §5 — the numeric operand-list {@code Min}/{@code Max} per-call constant budget
 * (SPEC-2026-07-23-06). Each call independently admits at most ONE immediate-or-bracketed numeric
 * literal ({@code MVK_TOO_MANY_CONST_FOR_CALC_OP} on a second); a literal nested inside arithmetic
 * or another value-function call does NOT consume the outer call's budget, so nested calls must not
 * be flattened. This is the separating matrix — verified against the real kernel, which owns the
 * admission ({@code CheckFeldListenOperOpImpl}/{@code CheckOpUtils.isConstant}); dmkits carries the
 * list-preserving {@code OpListMath} and delegates the constant gate, so this is a regression lock
 * on the kernel contract, not a reimplemented check.
 */
class OpListMathConstantLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_FIELD = "../Quantity";
    private static final String TOO_MANY = "MVK_TOO_MANY_CONST_FOR_CALC_OP";

    private final String json = DocumentModelDraft.named("order-oplistmath").locale("en_US")
            .rootGroup("Order", group -> {
                group.numberField("Quantity", number -> number.fractionDigits(0));
                group.numberField("Discount", number -> number.fractionDigits(2));
            })
            .build().toJson();
    private final BoundModel bound = BoundModel.ofJson(json);
    private final NumberExpr qty = bound.num("/Order/Quantity").asNumber();

    @Test
    void oneImmediateLiteralPerCallIsAdmitted() {
        accepts(Dsl.min(qty, Dsl.number(1)).ge(0));
        accepts(Dsl.max(qty, Dsl.number(1)).ge(0));
    }

    @Test
    void aSecondImmediateLiteralInTheSameCallIsRejected() {
        rejects(Dsl.min(qty, Dsl.number(1), Dsl.number(2)).ge(0));
        rejects(Dsl.max(qty, Dsl.number(1), Dsl.number(2)).ge(0));
    }

    @Test
    void aBracketedLiteralStillCountsAgainstTheCallBudget() {
        // The kernel's grouping token is curly {}; CheckOpUtils.isConstant unwraps a BRACKET_OP, so a
        // bracketed literal is still ONE constant. The typed renderer normalizes redundant brackets
        // (it groups arithmetic only by precedence), so this kernel-parser recognition is expressed as
        // a literal string — there is no typed form that emits `{1}` around a bare literal.
        accepts("Min([Quantity], {1}) >= 0");
        rejects("Min([Quantity], {1}, 2) >= 0");
    }

    /**
     * The two literal SPELLINGS a token-level reader has to match by hand, because A12's number terminal is
     * a digit run: a decimal arrives as three tokens and a signed one as two. Measured rather than assumed
     * from {@code konstante}'s grammar — a reader that failed to recognize either would under-report, and
     * one that recognized a shape A12 does not would refuse a call it admits.
     */
    @Test
    void aDecimalAndASignedLiteralCountLikeAnyOther() {
        accepts("Min([Quantity], 1.5) >= 0");
        rejects("Min([Quantity], 1.5, 2) >= 0");
        accepts("Min([Quantity], -1) >= 0");
        rejects("Min([Quantity], -1, 2) >= 0");
    }

    @Test
    void aLiteralInsideArithmeticDoesNotConsumeTheBudget() {
        // `[Quantity] + 1` is an arithmetic operand, not an immediate constant; the sole immediate
        // constant is `2`, so the call has exactly one.
        accepts(Dsl.min(qty.plus(1), Dsl.number(2)).ge(0));
        accepts(Dsl.max(qty.plus(1), Dsl.number(2)).ge(0));
    }

    @Test
    void aLiteralInsideANestedCallDoesNotConsumeTheOuterBudget() {
        // Each nested call checks its own budget; the inner literal is invisible to the outer list.
        // Same-selector and different-selector nesting are both admitted and must not be flattened
        // (the flattened `Min([Quantity], 1, 2)` is rejected above).
        accepts(Dsl.min(Dsl.min(qty, Dsl.number(1)), Dsl.number(2)).ge(0));
        accepts(Dsl.min(Dsl.max(qty, Dsl.number(1)), Dsl.number(2)).ge(0));
    }

    @Test
    void aCompletedCallMayAppearInSurroundingArithmetic() {
        accepts(Dsl.min(qty, Dsl.number(1)).plus(1).ge(0));
        accepts(Dsl.max(qty, Dsl.number(1)).plus(1).ge(0));
    }

    private void accepts(Condition condition) {
        KernelLaws.acceptsModel(json, GROUP, ERROR_FIELD, render(condition));
    }

    private void accepts(String condition) {
        KernelLaws.acceptsModel(json, GROUP, ERROR_FIELD, condition);
    }

    private void rejects(Condition condition) {
        KernelLaws.rejectsModel(json, GROUP, ERROR_FIELD, render(condition), TOO_MANY);
    }

    private void rejects(String condition) {
        KernelLaws.rejectsModel(json, GROUP, ERROR_FIELD, condition, TOO_MANY);
    }

    private static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN, GROUP).render(condition);
    }
}
