package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * F10 read of the {@code $} outer-iteration correlation (D13). A {@code $} reference inside a
 * {@code Having} filter is converted faithfully for the comparison kinds whose handles thread the
 * outer flag (number, string) — render → parse → convert → render round-trips with the {@code $}
 * intact. The N8 safety net is the <b>count-check backstop</b>: if conversion produces fewer
 * {@code $} markers than the source carries (a kind not yet threaded would silently drop one), the
 * rule degrades to {@link ReadResult.Opaque} (preserved verbatim) rather than being silently corrupted —
 * see {@code OuterCorrelationBackstopTest} for the un-threaded-kind case.
 */
class OuterCorrelationReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void numberOuterCorrelationConvertsAndRoundTrips() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        // sum the counts of the items sharing the current row's overall Quantity (outer correlation)
        Condition authored = m.field("/Order/CustomerName").filled().and(
                Dsl.sum(m.flatten("/Order/Items*/Count")
                        .having(m.num("/Order/Quantity").outer().eq(m.num("/Order/Items/Count")))).gt(5));
        String text = RENDER.render(authored);

        Condition converted = converted(KernelAdapter.ofJson(orderWith(text)).read("/Order/OuterRule"));
        assertEquals(text, RENDER.render(converted), "the $ correlation must round-trip faithfully");
        assertTrue(RENDER.render(converted).contains("[$/Order/Quantity]"),
                () -> "the $ marker must survive read: " + RENDER.render(converted));
    }

    @Test
    void stringOuterCorrelationConvertsAndRoundTrips() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        // keep only the items whose Sku equals the current row's outer ProductCode
        Condition authored = m.field("/Order/CustomerName").filled().and(
                Dsl.sum(m.flatten("/Order/Items*/Count")
                        .having(m.str("/Order/Items/Sku").eq(m.str("/Order/ProductCode").outer()))).gt(0));
        String text = RENDER.render(authored);

        Condition converted = converted(KernelAdapter.ofJson(orderWith(text)).read("/Order/OuterRule"));
        assertEquals(text, RENDER.render(converted), "the $ correlation must round-trip faithfully");
        assertTrue(RENDER.render(converted).contains("[$/Order/ProductCode]"),
                () -> "the $ marker must survive read: " + RENDER.render(converted));
    }

    @Test
    void wholeModelReadConvertsTheCorrelatedRule() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));
        Condition authored = m.field("/Order/CustomerName").filled().and(
                Dsl.sum(m.flatten("/Order/Items*/Count")
                        .having(m.num("/Order/Quantity").outer().eq(m.num("/Order/Items/Count")))).gt(5));
        var all = KernelAdapter.ofJson(orderWith(RENDER.render(authored))).readAll();
        assertInstanceOf(ReadResult.Converted.class, all.get("/Order/OuterRule"));
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read (the $ correlation should now convert), got: " + result)
                .condition();
    }

    /** Inject a raw-text rule into the /Order group. */
    private static String orderWith(String rawCondition) {
        String base = read("models/order.dm.json");
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Order/OuterRule\", \"name\": \"OuterRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../CustomerName\", \"errorCode\": \"OUTER\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } },\n";
        int idx = base.indexOf("\"elements\"");
        int br = base.indexOf('[', idx);
        return base.substring(0, br + 1) + "\n" + rule + base.substring(br + 1);
    }

    private static String read(String path) {
        try (InputStream in = OuterCorrelationReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
