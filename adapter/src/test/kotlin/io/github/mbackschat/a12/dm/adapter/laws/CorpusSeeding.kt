package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink
import io.github.mbackschat.a12.dm.interpreter.gen.SeedGenerator
import io.github.mbackschat.a12.dm.interpreter.gen.ValidValues
import io.github.mbackschat.a12.dm.interpreter.gen.ValueStrategy
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.random.Random

/**
 * Seeds a [ModelDef] into a corpus [Placement] list — the bridge between [SeedGenerator] (which writes to a
 * [PlacementSink]) and the tri-engine seam ([CorpusEngines], which replays a `List<Placement>`). Shared by the
 * real-corpus sweep ([RealCorpusSweepTest]) and the pre-expansion fixture guard ([CorpusModelPreparerTest]).
 *
 * The same name-path placement list drives BOTH the interpreter model (normalized name-path ids) and the kernel
 * model (opaque ids) — both address instances by name-path — so [CorpusModelPreparer]'s two outputs stay
 * comparable.
 */
object CorpusSeeding {

    /**
     * Generate one deterministic instance of [model] for [seed]: each repeatable group gets a seed-varied row
     * count in `0..[maxRows]` (0 exercises an empty repeatable group; >1 exercises iteration), every field a
     * valid-by-construction value from [strategy]. Deterministic in ([seed], [model], [strategy]).
     */
    fun seedPlacements(model: ModelDef, seed: Long, maxRows: Int = 2, strategy: ValueStrategy = ValidValues): List<Placement> {
        val out = mutableListOf<Placement>()
        SeedGenerator(model).populate(recordInto(out), seed, { _, _, rnd -> rnd.nextInt(0, maxRows + 1) }, strategy)
        return out
    }

    /** Java-friendly fixed-row seeding: every repeatable group gets [rows] rows, every field a valid value. */
    fun seedPlacementsFixed(model: ModelDef, seed: Long, rows: Int): List<Placement> {
        val out = mutableListOf<Placement>()
        SeedGenerator(model).populate(recordInto(out), seed, { _, _, _ -> rows }, ValidValues)
        return out
    }

    private fun recordInto(out: MutableList<Placement>): PlacementSink = object : PlacementSink {
        override fun group(path: String, vararg reps: Int) { out += Placement(PlacementKind.GROUP, path, reps.toList()) }
        override fun field(path: String, value: String, vararg reps: Int) { out += Placement(PlacementKind.FIELD, path, reps.toList(), value) }
        override fun emptyField(path: String, vararg reps: Int) { out += Placement(PlacementKind.EMPTY, path, reps.toList()) }
    }

    /** A deterministic RNG for a scenario ([seed]) — used by sweep strategies that need their own randomness. */
    fun rng(seed: Long): Random = Random(seed)
}
