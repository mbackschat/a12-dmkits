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
                    .group("Lines", l -> l.repeatable(5).indexField("Sku")
                            .stringField("Sku", f -> { })
                            .enumField("Grade", e -> e.value("OPEN").value("SHIPPED").category("Tier", "High", "Low"))))
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
}
