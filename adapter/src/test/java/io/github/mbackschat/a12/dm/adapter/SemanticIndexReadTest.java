package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F10 read of a <b>semantic index</b> {@code [field For X]} — the field's value in the repetition whose
 * group index field ({@code Sku}) equals {@code X}. The kernel models exactly two key forms, both
 * single-element: a <em>constant</em> key ({@code String}) and a <em>field</em> key ({@code IEntityId},
 * the current value of another field, DEV-API-GAPS DG8). The converter modeled only the constant form on
 * a number field; the field-keyed form (author-supported via {@code forIndex(FieldRef)}, kernel-valid per
 * {@code adapter.laws.SemanticIndexFieldKeyedDiffTest}) read back Opaque. Both now round-trip.
 *
 * <p>Own domain: an order whose {@code Items} rows are index-keyed by {@code Sku}, reading {@code Count}
 * in the row whose {@code Sku} equals the current {@code CustomerName}.
 */
class SemanticIndexReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void roundTripsAFieldKeyedSemanticIndex() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        Condition c = m.field("/Order/CustomerName").filled()
                .and(m.num("/Order/Items/Count").forIndex(m.field("/Order/CustomerName")).gt(0));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FieldKeyedIdx", "/Order/CustomerName", c)));
        assertTrue(RENDER.render(c).contains("[/Order/Items/Count For /Order/CustomerName]"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/FieldKeyedIdx"))));
    }

    @Test
    void stillReadsAConstantKeyedSemanticIndex() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        Condition c = m.field("/Order/CustomerName").filled()
                .and(m.num("/Order/Items/Count").forIndex("SKU-1").gt(0));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "ConstIdx", "/Order/CustomerName", c)));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.read("/Order/ConstIdx"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Order")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = SemanticIndexReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
