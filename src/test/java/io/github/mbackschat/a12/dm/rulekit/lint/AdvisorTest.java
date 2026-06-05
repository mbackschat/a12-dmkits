package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * F4a gotcha/INFO channel (the third diagnostic kind — correct-but-surprising). The kernel is silent
 * here because the rule is <em>valid</em>; rulekit surfaces an INFO per catalog {@code gotcha} keyed
 * by the operators a rule uses, so an LLM sees the semantic traps before they bite.
 */
class AdvisorTest {

    private static final BoundModel SUB = Fixtures.subscription();
    private static final BoundModel ORDER = Fixtures.order();
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String EMAIL = "/Subscription/Billing/InvoiceEmail";

    private final Advisor advisor = new Advisor();

    @Test
    void surfacesAGotchaForAnOperatorTheRuleUses() {
        Condition c = SUB.field(NAME).filled();

        Diagnostic d = withOperator(advisor.advise(c), "FieldFilled");
        assertEquals(DiagnosticSource.LINT, d.source());
        assertEquals(Severity.INFO, d.severity());
        assertEquals(Optional.of("RK_GOTCHA"), d.code());
        assertTrue(d.summary().contains("formal error"), () -> "unexpected gotcha: " + d.summary());
        assertTrue(d.explain().isPresent(), "the operator meaning is the WHY");
    }

    @Test
    void surfacesAGotchaOnANestedCalculationNoConditionNodeNames() {
        // DifferenceInYears lives on a Calculation operand, not a Condition node — the walk must descend.
        Condition c = Dsl.differenceInYears(
                ORDER.date("/Order/DeliveryDate"), ORDER.date("/Order/OrderDate")).gt(1);

        Diagnostic d = withOperator(advisor.advise(c), "DifferenceInYears");
        assertEquals(Severity.INFO, d.severity());
        String s = d.summary().toLowerCase();
        assertTrue(s.contains("floor") || s.contains("leap"), () -> "unexpected gotcha: " + d.summary());
    }

    @Test
    void dedupesAGotchaWhenAnOperatorIsUsedTwice() {
        Condition c = SUB.field(NAME).filled().and(SUB.field(EMAIL).filled());

        long fieldFilled = advisor.advise(c).stream()
                .filter(d -> d.where().operator().filter("FieldFilled"::equals).isPresent())
                .count();
        assertEquals(1, fieldFilled, "the FieldFilled gotcha is about the operator, not per field");
    }

    @Test
    void suppressesRenderingTriviaGotchas() {
        // LessThan's only gotcha ("the symbol is the same in every language") is RENDERING — rulekit
        // handles it, so it must not reach the author. The number operands apply no operator.
        Condition c = SUB.num("/Subscription/Billing/BaseFee").lt("100");

        assertTrue(advisor.advise(c).stream()
                        .noneMatch(d -> d.summary().contains("same in every condition language")),
                () -> "rendering trivia leaked: " + advisor.advise(c));
    }

    @Test
    void aGotchaFreeOperatorProducesNothing() {
        // NoFieldFilled carries no catalog gotchas, and field refs apply no operator.
        Condition c = Dsl.noFieldFilled(SUB.field(NAME), SUB.field(EMAIL));

        assertTrue(advisor.advise(c).isEmpty(), () -> "unexpected gotchas: " + advisor.advise(c));
    }

    private static Diagnostic withOperator(List<Diagnostic> diagnostics, String operatorId) {
        return diagnostics.stream()
                .filter(d -> d.where().operator().filter(operatorId::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a gotcha for operator " + operatorId + ", got: " + diagnostics));
    }
}
