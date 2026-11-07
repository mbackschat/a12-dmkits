package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws;
import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * A rule that reads a sibling root group through the model root — legal since kernel 31.3.1 (KERNEL-FINDINGS KF679) —
 * reads back on both backends as the same typed condition its absolute spelling gives, so a typed reader sees one
 * element whichever way the author wrote the walk. The absolute twin is the control.
 *
 * <p>Own domain (an e-commerce order and its customer).
 */
class CrossRootGroupConditionReadTest {

    private static final String SHOP = DocumentModelDraft.named("shop").locale("en_US").locale("de_DE")
            .rootGroup("Order", o -> o.stringField("Code", f -> { }))
            .rootGroup("Customer", c -> c.stringField("Name", f -> { }))
            .build().toJson();

    @Test
    void aWalkThroughTheModelRootReadsAsTheElementItNames() {
        BoundModel m = BoundModel.ofJson(SHOP);
        Condition expected = m.str("/Order/Code").filled().and(m.str("/Customer/Name").filled());
        String relative = RuntimeLaws.withRule(SHOP, "/Order", "Relative", "../Code", "RELATIVE",
                "FieldFilled(Code) And FieldFilled(../Customer/Name)");
        String json = RuntimeLaws.withRule(relative, "/Order", "Absolute", "../Code", "ABSOLUTE",
                "FieldFilled(Code) And FieldFilled(/Customer/Name)");

        for (ConditionReadBack backend : List.of(new KernelConditionReadBack(), new InterpreterConditionReadBack())) {
            Map<String, ReadResult> read = backend.readAll(json, ReferenceResolvers.selfContained());
            for (String rule : List.of("/Order/Relative", "/Order/Absolute")) {
                var converted = assertInstanceOf(ReadResult.Converted.class, read.get(rule),
                        () -> backend + " " + rule + ": " + read.get(rule));
                assertEquals(expected, converted.condition(), () -> backend + " " + rule);
            }
        }
    }
}
