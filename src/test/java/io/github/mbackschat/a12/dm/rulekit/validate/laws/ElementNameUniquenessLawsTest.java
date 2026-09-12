package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
