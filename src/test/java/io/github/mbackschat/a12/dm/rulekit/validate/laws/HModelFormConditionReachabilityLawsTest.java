package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Whether an A12 model can reach the H-model form-condition checks — the A1 dispositions of
 * {@code MVK_INVALID_ROOT_GROUP_CONSTR}, {@code MVK_CONDITION_WITH_WILDCARD} and
 * {@code MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP} (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, A1 record).
 *
 * <p><b>The condition LANGUAGE is the variable, and holding it constant is what made the first answer
 * wrong.</b> These constructs have a German spelling and no English one — {@code LexerTerminals_en} simply
 * does not carry {@code VordruckAngegeben} — so in an {@code en_US} model they are ordinary names and the
 * parser rejects them before any checker runs. Measured only there, "refused at the parser" and "reached
 * the checker" are indistinguishable. Both languages are therefore measured, and the German arm is the one
 * that answers the reachability question.
 *
 * <p>Two further controls keep the German arm from proving too much: a {@code zeilenBedingung}, which the
 * grammar predicate-gates on {@code isHModel()}, is still refused at the parser in a German model, and an
 * ordinary German condition is accepted there while its English spelling is not.
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

    /**
     * The same model with only the condition language changed. {@code DocumentModelDraft} always stamps
     * {@code en_US}, which is why every earlier row shared it; this is the within-model control that moves
     * exactly one variable.
     */
    private static final String ORDERS_DE = ModelDocument.read(ORDERS).setConditionLanguage("de_DE").toJson();

    /**
     * All three constructs DO reach their checker in an A12 model, and are refused there by name with the
     * A12-facing {@code MVK_INVALID_ROOT_GROUP_CONSTR} ("only allowed to be used in H models").
     *
     * <p>That code therefore pre-empts the two the outer action would raise: the grammar attaches
     * {@code checkFormularBedingungName} to {@code formularBedingungName} and
     * {@code checkFormularBedingung} to the enclosing {@code formularBedingung}, so the name's own
     * {@code isA12Model()} throw runs first and neither {@code MVK_CONDITION_WITH_WILDCARD} nor
     * {@code MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP} can be reached by an A12 model.
     */
    @Test
    void theFormConstructsReachTheirCheckerAndDrawTheA12Rejection() {
        KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", "VordruckAngegeben(Order/*)",
                "MVK_INVALID_ROOT_GROUP_CONSTR", "MVK_CONDITION_WITH_WILDCARD");
        KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", "MindestensEinVordruckAngegeben(Order)",
                "MVK_INVALID_ROOT_GROUP_CONSTR", "MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP");
        KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", "AnzahlAngegebenerVordrucke(Order) > 0",
                "MVK_INVALID_ROOT_GROUP_CONSTR", "MVK_UNEXPECTED_TOKEN");
    }

    /**
     * The other FOUR form-condition names, so the seven-member denominator is measured rather than argued.
     *
     * <p>All six {@code formularBedingungName} alternatives run one action — {@code checkFormularBedingungName},
     * whose {@code isA12Model()} throw is the first statement — and the count operation has its own copy of it.
     * Reading that is what makes the transfer plausible; it is not what makes it a fact, and a grid that
     * measured three of seven and named the class for all seven is the sibling case the oracle table warns
     * about. Measured 2026-09-15, for the portable checker's own denominator.
     */
    @Test
    void theRemainingFormConditionNamesDrawTheSameRejection() {
        for (String name : List.of("VordruckNichtAngegeben", "KeinVordruckAngegeben",
                "VordruckNichtFortlaufend", "UsbNichtFortlaufend")) {
            KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", name + "(Order)",
                    "MVK_INVALID_ROOT_GROUP_CONSTR", "MVK_UNEXPECTED_TOKEN");
            KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", name + "(Order)",
                    "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
        }
    }

    /**
     * The second CARRIER. A computation parses through the grammar's {@code operation} entry rule rather
     * than {@code bedingung}, so reachability there is a separate measurement — and it agrees.
     */
    @Test
    void theCountOperationDrawsTheSameRejectionInAComputationCarrier() {
        ValidationResult result = new RuleValidator().check(ORDERS_DE, new DraftComputation(
                "/Order", "CountComp", "../Count", null,
                List.of(new DraftComputation.Alternative(null, "{ AnzahlAngegebenerVordrucke(Order) }"))));

        assertFalse(result.ok(), "the H-model count operation must not be accepted in an A12 computation");
        assertTrue(
                result.diagnostics().stream().anyMatch(
                        d -> d.code().filter("MVK_INVALID_ROOT_GROUP_CONSTR"::equals).isPresent()),
                () -> "expected the A12-facing rejection on the computation carrier, got: "
                        + result.diagnostics());
    }

    /**
     * The control that keeps the arm above from proving too much: a row condition IS refused at the parser
     * even in German, because {@code einfacheBedingung} predicate-gates {@code zeilenBedingung} on
     * {@code isHModel()} while deliberately leaving {@code formularBedingung} un-predicated. So the two
     * layers are separable, and the German arm's verdicts are the checker's rather than the parser's.
     */
    @Test
    void aPredicateGatedRowConditionIsStillRefusedAtTheParser() {
        KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", "ZeilenLueckenlos(Code)",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
    }

    /**
     * The same three constructs in an ENGLISH model, where the lexer has no such terminals: every one is
     * refused with {@code MVK_UNEXPECTED_TOKEN} and no checker runs. This is the row set that, taken alone,
     * read as "the parser refuses these constructs for an A12 model".
     */
    @Test
    void theSameConstructsInAnEnglishModelNeverReachTheChecker() {
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "VordruckAngegeben(Order/*)",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "MindestensEinVordruckAngegeben(Order)",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
        KernelLaws.rejectsModelInsteadOf(ORDERS, "/Order", "../Code", "AnzahlAngegebenerVordrucke(Order) > 0",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ROOT_GROUP_CONSTR");
    }

    /**
     * The language control, both directions: the flip really took effect, and it is the keyword vocabulary
     * it changes. A German model accepts the German spelling and refuses the English one at the parser.
     */
    @Test
    void theConditionLanguageDecidesWhichKeywordsExist() {
        KernelLaws.acceptsModel(ORDERS_DE, "/Order", "../Code", "FeldAngegeben(Code)");
        KernelLaws.rejectsModelInsteadOf(ORDERS_DE, "/Order", "../Code", "FieldFilled(Code)",
                "MVK_UNEXPECTED_TOKEN", "MVK_INVALID_ENTITY");
        KernelLaws.acceptsModel(ORDERS, "/Order", "../Code", "FieldFilled(Code)");
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
