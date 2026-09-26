package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * A starred group scope is an iteration over the group's rows exactly as a flattened field list is, so the profile
 * rates the two alike, whichever carrier holds the scope (DEV-API-GAPS DG77). The oracle is the field-list twin
 * over the same rows: {@code Parcels*} and {@code Parcels*}{@code /Weight} scan one list of five rows. Own domain:
 * an order shipped in parcels.
 */
class ConditionComplexityCountOperandTest {

    private static final String STRUCTURE = DocumentModelDraft.named("shop").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Note", FieldKind.STRING)
                    .group("Parcels", g -> g.repeatable(5).field("Weight", FieldKind.NUMBER)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(STRUCTURE);

    @Test
    void aGroupScopeIsProfiledAsTheFieldListThatIteratesTheSameRows() {
        ConditionComplexity scope = ConditionComplexity.of(Dsl.numberOfFilledFields(M.flattenGroup("/Order/Parcels*")).gt(0));
        ConditionComplexity list = ConditionComplexity.of(Dsl.numberOfFilledFields(M.flatten("/Order/Parcels*/Weight")).gt(0));
        assertEquals(shape(list), shape(scope));
    }

    /**
     * {@code SynthesisProfile} reads the scanned chain from each collection key's parent, so this is the number the
     * key form decides: a scope keyed without its own level scanned one row where the list scans five.
     */
    @Test
    void everyCarrierOfAStarredGroupScopeScansTheGroupsOwnRows() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (Map.Entry<String, Condition> carrier : Map.of(
                "Sum(Parcels*)", Dsl.sum(M.flattenGroup("/Order/Parcels*")).gt(0),
                "FirstFilledValue(Parcels*)", Dsl.firstFilledNumber(M.firstFilledGroup("/Order/Parcels*")).gt(0),
                "Sum(Parcels*/Weight), the field-list control", Dsl.sum(M.flatten("/Order/Parcels*/Weight")).gt(0))
                .entrySet()) {
            expected.put(carrier.getKey(), 5);
            actual.put(carrier.getKey(), SynthesisProfile.of(withRule(carrier.getValue())).rules().iteration()
                    .listScanCapacity().max());
        }
        assertEquals(expected, actual);
    }

    private static List<Integer> shape(ConditionComplexity c) {
        return List.<Function<ConditionComplexity, Integer>>of(ConditionComplexity::nodeCount,
                ConditionComplexity::depth, ConditionComplexity::maxStarSegments).stream().map(f -> f.apply(c)).toList();
    }

    private static String withRule(Condition condition) {
        Rule rule = M.rule(M.group("/Order")).name("Parcelled").errorField(M.field("/Order/Note"))
                .when(Dsl.fieldFilled(M.field("/Order/Note")).and(condition))
                .code("PARCELLED").severity(Severity.ERROR).message(Dsl.en("Parcels recorded.")).build();
        return M.add(rule).toJson();
    }
}
