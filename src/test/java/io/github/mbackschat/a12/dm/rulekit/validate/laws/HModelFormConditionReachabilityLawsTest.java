package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Whether an A12 model can reach the H-model form-condition checks — the A1 legacy dispositions that
 * {@code MVK_CONDITION_WITH_WILDCARD} and {@code MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP} were left
 * unresolved on (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, A1 record).
 *
 * <p>Source reading gets as far as the sites and their guards and no further: {@code einfacheBedingung}
 * predicate-gates {@code zeilenBedingung} on {@code isHModel()} and deliberately does not gate
 * {@code formularBedingung}, and the only raiser of both codes is the outer {@code formularBedingung}
 * action. Whether that action is ever reached is a reachability question, so it is measured here.
 *
 * <p>The third case is the control that keeps the first two from proving too much: it shows the H-model
 * keywords are not excluded wholesale, because a different H-model construct does reach its checker and
 * draws the A12-facing rejection. Without it, "the parser refuses the keyword" and "this particular
 * construct is unreachable" would be indistinguishable.
 *
 * <p>Conditions are raw strings because these are legacy constructs the typed builders cannot express;
 * being unauthorable through rulekit is the premise here, not a gap in it.
 */
class HModelFormConditionReachabilityLawsTest {

    /** A repeatable root group, so the H-model level specifier below is structurally meaningful. */
    private static final String ORDERS = DocumentModelDraft.named("orders-repeatable").locale("en_US")
            .rootGroup("Order", o -> o.repeatable(9)
                    .field("Code", FieldKind.STRING)
                    .field("Count", FieldKind.NUMBER))
            .build().toJson();

    @Test
    void aFormConditionWithAWildcardNeverReachesTheWildcardCheck() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "VordruckAngegeben(Order/*)",
                "MVK_UNEXPECTED_TOKEN", "MVK_CONDITION_WITH_WILDCARD");
    }

    @Test
    void aFormConditionWithoutAWildcardNeverReachesTheRepeatableRootGroupCheck() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "MindestensEinVordruckAngegeben(Order)",
                "MVK_UNEXPECTED_TOKEN", "MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP");
    }

    /**
     * The control, and it came out the other way: the sibling H-model COUNT operation does not reach its
     * checker either, so the refusal is not specific to the form-condition production. Its checker's first
     * statement is the {@code isA12Model()} throw of {@code MVK_INVALID_ROOT_GROUP_CONSTR} — a code the A1
     * record currently disposes as in scope precisely because that guard fires only for A12 models. The
     * guard is real and the code is still unreachable from here, which is why a guard is not a reachability
     * proof. Why the parser refuses these keywords is NOT established: nothing here separates "the lexer
     * lacks them in this configuration" from "the entry production excludes them".
     */
    @Test
    void theSiblingHModelCountOperationIsRefusedAtTheSameLayer() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "AnzahlAngegebenerVordrucke(Order) > 0",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
    }

    /**
     * The second CARRIER, which the condition arm above does not settle. A computation parses through the
     * grammar's {@code operation} entry rule rather than {@code bedingung}, and inside
     * {@code einfacheOperation} the H-model siblings {@code feldListenOpHModel}/{@code feldOpHModel} are
     * predicate-gated on {@code isHModel()} while {@code anzahlAngegebenOpHModel} deliberately is not — the
     * same shape that lets a form condition reach a checker whose first statement is its A12 rejection. So
     * whether the code is reachable at all is a per-carrier question, not one the rule arm answers.
     */
    @Test
    void theCountOperationIsRefusedInAComputationCarrierToo() {
        ValidationResult result = new RuleValidator().check(ORDERS, new DraftComputation(
                "/Order", "CountComp", "../Count", null,
                List.of(new DraftComputation.Alternative(null, "{ AnzahlAngegebenerVordrucke(Order) }"))));

        assertFalse(result.ok(), "the H-model count operation must not be accepted in an A12 computation");
        assertTrue(
                result.diagnostics().stream().noneMatch(
                        d -> d.code().filter("MVK_INVALID_ROOT_GROUP_CONSTR"::equals).isPresent()),
                () -> "the A12-facing rejection was expected to be unreachable here too, got: "
                        + result.diagnostics());
    }

    /**
     * The harness control: an ordinary operator with an unresolvable operand must draw its own specific
     * code. Without it, every case above would also pass against a harness that reported
     * {@code MVK_UNEXPECTED_TOKEN} for anything it disliked.
     */
    @Test
    void anOrdinaryOperatorStillReachesTheSemanticChecks() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "FieldFilled(NoSuchField)",
                "MVK_INVALID_ENTITY", "MVK_UNEXPECTED_TOKEN");
    }
}
