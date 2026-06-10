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
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Kernel laws for the rule SLOTS (name, messages) — the locks for the 2026-06-10 MVK-ledger probes
 * (KERNEL-FINDINGS "Reserved rule-name prefix" + §13 blank-text supplement; MVK-LEDGER MM_Common
 * section). The condition-only {@link KernelLaws} harness can't vary name/messages, so these inject
 * raw slots via {@link ModelWriter#addRule} and assert what {@code checkConsistency} says on re-bind.
 */
class RuleSlotLawsTest {

    private static ModelConsistencyException rejected(String name, List<LocalizedText> messages) {
        String json = withProbeRule(name, messages);
        return assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(json));
    }

    private static String withProbeRule(String name, List<LocalizedText> messages) {
        return ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", name, "../Tier",
                "PROBE", "FieldNotFilled(Tier)", "ERROR", messages);
    }

    private static void assertCode(ModelConsistencyException ex, String code) {
        assertTrue(ex.notifications().stream().anyMatch(n -> n.getMessage().contains(code)),
                () -> "expected " + code + ", kernel said: " + ex.getMessage());
    }

    @Test
    void theProbeShapeItselfIsAccepted() {
        // negative control: the slot shape used below is fine when name + messages are legal
        assertNotNull(BoundModel.ofJson(withProbeRule("ProbeShapeControl", List.of(en("x"), de("x")))));
    }

    @Test
    void aVkVkPrefixedRuleNameIsRejected() {
        // names beginning vk_vk (any case) are the kernel's reserved namespace
        assertCode(rejected("vk_vk_probe", List.of(en("x"), de("x"))), "MVK_RULE_NAME_INVALID");
    }

    @Test
    void aBlankMessageTextCountsAsNoMessageAtAll() {
        // §13: blank text serializes as an absent message — MVK_ERROR_MESSAGE_MISSING, not an empty one
        assertCode(rejected("BlankMsgProbe", List.of(en(""))), "MVK_ERROR_MESSAGE_MISSING");
    }

    @Test
    void duplicateMessageLocalesAreSilentlyToleratedInDmJson() {
        // Discovered writing this lock (2026-06-10): the DM-JSON load tier does NOT fire
        // MVK_LANGUAGE_DUPLICATE — a rule with two en_US texts loads and checkConsistency passes
        // (the metamodel rule fires only in the model-as-document tier). One text silently wins,
        // which is exactly why rulekit's builder pre-checks duplicates (RuleAuthoringTest).
        assertNotNull(BoundModel.ofJson(withProbeRule("DupLocaleProbe", List.of(en("a"), en("b"), de("x")))));
    }

    @Test
    void aDuplicateRuleNameIsRejectedBeforeCheckConsistency() {
        // The same-group name collision is caught by the kernel's deserialize/convert tier
        // (IllegalArgumentException), not by the MVK_NAME_RULES_DUPLICATE metamodel rule —
        // the earlier tier shadows it (instrument-4 territory, KERNEL-FINDINGS pre-tier note).
        String once = withProbeRule("DupNameProbe", List.of(en("x"), de("x")));
        String twice = ModelWriter.addRule(once, "/Subscription", "DupNameProbe", "../Tier",
                "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("x")));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> BoundModel.ofJson(twice));
        assertTrue(ex.getMessage().contains("two child elements with the same name"), ex::getMessage);
    }
}
