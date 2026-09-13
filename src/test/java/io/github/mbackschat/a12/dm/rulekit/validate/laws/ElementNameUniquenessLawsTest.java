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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * How far a declaration's NAME must be unique — the scope, the kinds it spans, and its letter case.
 *
 * <p>{@link RuleSlotLawsTest#aDuplicateRuleNameIsRejectedByAThrowInsideCheckConsistency} establishes that two
 * rules of one name in one group make the model unbuildable. That single observation fits several accounts —
 * unique per group, per model, per kind, case-sensitively or not — which differ on models an author writes by
 * accident, so each boundary gets its own row here rather than being read off the one that is known.
 *
 * <p>Own domain (the shared subscription fixture: {@code /Subscription} with the field {@code Tier}, and the
 * nested {@code /Subscription/Billing}).
 */
class ElementNameUniquenessLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ROOT = "/Subscription";
    private static final String NESTED = "/Subscription/Billing";

    @Test
    void aNameMustBeUniqueAmongOneGroupsChildrenAndNoFurther() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one rule — the control", verdict(rule(Fixtures.subscriptionJson(), ROOT, "Probe")));
        actual.put("two rules, one group", verdict(rule(rule(Fixtures.subscriptionJson(), ROOT, "Probe"),
                ROOT, "Probe")));
        actual.put("two rules, different groups", verdict(nestedRule(rule(Fixtures.subscriptionJson(), ROOT,
                "Probe"), "Probe")));
        actual.put("a rule named after a sibling FIELD", verdict(rule(Fixtures.subscriptionJson(), ROOT,
                "Tier")));
        actual.put("a rule named after a sibling GROUP", verdict(rule(Fixtures.subscriptionJson(), ROOT,
                "Billing")));
        actual.put("a rule named after a field in ANOTHER group", verdict(rule(Fixtures.subscriptionJson(),
                ROOT, "BaseFee")));
        actual.put("two rules, one group, differing case", verdict(rule(rule(Fixtures.subscriptionJson(),
                ROOT, "Probe"), ROOT, "probe")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one rule — the control", KernelLaws.ADMITTED);
        // The Kernel refuses to BUILD the model — a rejection carrying no MVK_ code, so there is no verdict
        // about any declaration in it.
        expected.put("two rules, one group", KernelLaws.NOT_BUILDABLE);
        expected.put("two rules, different groups", KernelLaws.ADMITTED);
        // The demand spans KINDS, which is the row a "rule names are unique" reading gets wrong: the
        // collision is among a group's children, whatever each child is.
        expected.put("a rule named after a sibling FIELD", KernelLaws.NOT_BUILDABLE);
        expected.put("a rule named after a sibling GROUP", KernelLaws.NOT_BUILDABLE);
        expected.put("a rule named after a field in ANOTHER group", KernelLaws.ADMITTED);
        expected.put("two rules, one group, differing case", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "how far an element name must be unique");
    }

    /**
     * The collision is between any two children, and the RULE carrier cannot show that on its own.
     *
     * <p>Every row above adds a rule, so "two children of one name" and "a rule taking a name" are one
     * observation there — and the checker is kind-agnostic, which is a claim about carriers it never
     * measured. These rows add the collision without a rule anywhere in it.
     */
    @Test
    void theCollisionIsBetweenChildrenWhateverKindsTheyAre() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two FIELDS of one name", verdict(field(Fixtures.subscriptionJson(), "Tier")));
        actual.put("a field named after a sibling GROUP", verdict(field(Fixtures.subscriptionJson(), "Billing")));
        actual.put("a field in another group — the control",
                verdict(field(Fixtures.subscriptionJson(), "InvoiceEmail")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two FIELDS of one name", KernelLaws.NOT_BUILDABLE);
        expected.put("a field named after a sibling GROUP", KernelLaws.NOT_BUILDABLE);
        expected.put("a field in another group — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the collision without a rule in it");
    }

    /**
     * …and the two kinds no row above carries: a GROUP taking a sibling's name, and a COMPUTATION.
     *
     * <p>The portable checker keys this family by path alone and applies it to every kind, so a pairing A12
     * in fact ADMITTED would make it refuse a legal model — the one direction a checker may not take. The
     * grids above leave three pairings unmeasured, and {@code Computation} appears in none of them, so the
     * kind-agnostic reading rested on four of its seven cells. Each added kind brings its own
     * non-colliding control, because a group or a computation can also be refused on its own merits.
     */
    @Test
    void theSameCollisionWhenTheNewChildIsAGroupOrAComputation() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group of a fresh name — the control", verdict(group(Fixtures.subscriptionJson(), "Notes")));
        actual.put("two GROUPS of one name", verdict(group(Fixtures.subscriptionJson(), "Addons")));
        actual.put("a group named after a sibling FIELD", verdict(group(Fixtures.subscriptionJson(), "Tier")));
        actual.put("a computation of a fresh name — the control",
                verdict(computation(Fixtures.subscriptionJson(), "FeeTotal")));
        actual.put("a computation named after a sibling GROUP",
                verdict(computation(Fixtures.subscriptionJson(), "Addons")));
        actual.put("a computation named after a sibling FIELD",
                verdict(computation(Fixtures.subscriptionJson(), "Tier")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group of a fresh name — the control", KernelLaws.ADMITTED);
        expected.put("two GROUPS of one name", KernelLaws.NOT_BUILDABLE);
        expected.put("a group named after a sibling FIELD", KernelLaws.NOT_BUILDABLE);
        expected.put("a computation of a fresh name — the control", KernelLaws.ADMITTED);
        expected.put("a computation named after a sibling GROUP", KernelLaws.NOT_BUILDABLE);
        expected.put("a computation named after a sibling FIELD", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "the collision when the new child is a group or a computation");
    }

    /**
     * A second field of [name] added to the ROOT group, so the collision carries no rule at all.
     *
     * <p>Parsed rather than string-spliced: the fixture is pretty-printed, and a literal splice on
     * {@code "elements":[} matched nothing at all — three rows then came back ADMITTED and agreed with their
     * own control, which is what a silent no-op looks like from inside a table.
     */
    private static String field(String modelJson, String name) {
        return atRoot(modelJson, "{\"type\":\"Field\",\"id\":\"probe\",\"name\":\"" + name
                + "\",\"Field\":{\"fieldType\":{\"type\":\"StringType\"}}}");
    }

    /** A group of [name] added beside the root group's own children, carrying one field of its own. */
    private static String group(String modelJson, String name) {
        return atRoot(modelJson, "{\"type\":\"Group\",\"id\":\"probeGroup\",\"name\":\"" + name
                + "\",\"Group\":{\"elements\":[{\"type\":\"Field\",\"id\":\"probeGroupNote\",\"name\":\"Note\","
                + "\"Field\":{\"fieldType\":{\"type\":\"StringType\"}}}]}}");
    }

    /**
     * A computation of [name] writing the add-on total into the billing group's own fee field.
     *
     * <p>Its target and operation stay fixed across the rows so the only thing varying is the name — a
     * computation refused for its own shape would otherwise read as a collision.
     */
    private static String computation(String modelJson, String name) {
        return atRoot(modelJson, "{\"type\":\"Computation\",\"id\":\"probeComp\",\"name\":\"" + name
                + "\",\"Computation\":{\"computedFieldRelPath\":\"../Billing/EffectiveFee\","
                + "\"computationAlternatives\":[{\"operation\":\"Sum(Addons*/MonthlyFee)\"}],"
                + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"the effective fee\"},"
                + "{\"locale\":\"de_DE\",\"text\":\"das effektive Entgelt\"}]}}");
    }

    /** Inserts one declaration at the head of the ROOT group's children. */
    private static String atRoot(String modelJson, String declarationJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ((ArrayNode) root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")).insert(0, MAPPER.readTree(declarationJson));
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the added declaration must parse", unreadable);
        }
    }

    private static String verdict(String modelJson) {
        return KernelLaws.verdictOf(modelJson);
    }

    /** The probe rule at the root group, whose only varying part is what it is called. */
    private static String rule(String modelJson, String group, String name) {
        return ModelWriter.addRule(modelJson, group, name, "../Tier", "PROBE", "FieldNotFilled(Tier)",
                "ERROR", List.of(en("x"), de("y")));
    }

    /**
     * The same probe one level down, targeting that group's own field — the error target and the condition
     * move together, because a rule whose condition does not reference its target is refused for that
     * instead ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) and would move two variables at once.
     */
    private static String nestedRule(String modelJson, String name) {
        return ModelWriter.addRule(modelJson, NESTED, name, "../BaseFee", "PROBE",
                "FieldNotFilled(BaseFee)", "ERROR", List.of(en("x"), de("y")));
    }
}
