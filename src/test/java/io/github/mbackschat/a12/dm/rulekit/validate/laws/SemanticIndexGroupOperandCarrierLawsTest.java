package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;

/**
 * KERNEL-FINDINGS KF351 — a GROUP operand carrying a semantic index ({@code Packages For "P1"}) is admitted on
 * every carrier that takes a group operand, with two exceptions. {@code NumberOfFilledGroups} refuses it
 * ({@code MVK_SEMANTIC_INDEX_NOT_ALLOWED}), and a single-operand {@code AllGroupsFilled} is refused for its
 * arity ({@code MVK_PARAMSIZE_INVALID2}), as it is without an index. The spelling {@code GroupFilled(G For k)}
 * is admitted.
 *
 * <p>So an indexed group ALONE satisfies the arity of {@code Sum}, {@code MaxValue}, {@code MinValue},
 * {@code NumberOfDifferentValues} and {@code NumberOfFilledFields}, as an unindexed group scope does, and two
 * indexed groups differing only in the key are no duplicate. This grid is the denominator for the typed surface
 * below and for the adapter's read of these shapes ({@code adapter.SemanticIndexGroupOperandReadTest}).
 *
 * <p>Own domain: {@link ModelDrafts#shipping()}.
 */
class SemanticIndexGroupOperandCarrierLawsTest {

    private static final String MODEL = ModelDrafts.shipping().toJson();
    private static final String ADMITTED = KernelLaws.ADMITTED;

    @Test
    void anIndexedGroupIsAdmittedOnEveryGroupCarrier_butTwo() {
        Map<String, String> expected = new LinkedHashMap<>();
        // group-list quantifiers
        expected.put("NoGroupFilled(Packages For \"P1\")", ADMITTED);
        expected.put("NoGroupFilled(Packages For Title)", ADMITTED);
        expected.put("GroupFilled(Packages For \"P1\")", ADMITTED);
        expected.put("GroupNotFilled(Packages For \"P1\")", ADMITTED);
        expected.put("AtLeastOneGroupFilled(Packages/Parcels* For \"P1\")", ADMITTED);
        expected.put("AtLeastOneGroupFilled(Packages For \"P1\", Title)", ADMITTED);
        expected.put("AllGroupsFilled(Packages For \"P1\", Packages For \"P2\")", ADMITTED);
        expected.put("NotAllGroupsFilled(Packages For \"P1\", Packages For \"P2\")", ADMITTED);
        expected.put("GroupsNotCollectivelyFilled(Packages For \"P1\", Packages For \"P2\")", ADMITTED);
        expected.put("AllGroupsFilled(Packages For \"P1\")", "MVK_PARAMSIZE_INVALID2");
        // field-fill quantifiers over a group scope
        expected.put("AllFieldsFilled(Packages For \"P1\")", ADMITTED);
        expected.put("NoFieldFilled(Packages/Parcels* For \"P1\")", ADMITTED);
        expected.put("AllFieldsFilled(Packages For \"P1\", Packages For \"P2\")", ADMITTED);
        expected.put("AllFieldsFilled(Packages For \"P1\", Title)", ADMITTED);
        // aggregates, a lone indexed group included
        expected.put("NumberOfFilledFields(Packages For \"P1\") > 0", ADMITTED);
        expected.put("NumberOfFilledFields(Packages/Parcels* For \"P1\") > 0", ADMITTED);
        expected.put("NumberOfFilledFields(Packages For \"P1\", Title) > 0", ADMITTED);
        expected.put("NumberOfFilledFields(Packages For \"P1\", Packages For \"P2\") > 0", ADMITTED);
        expected.put("NumberOfDifferentValues(Packages/Parcels* For \"P1\") > 0", ADMITTED);
        expected.put("NumberOfValueInFields(\"x\" In Packages/Parcels* For \"P1\") > 0", ADMITTED);
        expected.put("Sum(Packages/Dimensions For \"P1\") > 0", ADMITTED);
        expected.put("MaxValue(Packages/Dimensions For \"P1\") > 0", ADMITTED);
        expected.put("MinValue(Packages/Dimensions For \"P1\") > 0", ADMITTED);
        expected.put("Sum(Packages/Dimensions For \"P1\", Packages/Dimensions For \"P2\") > 0", ADMITTED);
        expected.put("MaxValue(Packages/Schedule For \"P1\") == [Packages/Schedule/ShippedOn For \"P2\"]", ADMITTED);
        expected.put("NumberOfFilledGroups(Packages For \"P1\") > 0", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition,
                KernelLaws.verdictModel(MODEL, "/Order", "../Title", "FieldFilled(Title) And " + condition)));
        assertEquals(expected, actual);
    }

    /** The typed surface authors each admitted shape, and A12 admits what it renders. */
    @Test
    void theTypedSurfaceAuthorsEveryAdmittedCarrier() {
        BoundModel m = BoundModel.ofJson(MODEL);
        var p1 = new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages"), "P1");
        var p2 = new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages"), "P2");
        var byTitle = new GroupSemanticIndexFieldValue(m.flattenGroup("/Order/Packages"), m.field("/Order/Title"));
        var parcelsOfP1 = new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages/Parcels*"), "P1");
        var dimensionsOfP1 = new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages/Dimensions"), "P1");
        var dimensionsOfP2 = new GroupSemanticIndexValue(m.flattenGroup("/Order/Packages/Dimensions"), "P2");
        var title = m.field("/Order/Title");

        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("NoGroupFilled one", Dsl.noGroupFilled(p1));
        authored.put("NoGroupFilled field key", Dsl.noGroupFilled(byTitle));
        authored.put("AtLeastOneGroupFilled starred", Dsl.atLeastOneGroupFilled(parcelsOfP1));
        authored.put("AtLeastOneGroupFilled mixed", Dsl.atLeastOneGroupFilled(p1, title));
        authored.put("AllGroupsFilled two keys", Dsl.allGroupsFilled(p1, p2));
        authored.put("AllFieldsFilled one", Dsl.entityListPredicate(Quantifier.ALL_FIELDS_FILLED, List.of(p1)));
        authored.put("AllFieldsFilled mixed",
                Dsl.entityListPredicate(Quantifier.ALL_FIELDS_FILLED, List.of(p1, title)));
        authored.put("NumberOfFilledFields one", Dsl.numberOfFilledFields(p1).gt(0));
        authored.put("NumberOfFilledFields starred", Dsl.numberOfFilledFields(parcelsOfP1).gt(0));
        authored.put("NumberOfFilledFields two keys", Dsl.numberOfFilledFields(p1, p2).gt(0));
        authored.put("NumberOfDifferentValues one", Dsl.numberOfDifferentValues(parcelsOfP1).gt(0));
        authored.put("Sum one", Dsl.sum(dimensionsOfP1).gt(0));
        authored.put("MaxValue one", Dsl.maxValue(dimensionsOfP1).gt(0));
        authored.put("MinValue one", Dsl.minValue(dimensionsOfP1).gt(0));
        authored.put("Sum two keys", Dsl.sum(dimensionsOfP1, dimensionsOfP2).gt(0));

        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        Condition filled = title.filled();
        authored.forEach((row, condition) -> {
            expected.put(row, ADMITTED);
            actual.put(row, KernelLaws.verdictModel(MODEL, "/Order", "../Title", filled.and(condition)));
        });
        assertEquals(expected, actual);
    }
}
