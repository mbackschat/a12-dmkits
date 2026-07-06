package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Doc;
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Msg;
import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Probe + baseline for the WI-1 tri-differential's <b>partial-validation</b> leg: does the kernel's static-JAVA
 * codegen path support {@code validatePart} at all, and does it gate identically to the Groovy-dynamic path?
 * ({@link RuntimeLaws.JavaKernelEngine#messagesPart} converts to {@link com.mgmtp.a12.kernel.md.document.apiV2
 * .immutable.DocumentV2} and calls {@code validatePart} on the static rt-service — the modern-path requirement.)
 *
 * <p>If this stays green, {@code FuzzDiagnostics.partialSignaturesAgree} runs the java-static engine as a third
 * partial-validation engine; if the static path could not gate partial validation, this test would surface it
 * and the partial leg would be scoped out (validateFull + compute only). It is the {@code validatePart} twin of
 * {@link io.github.mbackschat.a12.dm.adapter.perf.KernelJavaBaselineDiffTest}.
 */
class JavaStaticPartialBaselineDiffTest {

    private static final String BASE = RuntimeLaws.model("models/perf.dm.json");
    // A per-row rule on /Perf/Lines: fires (VALUE_ERROR) when Qty > 50 — i*10 for i in 6..10 → rows 6..10.
    private static final String MODEL = RuntimeLaws.withRule(BASE, "/Perf/Lines", "R_HQ", "../Qty", "HQ",
            "FieldFilled(Qty) And [Qty] > 50");

    private static Doc tenRows() {
        Doc doc = RuntimeLaws.doc();
        for (int i = 1; i <= 10; i++) {
            doc.group("/Perf/Lines", 1, i);
            doc.field("/Perf/Lines/LineKey", "K" + i, 1, i, 1);
            doc.field("/Perf/Lines/Qty", String.valueOf(i * 10), 1, i, 1);
            doc.field("/Perf/Lines/Amount", "1", 1, i, 1);
        }
        return doc;
    }

    private static List<String> sig(List<Msg> msgs) {
        return msgs.stream().map(m -> m.code() + "|" + m.type().name() + "|" + m.field()).sorted().toList();
    }

    @Test
    void staticPartialGatesLikeDynamic_allRelevant() {
        // Whole /Perf relevant (wildcard) → every Qty>50 row fires, on both codegen strategies.
        Set<Relevant> relevant = Set.of(Relevant.of("/Perf", 0));
        var dynamic = sig(RuntimeLaws.messagesPart(MODEL, tenRows(), relevant));
        var staticJava = sig(RuntimeLaws.kernelJavaEngine(MODEL).messagesPart(tenRows(), relevant));
        assertFalse(dynamic.isEmpty(), "fixture sanity: the dynamic path must fire HQ on the high-Qty rows");
        assertEquals(dynamic, staticJava,
                "static-JAVA validatePart must gate identically to the Groovy-dynamic validatePart");
    }

    @Test
    void staticPartialGatesLikeDynamic_subsetRelevant() {
        // Only row 8's error field relevant → the rule runs on that row only; both strategies must agree.
        Set<Relevant> relevant = Set.of(Relevant.of("/Perf/Lines/Qty", 1, 8, 1));
        var dynamic = sig(RuntimeLaws.messagesPart(MODEL, tenRows(), relevant));
        var staticJava = sig(RuntimeLaws.kernelJavaEngine(MODEL).messagesPart(tenRows(), relevant));
        assertEquals(dynamic, staticJava,
                "static-JAVA validatePart must gate the relevant subset identically to Groovy-dynamic");
    }
}
