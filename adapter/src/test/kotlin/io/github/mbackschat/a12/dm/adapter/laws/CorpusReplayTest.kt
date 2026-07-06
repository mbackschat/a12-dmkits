package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The conformance corpus **replay guard** (CONFORMANCE-CORPUS-SPEC §12) — replays every committed case in the
 * `corpus/cases` tree through all three JVM engines ([CorpusReplay]) and asserts each PASSes. This is the
 * standing guard the corpus exists to be:
 *
 * - the **interpreter** leg is the conformance claim (zero FAIL, zero SKIP = full conformance over the corpus);
 * - the **groovy-dynamic** leg re-runs the real kernel, so a kernel change that alters a committed expectation
 *   fails here (the KERNEL-UPGRADE re-capture signal);
 * - the **java-static** leg asserts the codegen strategy matches the anchor (or its recorded divergence).
 *
 * Runs in the dedicated `corpusReplayTest` Gradle task with the corpus files as declared inputs (no false green).
 */
class CorpusReplayTest {

    @Test
    fun corpusIsPopulated() {
        assertTrue(CorpusFiles.all().size >= MIN_CASES) {
            "expected at least $MIN_CASES committed cases under ${CorpusFiles.casesDir()} " +
                "(found ${CorpusFiles.all().size}); regenerate with -PrunCorpusCapture"
        }
    }

    @TestFactory
    fun everyCaseReplaysThreeWays(): List<DynamicTest> = CorpusFiles.all().map { cf ->
        DynamicTest.dynamicTest("${cf.path.parent.fileName}/${cf.case.meta.id}") {
            val results = CorpusReplay.replay(cf.case)
            val bad = results.filterValues { it.verdict != CorpusReplay.Verdict.PASS }
            assertTrue(bad.isEmpty()) {
                "conformance verdict != PASS for ${cf.path}:\n" +
                    bad.entries.joinToString("\n") { "  ${it.key}: ${it.value.verdict} — ${it.value.detail}" }
            }
        }
    }

    private companion object {
        const val MIN_CASES = 6
    }
}
