package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Drill aid — replay a dumped shrink minimal (`-Datom.dump` / `-Dig44.dump`) + its `.placements` file through
 *  BOTH engines and print the full per-pointer outcome lists, so a divergence's exact values are visible without
 *  re-hunting. Opt-in (`-PrunCorpusSweep`, tag `corpus-sweep`) via `-Dreplay.model=<scratch-file>` — the minimal
 *  lives only in the scratchpad (license boundary: reads in place, prints to the console, persists nothing). */
@Tag("corpus-sweep")
class MinimalReplayTest {

    @Test
    fun replay() {
        val modelPath = System.getProperty("replay.model")
        assumeTrue(modelPath != null && Files.exists(Path.of(modelPath)), "replay.model not set — skipping")
        val kernelJson = Files.readString(Path.of(modelPath))
        val placements = Files.readAllLines(Path.of("$modelPath.placements")).filter { it.isNotBlank() }.map { l ->
            val (kind, path, reps, value) = l.split("|", limit = 4)
            Placement(PlacementKind.valueOf(kind), path, reps.split(",").map { it.toInt() }, value.ifEmpty { null })
        }
        val interpJson = CorpusModelPreparer.toInterpreterJson(kernelJson)
        val clock = "2026-07-07T12:00:00"
        val k = CorpusEngines.groovyDynamic(kernelJson, placements, Op.Compute, clock,
            CorpusShrinkSupport.modelLocale(kernelJson))
        val i = CorpusEngines.interpret(interpJson, placements, Op.Compute, clock)
        println("[replay] KERNEL compute outcomes:"); k.forEach { println("  K| $it") }
        println("[replay] INTERP compute outcomes:"); i.forEach { println("  I| $it") }
    }
}
