package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * A category read of a key-selected row reads back as the keyed category it was authored as, on both read-back
 * backends (KERNEL-FINDINGS KF568; DEV-API-GAPS DG137). The kernel converter took the key branch first and
 * dropped the category, and the interpreter bridge had no shape for a category beside a key.
 *
 * <p>The unkeyed category is the control that the key is read from the model rather than added.
 */
class KeyedCategoryReadTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("order-keyed-category-read")
            .locale("en_US")
            .rootGroup("Order", o -> o
                    .numberField("Total", n -> n.fractionDigits(0))
                    .stringField("Pick", f -> { })
                    .enumField("Status", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))
                    .enumField("Level", e -> e.value("L1").value("L2").category("Rank", "1", "2"))
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .enumField("Grade", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))
                            .enumField("Band", e -> e.value("B1").value("B2").category("Rank", "1", "2"))
                            .enumField("Qty", e -> e.value("1").value("2"))
                            .numberField("Units", n -> n.fractionDigits(0))))
            .build().toJson());
    private static final DslRenderer RENDER = new DslRenderer();
    private static final NumberRef TOTAL = M.num("/Order/Total");
    private static final CategoryRef TIER = M.enumOf("/Order/Lines/Grade").category("Tier");

    private static Condition guarded(Condition condition) {
        return TOTAL.filled().and(condition);
    }

    @Test
    void aKeyedCategoryReadsBackKeyedOnBothBackends() {
        Map<String, Condition> authored = new java.util.LinkedHashMap<>();
        authored.put("LiteralKey", guarded(TIER.forIndex("a").is("High")));
        authored.put("FieldKey", guarded(TIER.forIndex(M.field("/Order/Pick")).is("High")));
        authored.put("FirstFilled", guarded(Dsl.firstFilledString(TIER.forIndex("a").firstFilledOperand(),
                M.enumOf("/Order/Status").category("Tier").firstFilledOperand()).eq("High")));
        authored.put("Unkeyed", guarded(M.enumOf("/Order/Status").category("Tier").is("High")));
        BoundModel model = M;
        for (var entry : authored.entrySet()) {
            model = model.add(M.rule(M.group("/Order")).name(entry.getKey()).errorField(TOTAL.field())
                    .when(entry.getValue()).code(entry.getKey().toUpperCase()).message(Dsl.en("Check the tier."))
                    .build());
        }
        String json = model.toJson();

        for (ConditionReadBack backend : List.of(new KernelConditionReadBack(), new InterpreterConditionReadBack())) {
            Map<String, ReadResult> read = backend.readAll(json, ReferenceResolvers.selfContained());
            authored.forEach((name, condition) -> {
                var converted = assertInstanceOf(ReadResult.Converted.class, read.get("/Order/" + name),
                        () -> backend + " " + name + ": " + read.get("/Order/" + name));
                // A text-typed first-filled comparison reads its literal back as an enum value, keyed or not, so that
                // row compares as rendered, as FirstFilledValueReadTest does; the others compare whole.
                if (name.equals("FirstFilled")) {
                    assertEquals(RENDER.render(condition), RENDER.render(converted.condition()), () -> backend + " " + name);
                } else {
                    assertEquals(condition, converted.condition(), () -> backend + " " + name);
                }
            });
        }
    }

    /**
     * The roles beyond comparison and first-filled: a value-list member, a concatenation and FieldValueAsNumber each
     * read the keyed row too (KF568), so each reads back keyed on both backends. The bridge had no category arm in
     * FieldValueAsNumber or a value list at all, so the unkeyed rows are cases of their own (2026-10-25 cold review, R10).
     */
    @Test
    void everyKeyedRoleReadsBackKeyedOnBothBackends() {
        Map<String, Condition> authored = new java.util.LinkedHashMap<>();
        authored.put("ValueList", guarded(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("High"), TIER.forIndex("a"))));
        authored.put("ValueListMixed", guarded(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("High", "OPEN"),
                M.enumOf("/Order/Status").category("Tier"), M.enumOf("/Order/Status"))));
        authored.put("Concatenation", guarded(M.str("/Order/Pick").asString().eq(TIER.forIndex("a").plus("-x"))));
        authored.put("AsNumber", guarded(Dsl.fieldValueAsNumber(M.enumOf("/Order/Lines/Band").category("Rank").forIndex("a"))
                .gt(1)));
        authored.put("AsNumberUnkeyed", guarded(Dsl.fieldValueAsNumber(M.enumOf("/Order/Level").category("Rank")).gt(1)));
        assertReadsBackOnBothBackends(authored);
    }

    /**
     * A keyed FIELD in either coercion reads back keyed too: A12 admits {@code FieldValueAsNumber(Lines/Qty For "a")}
     * (KF568), which had no typed form, and the interpreter bridge read an indexed
     * {@code FieldValueAsString} operand as no shape at all. The unkeyed rows are the controls.
     */
    @Test
    void aKeyedFieldInACoercionReadsBackKeyedOnBothBackends() {
        // tag::indexed-number-coercion[]
        // fieldValueAsNumber takes the same semanticIndex(key) handle: the keyed cell's stored value as a
        // number, rendered bare as A12 requires inside a function (KERNEL-FINDINGS KF568).
        NumberExpr keyedQuantity = Dsl.fieldValueAsNumber(M.field("/Order/Lines/Qty").semanticIndex("a"));
        // end::indexed-number-coercion[]
        Map<String, Condition> authored = new java.util.LinkedHashMap<>();
        authored.put("AsNumberKeyed", guarded(keyedQuantity.gt(1)));
        authored.put("AsNumberFieldKeyed", guarded(Dsl.fieldValueAsNumber(
                M.field("/Order/Lines/Qty").semanticIndex(M.field("/Order/Pick"))).gt(1)));
        authored.put("AsStringKeyed", guarded(Dsl.fieldValueAsString(M.field("/Order/Lines/Units").semanticIndex("a"))
                .eq(Dsl.text("2"))));
        authored.put("AsStringUnkeyed", guarded(Dsl.fieldValueAsString(M.field("/Order/Total")).eq(Dsl.text("2"))));
        assertReadsBackOnBothBackends(authored);
    }

    private static void assertReadsBackOnBothBackends(Map<String, Condition> authored) {
        BoundModel model = M;
        for (var entry : authored.entrySet()) {
            model = model.add(M.rule(M.group("/Order")).name(entry.getKey()).errorField(TOTAL.field())
                    .when(entry.getValue()).code(entry.getKey().toUpperCase()).message(Dsl.en("Check the tier."))
                    .build());
        }
        String json = model.toJson();
        for (ConditionReadBack backend : List.of(new KernelConditionReadBack(), new InterpreterConditionReadBack())) {
            Map<String, ReadResult> read = backend.readAll(json, ReferenceResolvers.selfContained());
            authored.forEach((name, condition) -> {
                var converted = assertInstanceOf(ReadResult.Converted.class, read.get("/Order/" + name),
                        () -> backend + " " + name + ": " + read.get("/Order/" + name));
                assertEquals(RENDER.render(condition), RENDER.render(converted.condition()), () -> backend + " " + name);
            });
        }
    }
}
