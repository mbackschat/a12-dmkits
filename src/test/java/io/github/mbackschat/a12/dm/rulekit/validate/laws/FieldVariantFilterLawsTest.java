package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A FIELD's {@code variantFilter} — the third carrier of the member, and the one no lock covered.
 *
 * <p>{@code variantFilter} is declared on exactly three elements, and the enumeration is checkable: the
 * kernel's serializer package holds a {@code variantFilter} in {@code FieldSerializer}, {@code RuleSerializer}
 * and {@code ComputationSerializer} and nowhere else. <i>Searched:</i>
 * {@code grep -rln variantFilter ../a12-kernel/kernel-md/kernel-md-serializer --include='*Serializer.java'} —
 * three files, all three read; there is no group carrier. The rule and computation carriers are measured in
 * {@link RuleVariantFilterLawsTest} ([KERNEL-FINDINGS] KF299); this is the third.
 *
 * <p><b>The rule carrier does NOT predict this one, and that is why it is measured rather than transferred.</b>
 * On a rule, {@code ONLY} makes A12 say nothing about that rule — a suppression, so the safe response is
 * silence. A field is consumed somewhere else entirely: {@code ValidationModelConverter} keeps a field only
 * when {@code VariantFiltering.fieldIsAcceptedByFilter} accepts it, so a rejected field is absent from the
 * validation model rather than merely unjudged. Those two readings differ on a case this grid builds
 * deliberately — a rule whose condition names the filtered field. Under "a field filter suppresses" the model
 * is admitted; under "the field is dropped" the rule now names an entity that does not exist. One of the two
 * rows below settles it, which a source read alone could not.
 *
 * <p><b>Why {@code ONLY} and {@code ALL_EXCEPT} part at the DEFAULT variant.</b>
 * {@code filterAcceptsVariant} computes {@code variantMatchesFilter} under a {@code variant != null} guard, so
 * with no variant selected it is false for every filter: {@code ALL_EXCEPT} returns its negation and accepts,
 * {@code ONLY} returns it and rejects. That is a source reading of the mechanism and stays a hypothesis; the
 * grid is what settles the behaviour, and the {@code ALL_EXCEPT} rows are its control.
 *
 * <p>Own domain (the shared subscription fixture).
 */
class FieldVariantFilterLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ONLY = "{\"filterType\":\"ONLY\",\"variants\":[\"premium\"]}";
    private static final String ALL_EXCEPT = "{\"filterType\":\"ALL_EXCEPT\",\"variants\":[\"premium\"]}";

    @Test
    void whatAFieldsVariantFilterDoesToAModelNothingReferences() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", KernelLaws.verdictOf(filtered("Tier", null, false)));
        actual.put("ONLY, and no declaration names the field", KernelLaws.verdictOf(filtered("Tier", ONLY, false)));
        actual.put("ALL_EXCEPT, and no declaration names the field",
                KernelLaws.verdictOf(filtered("Tier", ALL_EXCEPT, false)));
        // The binding tier, as for the rule carrier: what A12's reader does with a filter it cannot bind.
        actual.put("an unknown filterType",
                KernelLaws.verdictOf(filtered("Tier", "{\"filterType\":\"SOMETIMES\",\"variants\":[]}", false)));
        actual.put("no filterType at all", KernelLaws.verdictOf(filtered("Tier", "{\"variants\":[]}", false)));
        // The rest of the binding tier, measured on THIS carrier rather than transferred from the rule one.
        // The two carriers agreeing is what a checker sharing one binding arm across them needs, and it is a
        // measurement here and not an inference: the same member's SEMANTIC law already differs by carrier
        // (a rule's `ONLY` suppresses, a field's deletes), so nothing about the member is uniform by default.
        actual.put("an explicit null", KernelLaws.verdictOf(filtered("Tier", "null", false)));
        actual.put("a string instead of an object", KernelLaws.verdictOf(filtered("Tier", "\"ONLY\"", false)));
        actual.put("a filterType that is JSON null",
                KernelLaws.verdictOf(filtered("Tier", "{\"filterType\":null}", false)));
        actual.put("a filterType that is an object",
                KernelLaws.verdictOf(filtered("Tier", "{\"filterType\":{}}", false)));
        actual.put("a filterType that is a number",
                KernelLaws.verdictOf(filtered("Tier", "{\"filterType\":9}", false)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("ONLY, and no declaration names the field", KernelLaws.ADMITTED);
        expected.put("ALL_EXCEPT, and no declaration names the field", KernelLaws.ADMITTED);
        expected.put("an unknown filterType", KernelLaws.UNREADABLE);
        // A DIFFERENT tier from the unknown spelling, and the same split the rule carrier shows: an unknown
        // name fails while the reader binds the string, while the absent member fails one step later, as A12
        // constructs the model from what it read. Both leave a model nobody can use; neither is an `MVK_*`.
        expected.put("no filterType at all", KernelLaws.NOT_BUILDABLE);
        expected.put("an explicit null", KernelLaws.ADMITTED);
        expected.put("a string instead of an object", KernelLaws.UNREADABLE);
        expected.put("a filterType that is JSON null", KernelLaws.UNREADABLE);
        expected.put("a filterType that is an object", KernelLaws.UNREADABLE);
        expected.put("a filterType that is a number", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what a field's variantFilter does when nothing references the field");
    }

    /**
     * The discriminating rows: a rule whose CONDITION names the filtered field.
     *
     * <p>This is the case the two rival readings disagree about, so it is built rather than inferred. The
     * {@code ALL_EXCEPT} row is its control — same model, same reference, the one filter type the mechanism
     * says accepts at the default variant — so a difference between the two rows cannot be the reference.
     */
    @Test
    void whatAFieldsVariantFilterDoesToARuleThatNamesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", KernelLaws.verdictOf(filtered("Tier", null, true)));
        actual.put("ONLY, with a rule naming the field", KernelLaws.verdictOf(filtered("Tier", ONLY, true)));
        actual.put("ALL_EXCEPT, with a rule naming the field",
                KernelLaws.verdictOf(filtered("Tier", ALL_EXCEPT, true)));
        // The same question with the two roles separated: this rule TARGETS `AutoRenew` and merely mentions
        // the filtered `Tier` in its condition, so nothing about its error target can explain the answer.
        actual.put("absent — the control, target elsewhere",
                KernelLaws.verdictOf(conditionOnly("Tier", null)));
        actual.put("ONLY, named only by the condition", KernelLaws.verdictOf(conditionOnly("Tier", ONLY)));
        actual.put("ALL_EXCEPT, named only by the condition",
                KernelLaws.verdictOf(conditionOnly("Tier", ALL_EXCEPT)));
        // A checker keying on the filter TYPE alone is claiming the variants list never matters here. That
        // holds on the rule carrier (KF299) and this is a different carrier, so it is measured rather than
        // inherited: the three lists that could plausibly rescue the field all fail to.
        actual.put("ONLY naming the DEFAULT variant, by empty string",
                KernelLaws.verdictOf(conditionOnly("Tier", "{\"filterType\":\"ONLY\",\"variants\":[\"\"]}")));
        actual.put("ONLY with an empty variants list",
                KernelLaws.verdictOf(conditionOnly("Tier", "{\"filterType\":\"ONLY\",\"variants\":[]}")));
        actual.put("ONLY with no variants member at all",
                KernelLaws.verdictOf(conditionOnly("Tier", "{\"filterType\":\"ONLY\"}")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        // The field is GONE from the validation model, not merely unjudged — and because this probe's error
        // target is that same field, A12 answers with the error-target pair rather than the entity code. The
        // rows below separate the two roles; this one alone could not.
        expected.put("ONLY, with a rule naming the field", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("ALL_EXCEPT, with a rule naming the field", KernelLaws.ADMITTED);
        expected.put("absent — the control, target elsewhere", KernelLaws.ADMITTED);
        expected.put("ONLY, named only by the condition", "MVK_INVALID_ENTITY");
        expected.put("ALL_EXCEPT, named only by the condition", KernelLaws.ADMITTED);
        expected.put("ONLY naming the DEFAULT variant, by empty string", "MVK_INVALID_ENTITY");
        expected.put("ONLY with an empty variants list", "MVK_INVALID_ENTITY");
        expected.put("ONLY with no variants member at all", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "what a field's variantFilter does to a rule that names it");
    }

    /**
     * The subscription fixture with [field]'s {@code variantFilter} set, and optionally a rule naming it.
     *
     * <p>The rule is added by the writer rather than by hand so its own shape is never the variable under
     * test: every row differs only in the field's filter member.
     */
    private static String filtered(String field, String rawFilter, boolean withReferencingRule) {
        String base = withReferencingRule
                ? ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "TierProbe",
                        "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")))
                : Fixtures.subscriptionJson();
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(base);
            ObjectNode body = fieldBody(root, field);
            if (rawFilter != null) {
                body.set("variantFilter", MAPPER.readTree(rawFilter));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragment must parse", unreadable);
        }
    }

    /**
     * A rule that TARGETS `AutoRenew` and only mentions [field] in its condition, with [field] filtered.
     *
     * <p>The condition names the target too, because A12 refuses a rule whose condition does not reference its
     * own error field (`MVK_ERROR_FIELD_NOT_REFERENCED`) — so naming both is what keeps this row's only
     * variable the filter.
     */
    private static String conditionOnly(String field, String rawFilter) {
        String base = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "MentionProbe",
                "../AutoRenew", "PROBE", "FieldNotFilled(AutoRenew) And FieldNotFilled(Tier)", "ERROR",
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(base);
            if (rawFilter != null) {
                fieldBody(root, field).set("variantFilter", MAPPER.readTree(rawFilter));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the raw fragment must parse", unreadable);
        }
    }

    /** The named field's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode fieldBody(ObjectNode root, String name) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (name.equals(element.path("name").asText()) && element.has("Field")) {
                return (ObjectNode) element.path("Field");
            }
        }
        throw new IllegalStateException("no field named " + name + " where this test writes it");
    }
}
