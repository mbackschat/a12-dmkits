package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The COMPUTATION carrier of the declaration-slot laws {@link RuleSlotLawsTest} measures on rules — run here
 * rather than inherited, because a law measured on one carrier is a claim about the other until it has been
 * run there (CLAUDE.md, the scope-transfer row).
 *
 * <p><b>Every row transfers, including the one whose code name says otherwise.</b>
 * {@code MVK_RULE_NAME_INVALID} reads as a rule's law and fires for a computation too — the kernel calls both
 * "Rule" in its own diagnostics, which is visible only once the row is run. Predicting the opposite is the
 * mistake a checker would make silently: refusing the prefix on one kind alone leaves the other unchecked,
 * and no consumer can detect a check that was never applied.
 *
 * <p>The rows exist because {@code Computation.Builder.build} and {@code LocalizedText.checkDeclared} apply
 * these refusals to a computation's messages citing these codes, on a basis that had only ever been measured
 * on rules.
 */
class ComputationSlotLawsTest {

    private static final String GROUP = "/Subscription/Billing";

    /** A scale-2 constant into the scale-2 target, so no operand law can move a row that is about slots. */
    private static final String OPERATION = "42.00";

    private static ModelConsistencyException rejected(String name, List<LocalizedText> messages) {
        String json = withProbeComputation(name, messages);
        return assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(json));
    }

    /** The same slot injection {@link RuleSlotLawsTest} uses, on the computation element instead. */
    private static String withProbeComputation(String name, List<LocalizedText> messages) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name(name).computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, OPERATION)))
                .messages(messages)
                .build();
        return ModelWriter.addComputation(Fixtures.subscriptionJson(), draft, messages);
    }

    private static void assertCode(ModelConsistencyException ex, String code) {
        assertTrue(ex.notifications().stream().anyMatch(n -> n.message().contains(code)),
                () -> "expected " + code + ", kernel said: " + ex.getMessage());
    }

    @Test
    void theProbeShapeItselfIsAccepted() {
        // negative control: the slot shape every row below varies is accepted when name + messages are legal
        assertNotNull(BoundModel.ofJson(withProbeComputation("ProbeShapeControl", List.of(en("x"), de("x")))));
    }

    @Test
    void aMessagelessComputationIsRejected() {
        assertCode(rejected("NoMessageProbe", List.of()), "MVK_ERROR_MESSAGE_MISSING");
    }

    @Test
    void aMessageMissingForADeclaredLocaleIsRejected() {
        // the fixture declares en_US and de_DE, so dropping the German text moves exactly one variable
        assertCode(rejected("MissingLocaleProbe", List.of(en("x"))), "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");
    }

    /** The row that had to be run: the reserved prefix is not the rule element's alone, despite its code name. */
    @Test
    void theReservedNamePrefixRejectsAComputationToo() {
        assertCode(rejected("vk_vk_probe", List.of(en("x"), de("x"))), "MVK_RULE_NAME_INVALID");
    }

    @Test
    void aBlankMessageTextCountsAsNoMessageAtAll() {
        assertCode(rejected("BlankMsgProbe", List.of(en(""), de(""))), "MVK_ERROR_MESSAGE_MISSING");
    }

    /**
     * The blank text of ONE declared locale, which separates the two message codes.
     *
     * <p>Every blank-text row measured before this one also left a declared locale with no entry at all, so
     * "blank counts as absent" and "this locale has no message" were the same observation and the code that
     * answered could not be attributed. Holding the other locale's text non-blank moves only the blank.
     */
    @Test
    void aBlankTextForOneDeclaredLocaleIsThatLocalesMessageMissing() {
        assertCode(rejected("BlankOneLocaleProbe", List.of(en("x"), de(""))),
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");
    }

    /** Tolerated here as on a rule — the deliberate dmkits refusal is an authoring policy, not A12's verdict. */
    @Test
    void aMessageForAnUndeclaredLocaleIsSilentlyTolerated() {
        assertNotNull(BoundModel.ofJson(withProbeComputation("UndeclaredLocaleProbe",
                List.of(en("x"), de("x"), new LocalizedText("fr_FR", "x")))));
    }

    /** Likewise duplicates: the DM-JSON tier keeps one text, and only the model-as-document tier refuses. */
    @Test
    void duplicateMessageLocalesAreSilentlyTolerated() {
        assertNotNull(BoundModel.ofJson(withProbeComputation("DupLocaleProbe",
                List.of(en("a"), en("b"), de("x")))));
    }
}
