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
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Adapter bootstrap canary (Phase 2, PROJECT-DESIGN §16). Proves the full a12internal read chain wires up
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

        // (4) exact whereUsed — the inverse: the field's rule shows up; a non-referenced entity is empty
        String feePath = refs.stream().filter(p -> p.endsWith("MonthlyFee")).findFirst().orElseThrow();
        assertTrue(adapter.whereUsed(feePath).contains(RULE_PATH),
                () -> "whereUsed(" + feePath + ") should include " + RULE_PATH + ", got: " + adapter.whereUsed(feePath));
        assertTrue(adapter.whereUsed("/Subscription/NoSuchField").isEmpty(),
                "whereUsed of an unreferenced entity should be empty");

        // (5) IProblem → Diagnostic: the stored condition is valid (no error diagnostics)…
        assertTrue(adapter.diagnose(RULE_PATH).ok(),
                () -> "stored condition is valid: " + adapter.diagnose(RULE_PATH).diagnostics());
        // …and a bad CANDIDATE condition's kernel problems are surfaced as structured Diagnostics (no mutation)
        ValidationResult bad = adapter.diagnose(RULE_PATH, "[MonthlyFee] PatternMatched \"abc\"");
        assertFalse(bad.ok(), () -> "a type-illegal candidate must produce diagnostics, got: " + bad.diagnostics());
        assertFalse(bad.diagnostics().isEmpty(), "the kernel problem must map to at least one Diagnostic");
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
