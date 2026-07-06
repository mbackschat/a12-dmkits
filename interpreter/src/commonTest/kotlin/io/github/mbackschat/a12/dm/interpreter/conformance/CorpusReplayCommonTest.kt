package io.github.mbackschat.a12.dm.interpreter.conformance

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The multiplatform conformance-corpus **replay suite** (CONFORMANCE-CORPUS-SPEC §12) — replays every committed
 * case in the `corpus/cases` tree through the kernel-free [InterpreterReplay] on BOTH targets (`:interpreter:check` runs
 * `commonTest` on jvm + js). The **JS run (`jsNodeTest`) is the first kernel-grounded JS oracle**: the JVM
 * capture tri-verified `interpreter == kernel`, so a JS-vs-JVM interpreter split (a decimal-shim / regex / format
 * platform difference) surfaces here as a FAIL — the bug class WI-6 exists to catch.
 *
 * Full conformance over the corpus = **zero FAIL and zero SKIP**; a SKIP on the current corpus is itself a
 * failure (there is nothing the committed cases legitimately can't evaluate). The corpus is located via the
 * `CORPUS_DIR` env var Gradle sets on the test tasks ([readCorpusFiles]); those tasks declare the corpus files as
 * inputs, so a case edit re-runs this suite and a no-change run stays cached.
 */
class CorpusReplayCommonTest {

    @Test
    fun everyCommittedCaseReplaysOnTheInterpreter() {
        val files = readCorpusFiles()
        assertTrue(
            files.size >= MIN_CASES,
            "expected >= $MIN_CASES committed conformance cases, found ${files.size} — is CORPUS_DIR set for this test task?",
        )
        val failures = files.mapNotNull { f ->
            val case = CaseJson.parse(f.text)
            val result = InterpreterReplay.replay(case, readCorpusModel(case.modelRef))
            if (result.verdict == InterpreterReplay.Verdict.PASS) null
            else "${f.family}/${f.id}: ${result.verdict} — ${result.detail}"
        }
        assertTrue(
            failures.isEmpty(),
            "interpreter corpus replay: ${failures.size} non-PASS (a SKIP counts as a failure on this corpus):\n  " +
                failures.joinToString("\n  "),
        )
    }

    private companion object {
        const val MIN_CASES = 6
    }
}
