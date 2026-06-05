package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Adapter bootstrap canary (Phase 2, proposal §16). Proves the full a12internal read chain wires up
 * against the pinned kernel — and doubles as the N8 <b>canary</b> that fails loudly if a kernel
 * upgrade breaks the (unstable) internal surface. It authors a rule with rulekit, writes it into the
 * model (F6), then reads it back through the kernel adapter: parse+format round-trip, exact
 * referenced-entities, and the per-rule validity fast lane.
 */
class KernelAdapterBootstrapTest {

    private static final String RULE_PATH = "/Subscription/Addons/FeeCapRule";

    /** A model containing one rulekit-authored rule — the foreign-rule the adapter then reads. */
    private static String modelWithRule() {
        BoundModel m = BoundModel.ofJson(readResource("models/subscription.dm.json"));
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee too high."), de("Monatsgebühr zu hoch."))
                .build();
        return m.add(rule).toJson();
    }

    @Test
    void roundTripsAForeignRuleThroughTheKernelAdapter() {
        KernelAdapter adapter = KernelAdapter.ofJson(modelWithRule());

        // (1) per-rule validity — the kernel accepts what rulekit rendered (fast lane, no whole-model check)
        assertTrue(adapter.hasValidCondition(RULE_PATH), "the kernel should accept the authored condition");

        // (2) parse + format round-trip — the kernel re-emits the condition it parsed
        String canonical = adapter.canonicalConditionText(RULE_PATH);
        assertNotNull(canonical);
        assertFalse(canonical.isBlank(), "the kernel should re-emit a non-empty condition text");
        assertTrue(canonical.contains("MonthlyFee"),
                () -> "the reformatted text should reference the field, got: " + canonical);

        // (3) exact referenced-entities — the kernel's own answer (F9a whereUsed, no converter needed)
        Set<String> refs = adapter.referencedFields(RULE_PATH);
        assertTrue(refs.stream().anyMatch(p -> p.endsWith("MonthlyFee")),
                () -> "referenced fields should include MonthlyFee, got: " + refs);
    }

    private static String readResource(String path) {
        try (InputStream in = KernelAdapterBootstrapTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, () -> "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
