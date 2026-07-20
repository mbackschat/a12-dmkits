package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/** Typed read-back symmetry for the three authorable constructs that previously degraded or lost metadata. */
class IndexedConstructReadBackTest {

    private static final DslRenderer RENDER = new DslRenderer();

    private static BoundModel model() {
        String json = DocumentModelDraft.named("stock").locale("en_US")
                .rootGroup("Stock", stock -> stock
                        .stringField("Notice", field -> { })
                        .numberField("Base", field -> { })
                        .numberField("Total", field -> { })
                        .group("Lines", lines -> lines
                                .repeatable(20).indexField("Sku")
                                .stringField("Sku", field -> { })
                                .numberField("Quantity", field -> { })))
                .build().toJson();
        return BoundModel.ofJson(json);
    }

    @Test
    void readsIndexedPresenceWithoutDroppingTheIndex() {
        BoundModel model = model();
        Condition condition = model.str("/Stock/Notice").filled()
                .and(model.num("/Stock/Lines/Quantity").semanticIndex("A-17").filled());
        KernelAdapter adapter = KernelAdapter.of(model.add(rule(model, "IndexedPresence", condition)));

        Condition read = assertInstanceOf(ReadResult.Converted.class,
                adapter.read("/Stock/IndexedPresence")).condition();

        assertEquals(RENDER.render(condition), RENDER.render(read));
    }

    @Test
    void readsIndexedFieldValueAsStringWithoutDroppingTheIndex() {
        BoundModel model = model();
        Condition condition = model.str("/Stock/Notice")
                .eq(Dsl.fieldValueAsString(model.num("/Stock/Lines/Quantity").semanticIndex("A-17")));
        KernelAdapter adapter = KernelAdapter.of(model.add(rule(model, "IndexedString", condition)));

        Condition read = assertInstanceOf(ReadResult.Converted.class,
                adapter.read("/Stock/IndexedString")).condition();

        assertEquals(RENDER.render(condition), RENDER.render(read));
    }

    @Test
    void readsComputationSuppressionsFromTheSourceComputation() {
        BoundModel model = model();
        Computation computation = model.computation(model.num("/Stock/Total"))
                .name("TotalComputation")
                .calc(model.num("/Stock/Base").value())
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
                .message(en("Total must match the selected quantity."))
                .build();

        ReadComputation result = KernelAdapter.of(model.add(computation))
                .readComputation("/Stock/TotalComputation");
        Computation read = assertInstanceOf(ReadComputation.Converted.class, result).computation();

        assertEquals(computation.errorCodesToSuppress(), read.errorCodesToSuppress());
        assertEquals(computation.draft(), read.draft());
    }

    private static Rule rule(BoundModel model, String name, Condition condition) {
        return model.rule(model.group("/Stock"))
                .name(name)
                .errorField(model.field("/Stock/Notice"))
                .when(condition)
                .code("READ")
                .severity(Severity.ERROR)
                .message(en("Read-back failed."))
                .build();
    }
}
