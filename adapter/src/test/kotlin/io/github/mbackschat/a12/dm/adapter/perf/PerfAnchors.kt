package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * The runner class an observation belongs to. Absolute timings are only comparable **within** one of these: the
 * same 17 gate observations drift `0.91×–2.45×` between Apple Silicon and a hosted x64 runner, and normalizing by
 * another interpreter scenario does not reduce that (INTERPRETER-PERFORMANCE.md "the blind-band audit"). A hosted
 * runner is a different class from a local machine of the same os/arch, so `CI` is part of the key.
 */
internal object PerfEnvironment {

    val key: String by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase().let {
            when {
                it.startsWith("mac") || it.startsWith("darwin") -> "darwin"
                it.startsWith("windows") -> "windows"
                else -> it.substringBefore(' ').ifBlank { "unknown" }
            }
        }
        val arch = System.getProperty("os.arch").orEmpty().ifBlank { "unknown" }
        val hosted = if (System.getenv("CI").isNullOrBlank()) "" else "-ci"
        "$os-$arch$hosted"
    }

    /**
     * A cheap machine discriminator INSIDE a runner class. [key] names an os/arch family, which is what an artifact
     * and a committed block are addressed by — but absolute timings are a property of the MACHINE, and two Apple
     * Silicon generations differ materially at the same `darwin-aarch64` key. A block records this alongside its
     * numbers and [PerfAnchors] declines to assert against a block captured on a different one, so a second machine
     * reads as bootstrap instead of reddening 18 lanes. It **narrows** the class rather than closing it: two machines
     * with the same core count and different generations still collide, so the standing rule is one anchoring
     * machine per runner class (IG120).
     */
    val processors: Int get() = Runtime.getRuntime().availableProcessors()

    val describe: String
        get() = "$key jdk=${System.getProperty("java.version")} processors=$processors"
}

/**
 * Every median this run measured, appended as JSON Lines to `build/perf-observations.jsonl`.
 *
 * This is what makes an anchor refreshable: a hosted-runner block can only be authored from a hosted run, so the
 * nightly uploads this file and [scripts/perf-anchor.sh] folds it into the committed anchor. Same append-per-call,
 * truncate-once-per-JVM discipline as `PerfHarness.progress` — Gradle buffers test stdout until a task ends, so a
 * file written incrementally is the only readable record of a long run.
 */
internal object PerfObservations {

    private val file: File by lazy {
        File("build/perf-observations.jsonl").apply {
            parentFile?.mkdirs()
            writeText("")
            appendLine(
                mapOf(
                    "kind" to "run",
                    "environment" to PerfEnvironment.key,
                    "os" to System.getProperty("os.name"),
                    "arch" to System.getProperty("os.arch"),
                    "jdk" to System.getProperty("java.version"),
                    "processors" to PerfEnvironment.processors,
                ),
            )
        }
    }

    private fun File.appendLine(record: Map<String, Any?>) =
        runCatching { appendText(MAPPER.writeValueAsString(record) + "\n") }

    fun record(key: ObservationKey, medianMs: Double) {
        file.appendLine(
            mapOf(
                "kind" to "observation",
                "environment" to PerfEnvironment.key,
                "key" to key.text,
                "scenario" to key.identityLabel,
                "n" to key.n,
                "engine" to key.engine,
                "clock" to key.clock,
                "medianMs" to medianMs,
            ),
        )
    }

    internal val MAPPER = ObjectMapper()
}

/**
 * One anchored measurement's identity. Construct it with NAMED arguments — four of its five components are
 * `String`, so a positional call is silently swap-prone.
 *
 * The [identityLabel] already carries `id@version fingerprint=<sha256>`, and that fingerprint covers the model, the
 * generated placements, the scale and the runtime bindings — so **changing a scenario's content changes its key**,
 * which drops the anchor rather than silently comparing new work against an old number. [lane] is part of the key
 * because one scenario at one scale can be measured under different regimes: the warm `B1 sub-quadratic check` and
 * the `COLD first-call (startup)` lane share `b1()` at 5,000 rows and must never share an anchor.
 */
internal data class ObservationKey(
    val lane: String,
    val identityLabel: String,
    val n: Int,
    val engine: String,
    val clock: String,
) {
    val text: String get() = "$lane|$identityLabel|n=$n|$engine|$clock"
}

/**
 * How far above an anchor a median may sit: a multiplicative [factor] for the measurement's own scale plus an
 * absolute [slackMs] so a sub-millisecond lane cannot flap on a fraction of a millisecond. The shape mirrors
 * `CharsetPerfTest`'s `FACTOR × baseline + 50 ms`.
 */
internal data class AnchorTolerance(val factor: Double, val slackMs: Double) {
    init {
        require(factor > 1.0) { "a factor of $factor would fail an unchanged measurement" }
        require(slackMs >= 0.0) { "slack must not be negative, was $slackMs" }
    }

    fun ceilingFor(anchorMs: Double): Double = anchorMs * factor + slackMs

    override fun toString(): String = "${factor}× + ${slackMs}ms"
}

/**
 * The committed per-runner-class absolute-time anchor — the only bar in this estate that can see a **uniform**
 * regression (IF280).
 *
 * Every other bar is a catastrophe floor: a growth ratio is unchanged by a uniform `k×` slowdown at every `k`, and
 * the interp/kernel bars need 2.4×–3800× (INTERPRETER-PERFORMANCE.md "the blind-band audit"). A portable constant
 * cannot do better, because every candidate normalizer drifts 2.07×–2.69× across environments. Comparing an
 * observation with **the same runner class's own recorded value** removes that drift, so the tolerance only has to
 * cover run-to-run noise, and [PerfBounds.ANCHOR_TOLERANCE] is set from measured noise rather than from taste.
 *
 * Three properties keep it honest:
 * - **A breach is re-measured** and accepted on the median of the attempts, exactly as the growth floors are
 *   (`PerfHarness.assertGrowthBelowCatastropheFloor` — the reason that pattern exists is that one contended slice
 *   must not turn a healthy guard red, while a real regression reproduces).
 * - **An unanchored observation is never silently skipped**, and the two ways of being unanchored are told apart.
 *   A runner class with **no block at all** is the bootstrap state: reported, never failed, because a first run on
 *   a new machine or a new hosted-runner generation is not a regression. A **missing key inside an anchored class**
 *   is the dangerous one — a new lane, or a scenario whose fingerprint changed and took its anchor with it — so
 *   under `-Dperf.anchor.required=true` (which CI sets) it FAILS rather than quietly switching the bar off.
 * - **The anchor is committed, not inferred.** A rolling baseline would let `K` successive within-tolerance
 *   degradations compound into an unbounded one; a committed number only moves when a human moves it, and moving it
 *   is a reviewable diff.
 */
internal object PerfAnchors {

    const val REQUIRED_PROPERTY = "perf.anchor.required"

    /** Set only by the dedicated single-fork `perfGateTest` task — see its comment in `adapter/build.gradle`. */
    const val LANE_PROPERTY = "perf.anchor.lane"

    internal const val ANCHOR_RESOURCE = "/perf/anchors.json"

    /** `null` when this runner class has no block at all — the bootstrap state, which is not a failure. */
    private val anchored: Map<String, Double>? by lazy {
        val stream = PerfAnchors::class.java.getResourceAsStream(ANCHOR_RESOURCE)
            ?: error("the committed performance anchor $ANCHOR_RESOURCE is missing from the test resources")
        val root = PerfObservations.MAPPER.readTree(stream)
        val environments = root.path("environments")
        check(environments.isObject && !environments.isEmpty) {
            "$ANCHOR_RESOURCE must anchor at least one runner class"
        }
        val block = environments.path(PerfEnvironment.key)
        if (block.isMissingNode) return@lazy null
        // Captured on a different machine in the same os/arch family: absolute times are not comparable across
        // those, so this is the bootstrap case, not a regression. Reported, never failed. A block without the
        // field predates it and is trusted.
        val capturedOn = block.path("processors")
        if (capturedOn.isInt && capturedOn.asInt() != PerfEnvironment.processors) {
            println(
                "[perf] anchor for ${PerfEnvironment.key} was captured on a ${capturedOn.asInt()}-processor " +
                    "machine and this one has ${PerfEnvironment.processors} — not asserting, since an absolute " +
                    "time is a property of the machine (IG120: one anchoring machine per runner class)",
            )
            return@lazy null
        }
        block.path("observations").properties().associate { (key, value) -> key to value.asDouble() }
    }

    private val required: Boolean get() = System.getProperty(REQUIRED_PROPERTY) == "true"

    /**
     * Record [observedMs] for a later refresh, then hold it against this runner class's committed value.
     * [remeasure] must repeat the same measurement [observedMs] came from, at the same scale and on the same
     * clock; it is called only when the first observation breaches.
     */
    fun assertWithinBudget(key: ObservationKey, observedMs: Double, remeasure: () -> Double) {
        if (System.getProperty(LANE_PROPERTY) != "true") {
            // Not the isolated lane: an absolute-time bar measured under parallel forks would be a coin flip, so
            // record the observation and assert nothing. Announced, because a silently skipped bar is the defect
            // this whole mechanism exists to avoid.
            println("[perf] anchor not asserted (-D$LANE_PROPERTY absent): ${key.text} measured $observedMs ms")
            PerfObservations.record(key, observedMs)
            return
        }
        // Recorded AFTER acceptance, and it is the ACCEPTED value: the record is what `scripts/perf-anchor.sh`
        // refreshes an anchor from, and the nightly uploads it even on failure, so a breaching first sample here
        // would ratchet a future anchor up to a spike the assertion itself rejected.
        val accepted = AnchorBudget(anchored, required, PerfBounds.ANCHOR_TOLERANCE)
            .assertWithinBudget(key, observedMs, remeasure)
        PerfObservations.record(key, accepted)
    }
}

/**
 * [PerfAnchors]' decision, separated from the resource file and the system property so the rule itself is checked
 * on scripted numbers ([PerfAnchorBudgetTest]) instead of only by a timed run — the sibling of
 * [PairedAcceptance] and `GrowthFloorRetryTest`.
 */
internal class AnchorBudget(
    /** `null` when this runner class has no committed block yet — reported, never failed (see the class doc). */
    private val anchored: Map<String, Double>?,
    private val unanchoredIsAFailure: Boolean,
    private val tolerance: AnchorTolerance,
    /** Independent re-measurements a breach must survive, on the median — the growth floors' attempt count. */
    private val attempts: Int = PerfHarness.GROWTH_RETRY_ATTEMPTS,
) {
    init {
        require(attempts > 0 && attempts % 2 == 1) { "attempts must be a positive odd number, was $attempts" }
    }

    /** @return the value acceptance was decided on: [observedMs], or the median of the re-measured attempts. */
    fun assertWithinBudget(key: ObservationKey, observedMs: Double, remeasure: () -> Double): Double {
        if (anchored == null) {
            // Bootstrap: nothing has ever been recorded for this runner class, so there is nothing to compare and
            // no guard to lose. Loud, but never a failure — a first run on a new machine or a new hosted-runner
            // generation must not read as a regression.
            println(
                "[perf] UNANCHORED ${PerfEnvironment.key} (no block for this runner class yet): ${key.text} " +
                    "measured ${ms(observedMs)} — author it from this run (scripts/perf-anchor.sh refresh)",
            )
            return observedMs
        }
        val anchor = anchored[key.text]
        if (anchor == null) {
            // The class IS anchored but this key is not: a lane was added, or a scenario's fingerprint changed and
            // took its anchor with it. That is exactly how a guard goes silently missing, so it fails where the bar
            // is required rather than passing quietly.
            val message = "[perf] UNANCHORED ${PerfEnvironment.key}: ${key.text} measured ${ms(observedMs)} " +
                "while this runner class IS anchored (${anchored.size} observations) — refresh it " +
                "(scripts/perf-anchor.sh) to arm the budget for this key"
            check(!unanchoredIsAFailure) {
                "$message; -D${PerfAnchors.REQUIRED_PROPERTY}=true refuses an unanchored observation in an " +
                    "anchored runner class, because that is how a bar silently disappears"
            }
            println(message)
            return observedMs
        }
        val ceiling = tolerance.ceilingFor(anchor)
        if (observedMs <= ceiling) return observedMs
        val seen = mutableListOf(observedMs)
        while (seen.size < attempts) seen += remeasure()
        val accepted = seen.sorted()[seen.size / 2]
        check(accepted <= ceiling) {
            "${key.identityLabel} at n=${key.n} (${key.lane}): ${key.engine} ${key.clock}-clock median " +
                "${ms(accepted)} exceeds ${ms(ceiling)} — this runner class (${PerfEnvironment.describe}) is " +
                "anchored at ${ms(anchor)} and the tolerance is $tolerance. Attempts: " +
                seen.joinToString { ms(it) } +
                "; acceptance is their median, so one contended slice cannot fail it and a real slowdown cannot " +
                "pass. A growth ratio cannot see this class of regression at all — it is unchanged by a uniform " +
                "slowdown. If the new cost is intended, refresh the anchor (scripts/perf-anchor.sh) in the same commit."
        }
        return accepted
    }

    /** [java.util.Locale.ROOT], so a number a maintainer may copy into the anchor file is not `12,45` on a German
     *  JVM and `12.45` on an English one — the surrounding `[perf]` log lines still carry that locale dependence. */
    private fun ms(value: Double): String = "%.2fms".format(java.util.Locale.ROOT, value)
}
