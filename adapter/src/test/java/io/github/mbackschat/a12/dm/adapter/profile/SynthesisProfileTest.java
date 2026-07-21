package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SynthesisProfileTest {

    @Test
    void freezesFlatAndNestedMapsInDeterministicOrder() {
        var fieldKinds = mutableCounts("STRING", "NUMBER");
        var ruleOperators = mutableCounts("Z", "A");
        var ruleScopeCounts = mutableScopes("perGroup", "modelLevel");
        var computationKinds = mutableCounts("STRING", "NUMBER");
        var computationScopeCounts = mutableScopes("perRepeatableRow", "modelLevel");

        var structure = SynthesisProfile.Structure.builder()
                .fieldKinds(fieldKinds)
                .build();
        var rules = SynthesisProfile.Rules.builder()
                .operators(ruleOperators)
                .constructsByScope(ruleScopeCounts)
                .build();
        var computations = SynthesisProfile.Computations.builder()
                .computedFieldKinds(computationKinds)
                .constructsByScope(computationScopeCounts)
                .build();

        fieldKinds.put("BOOLEAN", 3);
        ruleOperators.put("M", 3);
        ruleScopeCounts.get("perGroup").put("M", 3);
        ruleScopeCounts.put("perRepeatableRow", mutableCounts("X", "Y"));
        computationKinds.put("DATE", 3);
        computationScopeCounts.get("perRepeatableRow").put("M", 3);

        assertEquals(List.of("NUMBER", "STRING"), List.copyOf(structure.fieldKinds().keySet()));
        assertEquals(List.of("A", "Z"), List.copyOf(rules.operators().keySet()));
        assertEquals(List.of("modelLevel", "perGroup"), List.copyOf(rules.constructsByScope().keySet()));
        assertEquals(List.of("A", "Z"), List.copyOf(rules.constructsByScope().get("perGroup").keySet()));
        assertEquals(List.of("NUMBER", "STRING"), List.copyOf(computations.computedFieldKinds().keySet()));
        assertEquals(
                List.of("modelLevel", "perRepeatableRow"),
                List.copyOf(computations.constructsByScope().keySet()));
        assertEquals(
                List.of("A", "Z"),
                List.copyOf(computations.constructsByScope().get("perRepeatableRow").keySet()));
    }

    @Test
    void nullAndEmptySettersLeaveCompleteEmptyDefaults() {
        var structure = SynthesisProfile.Structure.builder()
                .fieldsPerGroup(null)
                .fieldKinds(null)
                .fieldKinds(Map.of())
                .repeatability(null)
                .constraints(null)
                .build();
        var rules = SynthesisProfile.Rules.builder()
                .scope(null)
                .nodeCount(null)
                .operators(null)
                .constructsByScope(Map.of())
                .iteration(null)
                .build();
        var computations = SynthesisProfile.Computations.builder()
                .scope(null)
                .computedFieldKinds(Map.of())
                .aggregateScaleMatch(null)
                .iteration(null)
                .aggregateStars(null)
                .build();

        assertEquals(Distribution.empty(), structure.fieldsPerGroup());
        assertTrue(structure.fieldKinds().isEmpty());
        assertNotNull(structure.repeatability());
        assertNotNull(structure.constraints());
        assertNotNull(rules.scope());
        assertEquals(Distribution.empty(), rules.nodeCount());
        assertTrue(rules.operators().isEmpty());
        assertTrue(rules.constructsByScope().isEmpty());
        assertNotNull(rules.iteration());
        assertNotNull(computations.scope());
        assertTrue(computations.computedFieldKinds().isEmpty());
        assertNotNull(computations.aggregateScaleMatch());
        assertNotNull(computations.iteration());
        assertEquals(Distribution.empty(), computations.aggregateStars());
    }

    private static LinkedHashMap<String, Integer> mutableCounts(String first, String second) {
        var result = new LinkedHashMap<String, Integer>();
        result.put(first, 1);
        result.put(second, 2);
        return result;
    }

    private static LinkedHashMap<String, Map<String, Integer>> mutableScopes(
            String first,
            String second) {
        var result = new LinkedHashMap<String, Map<String, Integer>>();
        result.put(first, mutableCounts("Z", "A"));
        result.put(second, mutableCounts("Z", "A"));
        return result;
    }
}
