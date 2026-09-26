package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        assertTrue(ex.notifications().stream().anyMatch(n -> n.message().contains(code)),
                () -> "expected " + code + ", kernel said: " + ex.getMessage());
    }

    /**
     * An {@code errorMessage} member that is ABSENT and one that is an empty array are both refused.
     *
     * <p>A12's own serializer omits an empty member, so the writer emits the absent shape and stored DM-JSON can
     * carry either; a reader that equates the two is asserting a transfer only these rows run. Measured because
     * the wrong answer is the dangerous one: treating absent as "no messages" would refuse a model A12 might
     * accept.
     */
    @Test
    void anAbsentErrorMessageMemberIsRejected() {
        String json = withProbeRule("NoMessageMemberProbe", List.of());
        assertTrue(!errorMessageMemberPresent(json), "the writer omits an empty errorMessage, as A12 does");
        assertCode(assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(json)),
                "MVK_ERROR_MESSAGE_MISSING");
    }

    @Test
    void anEmptyErrorMessageArrayIsRejected() {
        String json = withEmptyErrorMessageMember(withProbeRule("EmptyMessageMemberProbe", List.of()));
        assertCode(assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(json)),
                "MVK_ERROR_MESSAGE_MISSING");
    }

    /**
     * With NO declared locale there is no per-locale demand left — and the message demand survives anyway.
     *
     * <p>The cell matters because every row above holds two declared locales, so "a rule needs a message"
     * and "a rule needs a message for each declared locale" are the same observation across the whole set.
     * Emptying {@code header.locales} is the only row on the other side of that axis: it settles that a
     * checker may report {@code MVK_ERROR_MESSAGE_MISSING} without first knowing what the model declares.
     */
    @Test
    void aMessagelessRuleIsRejectedEvenWhenTheModelDeclaresNoLocale() {
        String json = withoutDeclaredLocales(withEmptyErrorMessageMember(withProbeRule("NoLocaleProbe", List.of())));
        assertCode(assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(json)),
                "MVK_ERROR_MESSAGE_MISSING");
    }

    /** Empty {@code header.locales}, refusing rather than silently passing an unchanged model through. */
    private static String withoutDeclaredLocales(String json) {
        try {
            ObjectNode root = (ObjectNode) new ObjectMapper().readTree(json);
            ObjectNode header = (ObjectNode) root.path("header");
            if (!header.path("locales").isArray() || header.path("locales").isEmpty()) {
                throw new IllegalStateException("the fixture declares no locales to remove");
            }
            header.putArray("locales");
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output must be readable", unreadable);
        }
    }

    /** Give the probe rule an empty {@code errorMessage} array, refusing when it already carries the member. */
    private static String withEmptyErrorMessageMember(String json) {
        ObjectNode root = tree(json);
        ObjectNode body = probeRuleBody(root);
        if (body.has("errorMessage")) {
            throw new IllegalStateException("the probe rule already carries an errorMessage member");
        }
        body.putArray("errorMessage");
        return root.toString();
    }

    private static boolean errorMessageMemberPresent(String json) {
        return probeRuleBody(tree(json)).has("errorMessage");
    }

    /** The body of the one rule the probe fixture's root group carries. */
    private static ObjectNode probeRuleBody(ObjectNode root) {
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (element.path("Rule").isObject()) {
                return (ObjectNode) element.path("Rule");
            }
        }
        throw new IllegalStateException("the fixture carries no probe rule");
    }

    private static ObjectNode tree(String json) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(json);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output must be readable", unreadable);
        }
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

    /**
     * The reserved namespace is case-INSENSITIVE in the kernel, so {@code Rule.build()}'s
     * {@code regionMatches(true, …)} pre-check cannot over-reject. Locked because the builder asserted "any
     * letter case" while only the lowercase form was ever kernel-verified — a client-side refusal broader than
     * its measured basis (MVK-LEDGER pre-checked audit, 2026-07-25).
     */
    @Test
    void theReservedPrefixIsRejectedInAnyLetterCase() {
        assertCode(rejected("VK_VK_probe", List.of(en("x"), de("x"))), "MVK_RULE_NAME_INVALID");
        assertCode(rejected("Vk_Vk_probe", List.of(en("x"), de("x"))), "MVK_RULE_NAME_INVALID");
    }

    @Test
    void aBlankMessageTextCountsAsNoMessageAtAll() {
        // §13: blank text serializes as an absent message — MVK_ERROR_MESSAGE_MISSING, not an empty one
        assertCode(rejected("BlankMsgProbe", List.of(en(""))), "MVK_ERROR_MESSAGE_MISSING");
    }

    /**
     * The blank text of ONE declared locale, which separates the two message codes.
     *
     * <p>The row above and every other blank-text measurement here left the other declared locale with no
     * entry at all, so "blank counts as absent" and "this locale has no message" were one observation and
     * the code that answered could not be attributed to either. Holding {@code en_US}'s text non-blank moves
     * only the blank.
     */
    @Test
    void aBlankTextForOneDeclaredLocaleIsThatLocalesMessageMissing() {
        assertCode(rejected("BlankOneLocaleProbe", List.of(en("x"), de(""))),
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");
    }

    /**
     * The locale-coverage law, measured rather than inherited. {@code LocalizedText.checkDeclared} refuses
     * this shape citing {@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}, and until now nothing here had asked
     * the kernel — a client-side refusal whose basis was a code name. The fixture declares {@code en_US} and
     * {@code de_DE}, so dropping the German text moves exactly one variable against
     * {@link #theProbeShapeItselfIsAccepted}.
     */
    @Test
    void aMessageMissingForADeclaredLocaleIsRejected() {
        assertCode(rejected("MissingLocaleProbe", List.of(en("x"))), "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");
    }

    /**
     * The other half of the same law, and the one a checker must NOT reproduce: a text for a locale the model
     * never declares is tolerated. {@code LocalizedText.checkDeclared} refuses it deliberately — it usually
     * hides a typo — so this row is what makes that an authoring policy rather than an A12 rejection.
     */
    @Test
    void aMessageForAnUndeclaredLocaleIsSilentlyTolerated() {
        assertNotNull(BoundModel.ofJson(withProbeRule("UndeclaredLocaleProbe",
                List.of(en("x"), de("x"), new LocalizedText("fr_FR", "x")))));
    }

    @Test
    void duplicateMessageLocalesAreSilentlyToleratedInDmJson() {
        // Discovered writing this lock (2026-06-10): the DM-JSON load tier does NOT fire
        // MVK_LANGUAGE_DUPLICATE — a rule with two en_US texts loads and checkConsistency passes
        // (the metamodel rule fires only in the model-as-document tier). One text silently wins,
        // which is exactly why rulekit's builder pre-checks duplicates (RuleAuthoringTest).
        assertNotNull(BoundModel.ofJson(withProbeRule("DupLocaleProbe", List.of(en("a"), en("b"), de("x")))));
    }

    /**
     * The same-group name collision is caught by a kernel THROW, not by the {@code MVK_NAME_RULES_DUPLICATE}
     * metamodel rule — the kernel refuses to build the model at all and the metamodel rule never gets to
     * report (instrument-4 territory, KERNEL-FINDINGS pre-tier note).
     *
     * <p><b>The throw happens INSIDE {@code checkConsistency}, not before it</b>, which is what makes it this
     * gate's business: the adapter's guard wraps that call alone and never {@code expand}, and a mutant on that
     * catch clause kills this test. An earlier name and wording here said "before checkConsistency" and
     * "deserialize/convert tier" — pointing a future reader at the one tier the guard deliberately excludes.
     *
     * <p><b>It arrives as a REJECTION, and used not to.</b> The kernel throws a bare
     * {@code IllegalArgumentException} there, which escaped {@code ModelLoader.loadAndCheck} and reached a
     * caller as an internal-defect envelope — asserting the opposite of the truth, since the input is invalid
     * and the kernel is what said so ([KF259]). This is the second member of that class: the first was a blank
     * temporal {@code format}, and this one was never reported, which is what makes it the confirmation rather
     * than a second symptom.
     */
    @Test
    void aDuplicateRuleNameIsRejectedByAThrowInsideCheckConsistency() {
        String once = withProbeRule("DupNameProbe", List.of(en("x"), de("x")));
        String twice = ModelWriter.addRule(once, "/Subscription", "DupNameProbe", "../Tier",
                "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("x")));
        ModelConsistencyException ex =
                assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(twice));
        assertTrue(ex.getMessage().contains("two child elements with the same name"), ex::getMessage);
        assertTrue(ex.notifications().stream()
                        .anyMatch(n -> n.message().contains("the kernel could not build this model")),
                () -> "the rejection must carry the kernel's own reason: " + ex.notifications());
    }
}
