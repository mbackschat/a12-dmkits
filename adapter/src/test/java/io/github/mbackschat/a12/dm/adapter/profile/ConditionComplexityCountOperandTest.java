package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void everyIndexedGroupCarrierKeepsTheSelectionAndItsLowerStars() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("depot-group-profiles").locale("en_US")
                .rootGroup("Depot", depot -> depot.stringField("Wanted", f -> { })
                        .numberField("Limit", f -> f.fractionDigits(0))
                        .group("Boxes", boxes -> boxes.repeatable(3).indexField("Code")
                                .stringField("Code", f -> { })
                                .group("Details", details -> details.numberField("Weight", f -> f.fractionDigits(0)))
                                .group("Loads", loads -> loads.repeatable(4)
                                        .group("Units", units -> units.repeatable(2)
                                                .numberField("Weight", f -> f.fractionDigits(0))))))
                .build().toJson());
        String path = "/Depot/Boxes/Loads*/Units*";
        var scope = model.flattenGroup(path);
        var limit = model.num("/Depot/Limit");
        var expectedCollections = Set.of("/Depot/Boxes/Loads/", "/Depot/Boxes/Loads/Units/");
        for (var selected : List.of(scope.forIndex("B1"), scope.forIndex(model.field("/Depot/Wanted")))) {
            Map<String, Condition> carriers = Map.of(
                    "aggregate", Dsl.sum(selected).gt(0),
                    "count", Dsl.numberOfFilledFields(selected).gt(0),
                    "field predicate", Dsl.atLeastOneFieldFilled(selected),
                    "group predicate", Dsl.atLeastOneGroupFilled(selected),
                    "presence", selected.filled(),
                    "membership subject", Dsl.atLeastOneFieldValueIncludedInValueList(List.of(limit.field()), selected),
                    "membership values", Dsl.atLeastOneFieldValueIncludedInValueList(List.of(selected), limit.field()),
                    "first-filled control", Dsl.firstFilledNumber(model.firstFilledGroup(path).forIndex("B1")).gt(0));
            for (var carrier : carriers.entrySet()) {
                var complexity = ConditionComplexity.of(carrier.getValue());
                assertEquals(2, complexity.maxStarSegments(), carrier.getKey());
                assertEquals(expectedCollections, complexity.collectionPaths(), carrier.getKey());
                assertEquals(1, complexity.operatorHistogram().get("SemanticIndex"), carrier.getKey());
                assertTrue(complexity.constructs().contains(ConditionComplexity.Construct.SEMANTIC_INDEX), carrier.getKey());
            }
            assertTrue(ConditionComplexity.of(Dsl.atLeastOneFieldFilled(selected)).constructs()
                    .contains(ConditionComplexity.Construct.GROUP_FILL));
        }
        for (Condition condition : List.of(Dsl.atLeastOneFieldFilled(scope), Dsl.atLeastOneGroupFilled(scope),
                Dsl.atLeastOneFieldValueIncludedInValueList(List.of(limit.field()), scope))) {
            var complexity = ConditionComplexity.of(condition);
            assertEquals(2, complexity.maxStarSegments());
            assertEquals(expectedCollections, complexity.collectionPaths());
            assertEquals(null, complexity.operatorHistogram().get("SemanticIndex"));
        }
        var fixed = model.flattenGroup("/Depot/Boxes/Details");
        var plain = ConditionComplexity.of(Dsl.atLeastOneGroupFilled(fixed));
        var selected = ConditionComplexity.of(Dsl.atLeastOneGroupFilled(fixed.forIndex("B1")));
        assertFalse(plain.operatorHistogram().containsKey("SemanticIndex"));
        assertFalse(plain.constructs().contains(ConditionComplexity.Construct.SEMANTIC_INDEX));
        assertEquals(1, selected.operatorHistogram().get("SemanticIndex"));
        assertTrue(selected.constructs().contains(ConditionComplexity.Construct.SEMANTIC_INDEX));
        for (var control : List.of(plain, selected)) {
            assertEquals(0, control.maxStarSegments());
            assertEquals(Set.of(), control.collectionPaths());
            assertEquals(1, control.nodeCount());
            assertEquals(1, control.depth());
        }
        var selectedField = model.field("/Depot/Boxes/Code").semanticIndex("B1").index();
        var scalar = ConditionComplexity.of(Dsl.atLeastOneGroupFilled(selectedField));
        assertEquals(1, scalar.operatorHistogram().get("SemanticIndex"));
        assertEquals(0, scalar.maxStarSegments());
        assertEquals(Set.of(), scalar.collectionPaths());
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
