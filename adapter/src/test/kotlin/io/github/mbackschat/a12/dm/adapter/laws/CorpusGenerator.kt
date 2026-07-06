package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.CaseJson
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The corpus **generation / re-capture tool** — writes every [CorpusScenarios] case to `corpus/cases/<family>/`,
 * tri-verified by [CorpusCapture] as a side effect (a blocked interpreter split throws, so a bad case is never
 * written). It is the KERNEL-UPGRADE re-capture entry point (CONFORMANCE-CORPUS-SPEC §13): on a kernel bump,
 * re-run it and the committed diff **is** the behavior-change characterization; `meta.kernelVersion` moves with it.
 *
 * Opt-in only (`@Tag("corpus-capture")`, excluded from the default suite) since it mutates the working tree — run
 * with `-PrunCorpusCapture`. The everyday guard is [CorpusReplayTest], which replays the committed files.
 */
@Tag("corpus-capture")
class CorpusGenerator {

    @Test
    fun generateCorpus() {
        val casesDir = CorpusFiles.casesDir()
        var written = 0
        for ((family, case) in CorpusScenarios.all()) {
            val file = casesDir.resolve(family).resolve("${case.meta.id}.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, CaseJson.write(case) + "\n")
            written++
        }
        println("corpus: wrote $written case(s) under $casesDir")
    }
}
