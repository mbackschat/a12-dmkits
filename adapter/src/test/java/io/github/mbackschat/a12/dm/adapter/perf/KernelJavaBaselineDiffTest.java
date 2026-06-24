package io.github.mbackschat.a12.dm.adapter.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws;
import org.junit.jupiter.api.Test;

/**
 * Correctness guard for the <b>Java kernel baseline</b> (INTERPRETER-PERFORMANCE.md "the kernel baseline:
 * Groovy vs Java"): the kernel's OWN generated validator compiled as Java ({@link RuntimeLaws#kernelJavaEngine})
 * must fire <em>identically</em> to the Groovy dynamic path ({@link RuntimeLaws#kernelEngine}) on the same
 * document — otherwise the Java timing measures a different validator and the Groovy-vs-Java comparison is a
 * fiction. This pins the generate-Java → in-memory-compile → static-rt-service mechanism independently of the
 * perf gate (which also asserts three-way agreement, but at a single CI scale).
 */
class KernelJavaBaselineDiffTest {

    private static final String BASE = RuntimeLaws.model("models/perf.dm.json");

    @Test
    void perRowRule_javaFiresLikeGroovy() {
        // i*10 > 50 for i in 6..10 → exactly 5 firings; assert that AND Groovy==Java.
        assertSameFirings(RuntimeLaws.withRule(BASE, "/Perf/Lines", "R_HQ", "../Qty", "HQ",
                "FieldFilled(Qty) And [Qty] > 50"), 5, "HQ");
    }

    @Test
    void starAggregate_javaFiresLikeGroovy() {
        // Sum(Amount=1 over 10 rows) = 10 > 0 → TP fires once at /Perf.
        assertSameFirings(RuntimeLaws.withRule(BASE, "/Perf", "R_TP", "../Lines/Amount", "TP",
                "Sum(Lines*/Amount) > 0"), 1, "TP");
    }

    private void assertSameFirings(String model, int expectedCount, String code) {
        RuntimeLaws.KernelEngine groovy = RuntimeLaws.kernelEngine(model);
        RuntimeLaws.JavaKernelEngine java = RuntimeLaws.kernelJavaEngine(model);
        RuntimeLaws.Doc doc = RuntimeLaws.doc();
        for (int i = 1; i <= 10; i++) {
            doc.group("/Perf/Lines", 1, i);
            doc.field("/Perf/Lines/LineKey", "K" + i, 1, i, 1);
            doc.field("/Perf/Lines/Qty", String.valueOf(i * 10), 1, i, 1);
            doc.field("/Perf/Lines/Amount", "1", 1, i, 1);
        }
        var groovyCodes = groovy.firedCodesSorted(groovy.instance(doc));
        var javaCodes = java.firedCodesSorted(java.instance(doc));
        assertEquals(expectedCount, groovyCodes.stream().filter(code::equals).count(),
                "fixture sanity: " + code + " should fire " + expectedCount + "×");
        assertTrue(groovyCodes.contains(code), "fixture sanity: Groovy fired " + code);
        assertEquals(groovyCodes, javaCodes, "Java-generated kernel validator must fire identically to Groovy");
    }
}
