package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate.CountOp;

/**
 * Which of {@code NumberOfFilledGroups}' gates refused a list of group entities, told apart by the problem text.
 *
 * <p>The class is withheld on every such list, since A12 reads a group's emptiness first
 * ({@code validate.laws.EntityListOperandReadLawsTest}), so the text is the only thing that says which gate
 * answered. Without it an index-selected group would read as "no fields", which the kernel's own gate order
 * contradicts ({@code validate.laws.GroupCountScopeOperandLawsTest}).
 */
class CountAggregateGroupScopeGateTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("warehouse-gates")
            .locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Weight", FieldKind.NUMBER)
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)))
            .rootGroup("Lot", l -> l.repeatable(3).field("Serial", FieldKind.STRING))
            .build().toJson());

    @Test
    void eachGateNamesItsOwnProblem() {
        var grading = M.flattenGroup("/Item/Grading");
        Map<String, List<AggregateOperand>> lists = new LinkedHashMap<>();
        lists.put("root beside another", List.of(grading, M.flattenGroup("/Lot*")));
        lists.put("index-selected", List.of(M.flattenGroup("/Item/Keyed").forIndex("k"), grading));
        lists.put("a field", List.of(grading, M.field("/Item/Weight")));
        lists.put("one unstarred", List.of(grading));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("root beside another", "takes a root group only as its sole operand");
        expected.put("index-selected", "takes no index-selected operand");
        expected.put("a field", "takes groups only, no fields");
        expected.put("one unstarred", "needs more than one group, or a single starred group scope");
        Map<String, String> actual = new LinkedHashMap<>();
        lists.forEach((name, operands) -> actual.put(name, problem(operands, expected.get(name))));
        assertEquals(expected, actual);
    }

    /** The expected fragment when the refusal's text carries it, else the whole text. */
    private static String problem(List<AggregateOperand> operands, String fragment) {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> new CountAggregate(CountOp.FILLED_GROUPS, operands, List.of()));
        assertFalse(refusal instanceof KernelCodeRefusal, refusal::getMessage);
        return refusal.getMessage().contains(fragment) ? fragment : refusal.getMessage();
    }
}
