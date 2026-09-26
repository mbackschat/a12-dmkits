package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ComparisonCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * DEV-API-GAPS DG77, the read half — the kernel-backed converter reads a stored count back as the record either
 * authored spelling builds. It picks the {@code GroupRef} or the scope factory by operand shape, fixed groups on
 * one arm and an ancestor-starred list on the other, so the choice must not reach the value. Rendered text is not
 * the oracle here: the two shapes rendered alike while being unequal records. Own domain: an order in parcels.
 */
class CountAggregateReadShapeTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("shop").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Note", FieldKind.STRING)
                    .group("Billing", g -> g.field("Account", FieldKind.STRING))
                    .group("Shipping", g -> g.field("Carrier", FieldKind.STRING))
                    .group("Parcels", g -> g.repeatable(5).field("Weight", FieldKind.NUMBER)
                            .group("Seal", s -> s.field("Code", FieldKind.STRING))))
            .build().toJson());

    @Test
    void eachStoredCountReadsBackAsTheRecordBothSpellingsBuild() {
        var billing = M.group("/Order/Billing");
        var shipping = M.group("/Order/Shipping");
        var parcels = M.group("/Order/Parcels");
        Map<String, NumberExpr[]> spellings = new LinkedHashMap<>();
        spellings.put("NumberOfFilledGroups over two fixed groups", new NumberExpr[] {
                Dsl.numberOfFilledGroups(billing, shipping),
                Dsl.numberOfFilledGroups(M.flattenGroup("/Order/Billing"), M.flattenGroup("/Order/Shipping"))});
        spellings.put("NumberOfFilledGroups over one repeatable group", new NumberExpr[] {
                Dsl.numberOfFilledGroups(parcels), Dsl.numberOfFilledGroups(M.flattenGroup("/Order/Parcels*"))});
        spellings.put("NumberOfFilledFields over two fixed groups", new NumberExpr[] {
                Dsl.numberOfFilledFields(billing, shipping),
                Dsl.numberOfFilledFields(java.util.List.of(M.flattenGroup("/Order/Billing"),
                        M.flattenGroup("/Order/Shipping")))});
        spellings.put("NumberOfFilledFields over one repeatable group", new NumberExpr[] {
                Dsl.numberOfFilledFields(parcels), Dsl.numberOfFilledFields(M.flattenGroup("/Order/Parcels*"))});
        spellings.put("NumberOfFilledGroups below a starred level", new NumberExpr[] {
                Dsl.numberOfFilledGroups(M.flattenGroup("/Order/Parcels*/Seal"))});

        Map<String, Integer> unequal = new LinkedHashMap<>();
        for (Map.Entry<String, NumberExpr[]> entry : spellings.entrySet()) {
            int misses = 0;
            for (NumberExpr authored : entry.getValue()) {
                if (!readBack(entry.getKey(), authored).equals(authored.expression())) {
                    misses++;
                }
            }
            unequal.put(entry.getKey(), misses);
        }
        Map<String, Integer> none = new LinkedHashMap<>();
        spellings.keySet().forEach(key -> none.put(key, 0));
        assertEquals(none, unequal);
    }

    private static Calculation readBack(String name, NumberExpr count) {
        Condition authored = Dsl.fieldFilled(M.field("/Order/Note")).and(count.gt(0));
        Rule rule = M.rule(M.group("/Order")).name(name.replaceAll("[^A-Za-z]", ""))
                .errorField(M.field("/Order/Note")).when(authored)
                .code("CNT").severity(Severity.ERROR).message(en("count")).build();
        ReadResult result = KernelAdapter.of(M.add(rule)).read("/Order/" + rule.name());
        Condition read = assertInstanceOf(ReadResult.Converted.class, result, result::toString).condition();
        LogicCondition and = assertInstanceOf(LogicCondition.class, read);
        return assertInstanceOf(ComparisonCondition.class, and.right()).left();
    }
}
