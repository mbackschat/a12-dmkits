package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Drill aid — the sweep only TALLIES an `interp-eval:<Exception>` skip class; this probe finds the first
 *  crashing model+seed and prints OUR stack frames (the interpreter's own code — license-safe; no corpus
 *  content), so a crash class is drillable without instrumenting the sweep. Opt-in `corpus-sweep`
 *  (`-Da12.corpus1.dir`). Found IF102's single-arg-Round crash. */
@Tag("corpus-sweep")
class CrashProbeTest {

    @Test
    fun findFirstInterpEvalCrash() {
        val dir = System.getProperty("a12.corpus1.dir")
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "a12.corpus1.dir not set — skipping")
        val root = Path.of(dir)
        val resolver = ReferenceResolvers.directory(root)
        var n = 0
        for (file in CorpusShrinkSupport.documentModels(root)) {
            val kernelJson = runCatching { CorpusModelPreparer.prepareKernelJson(Files.readString(file), resolver) }.getOrNull() ?: continue
            val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(kernelJson) }.getOrNull() ?: continue
            val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: continue
            n++
            for (seed in 0 until 5) {
                val placements = runCatching { CorpusSeeding.seedPlacements(model, seed.toLong(), 2, ValidValues) }.getOrNull() ?: continue
                try {
                    InterpreterReplay.run(interpJson, placements, Op.ValidateFull, "2026-07-07T12:00:00")
                } catch (t: Throwable) {
                    println("[crash] model #$n seed=$seed → ${t::class.simpleName}: ${t.message?.replace(Regex("/[A-Za-z0-9_/]+"), "<path>")}")
                    t.stackTrace.take(12).forEach { println("[crash]   at $it") }
                    return
                }
            }
        }
        println("[crash] no interp-eval crash in $n models")
    }
}
