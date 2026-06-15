package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The {@code seeAlso} bridge (FEATURE-GAPS §2 G7): operators that have a field-config twin point at it, so an
 * agent exploring the rule operator discovers the field-level alternative (and vice versa, via the field
 * schema's descriptions). The link is <b>factual</b> — it states what relates, not which to pick (mechanism
 * choice is the skill's job). This locks that the bridge exists and names the field-config knob it relates to.
 */
class OperatorSeeAlsoTest {

    /** Operator id → a field-config knob its seeAlso must reference (the rich-duality cases: STRING/NUMBER/ENUM). */
    private static final Map<String, String> EXPECTED = Map.of(
            "PatternViolated", "string.pattern",
            "PatternMatched", "string.pattern",
            "LessThan", "number.min",
            "GreaterThan", "number.min",
            "LessThanOrEqual", "number.min",
            "GreaterThanOrEqual", "number.min",
            "FieldValueIncludedInValueList", "enum.values");

    @Test
    void operatorsWithAFieldConfigTwinPointAtIt() {
        OperatorCatalog catalog = OperatorCatalog.load();
        EXPECTED.forEach((id, knob) -> {
            OperatorEntry e = catalog.byId(id).orElseThrow(() -> new AssertionError("no operator " + id));
            List<String> seeAlso = e.seeAlso();
            assertFalse(seeAlso.isEmpty(), () -> id + " should seeAlso its field-config twin (G7)");
            assertTrue(seeAlso.stream().anyMatch(s -> s.contains(knob)),
                    () -> id + " seeAlso should reference the field-config knob '" + knob + "': " + seeAlso);
        });
    }

    @Test
    void seeAlsoStaysFactualNotPrescriptive() {
        // neutral by design: it names the alternative + "field-level alternative", never "prefer"/"should use"
        OperatorCatalog catalog = OperatorCatalog.load();
        for (String id : EXPECTED.keySet()) {
            for (String s : catalog.byId(id).orElseThrow().seeAlso()) {
                String lower = s.toLowerCase(java.util.Locale.ROOT);
                assertFalse(lower.contains("prefer ") || lower.contains("you should") || lower.contains("recommended"),
                        () -> id + " seeAlso must stay factual (mechanism choice is skill territory): " + s);
            }
        }
    }
}
