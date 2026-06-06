package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * F10 tolerance — the {@code $} outer-iteration correlation (D13, unmodeled) must ingest as
 * {@link Ingest.Opaque}, <em>never</em> silently converted. The converter has field-vs-field
 * comparison (it round-trips), but no AST node for the {@code $} rule-iteration marker — so without a
 * guard it would emit a non-correlated reference ({@code [$/Order/Quantity]} → {@code [/Order/Quantity]}),
 * a silent meaning change that violates N8 ("never silently corrupt"). And the Opaque must preserve the
 * <b>raw stored text</b>: the kernel's canonical formatter truncates a {@code $} condition
 * (KERNEL-FINDINGS), so canonical preservation would <em>lose</em> the rule on write-back.
 *
 * <p>Own-domain fixture: an order line whose item count is correlated (per outer iteration) with the
 * order's overall quantity. The rule is injected as raw condition text (this exercises the <em>ingest</em>
 * direction — reading a foreign rule back into the AST — which is the deferred half; the <em>authoring</em>
 * direction now works, see {@code OuterCorrelationAuthoringTest}).
 */
class OuterCorrelationIngestTest {

    /** A kernel-valid rule using the {@code $} outer correlation inside a Having filter. */
    private static final String OUTER_CONDITION =
            "FieldFilled(CustomerName) And Sum(Items*/Count Having [$/Order/Quantity] == [Items/Count]) > 5";

    @Test
    void outerCorrelationIngestsAsOpaqueAndIsNotSilentlyConverted() {
        KernelAdapter adapter = KernelAdapter.ofJson(orderWith(OUTER_CONDITION));

        // the rule is well-formed for the kernel — this is a converter gap, not an invalid rule
        assertTrue(adapter.hasValidCondition("/Order/OuterRule"),
                "the $ rule should be kernel-valid (the gap is in rulekit's converter, not the rule)");

        Ingest result = adapter.ingest("/Order/OuterRule");
        Ingest.Opaque opaque = assertInstanceOf(Ingest.Opaque.class, result,
                () -> "a $ correlation must degrade to Opaque, never be silently converted, got: " + result);
        assertTrue(opaque.reason().toLowerCase().contains("$")
                        || opaque.reason().toLowerCase().contains("outer"),
                () -> "the reason should name the $ / outer-iteration correlation: " + opaque.reason());
    }

    @Test
    void opaquePreservesTheRawTextVerbatimIncludingTheDollarMarker() {
        KernelAdapter adapter = KernelAdapter.ofJson(orderWith(OUTER_CONDITION));
        Ingest.Opaque opaque = assertInstanceOf(Ingest.Opaque.class, adapter.ingest("/Order/OuterRule"));

        String preserved = opaque.canonicalText();
        assertFalse(preserved.isBlank(), "the rule must be preserved, not lost");
        // the $ marker survives verbatim (silent corruption would have dropped it)
        assertTrue(preserved.contains("$"),
                () -> "the $ correlation marker must be preserved verbatim: " + preserved);
        // the whole rule survives — the kernel canonical form truncates a $ rule to FieldFilled(...) alone
        assertTrue(preserved.contains("Sum") && preserved.contains("Having"),
                () -> "the full rule must be preserved, not the kernel's truncated canonical form: " + preserved);
    }

    @Test
    void wholeModelIngestStaysTolerant() {
        // ingestAll must not throw on a model carrying a $ rule — it degrades that one rule to Opaque.
        var all = KernelAdapter.ofJson(orderWith(OUTER_CONDITION)).ingestAll();
        assertInstanceOf(Ingest.Opaque.class, all.get("/Order/OuterRule"));
    }

    /** Inject a raw-text rule into the /Order group (we're testing the ingest/read path, not authoring). */
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
        try (InputStream in = OuterCorrelationIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
