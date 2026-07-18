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
     * kind-appropriate best-effort value from [strategy] (per-value formal plausibility, not
     * model-acceptance). Deterministic in ([seed], [model], [strategy]).
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

/**
 * The opt-in HOSTILE seeding strategy (the IG56 harness-limitation closer): [ValidValues] draws
 * constraint-RESPECTING values, so the operand-invalidity classes beyond what a model's own rules trip —
 * total-digit overflow, leading zeros, fraction-digit violations — are differential-proven but
 * corpus-invisible. This wrapper makes roughly every fourth NUMBER cell violate ONE of those classes
 * (deterministic in the seed), so the sweep exercises the poison/suppression paths over real corpus rules:
 *
 *  - `LENGTH` — 16 digits (> 15 total, `stelligkeitZuLang`; also over any integer-cap);
 *  - `LEADING_ZERO` — a valid number with a leading `0` (`zahlFuehrendeNull`, unless the field allows them);
 *  - `SCALE` — one MORE fractional digit than `maxFractionalDigits` (`zahlZuvieleNachkommastellen`; a scale-0
 *    integer field gets a decimal);
 *  - `MIN_FRAC` — a WHOLE number into a `minFractionalDigits ≥ 1` field (`zahlFalscheAnzahlNachkommastellen`).
 *
 * Non-number fields (and the other three quarters of number cells) delegate to [ValidValues], so instances
 * stay mostly-valid — the point is scattered invalid cells INSIDE otherwise-live rules, not all-dead documents.
 * Enable on the sweep with `-Dcorpus.hostile=true`.
 */
object HostileValues : ValueStrategy {
    override fun value(field: io.github.mbackschat.a12.dm.interpreter.model.FieldDef, reps: List<Int>, rnd: Random): String? {
        if (field.kind != io.github.mbackschat.a12.dm.interpreter.model.Kind.NUMBER || rnd.nextInt(4) != 0) {
            return ValidValues.value(field, reps, rnd)
        }
        val scale = field.scale ?: 0
        return when (rnd.nextInt(4)) {
            0 -> "9".repeat(16)                                                   // LENGTH: > 15 total digits
            1 -> "0" + (1 + rnd.nextInt(9)) + if (scale > 0) "." + "1".repeat(scale) else ""   // LEADING_ZERO
            2 -> (1 + rnd.nextInt(99)).toString() + "." + "1".repeat(scale + 1)   // SCALE: one frac digit too many
            else -> if ((field.minFractionalDigits ?: 0) >= 1) (1 + rnd.nextInt(99)).toString()  // MIN_FRAC: whole
                else "9".repeat(16)                                               // no min-frac → LENGTH again
        }
    }
}
