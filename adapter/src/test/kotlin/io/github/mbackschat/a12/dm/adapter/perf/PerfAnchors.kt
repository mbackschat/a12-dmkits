package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 * Every median this run measured, as JSON Lines under **`perf-results/<environment>/<utc-timestamp>.jsonl`** — one
 * file per run, committed, text only.
 *
 * **Not under `build/`, and that is the whole point** (CLAUDE.md, owner rule 2026-08-11; the guard is
 * `SurvivingDataNotInBuildGuardTest`). This record is read back *across* runs: it is what a historic table is
 * rendered from (`scripts/perf-history.sh`) and what an anchor is refreshed from (`scripts/perf-anchor.sh`, which
 * folds several run files at their per-key median). It lived in `adapter/build/` until 2026-08-11, where `clean`
 * deleted it, so a run could be compared against one committed anchor but never against its own history.
 *
 * **Each run records the conditions it ran under**, because the same commit measured on a loaded laptop and on a
 * 2-vCPU hosted runner are not comparable numbers: environment key, os/arch/JDK, processor count, the commit read
 * straight from `.git`, the CI run id when present, and the **system load average sampled per observation** so a
 * run that got busy halfway through is visible rather than merely puzzling.
 *
 * Append-per-call, as `PerfHarness.progress` is: Gradle buffers test stdout until a task ends, so an incrementally
 * written file is the only readable record of a long run.
 */
internal object PerfObservations {

    private val file: File by lazy {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        resultsDirectory().resolve(PerfEnvironment.key).resolve("$stamp.jsonl").toFile().apply {
            // Through the same guarded path as every observation. `writeText("")` used to sit here bare, so an
            // unwritable results directory — the exact shape a mistyped `-Dperf.results.dir` produces — surfaced
            // as a raw FileNotFoundException from a lazy initializer instead of the explanation this mechanism
            // advertises, and it did so BEFORE any observation could reach the guarded append.
            truncate(this)
            appendLine(
                mapOf(
                    "kind" to "run",
                    "environment" to PerfEnvironment.key,
                    "capturedAt" to stamp,
                    "commit" to headCommit(),
                    "os" to System.getProperty("os.name"),
                    "arch" to System.getProperty("os.arch"),
                    "jdk" to System.getProperty("java.version"),
                    "processors" to PerfEnvironment.processors,
                    "loadAverage" to loadAverage(),
                    "ci" to !System.getenv("CI").isNullOrBlank(),
                    "runId" to System.getenv("GITHUB_RUN_ID"),
                    "runnerName" to System.getenv("RUNNER_NAME"),
                ),
            )
        }
    }

    /**
     * `-Dperf.results.dir`, which the Gradle test tasks set to the repository's `perf-results`. The fallback walks
     * up for `settings.gradle` so a run outside Gradle still lands in the committed tree rather than in whatever
     * working directory happened to be current.
     */
    private fun resultsDirectory(): Path {
        System.getProperty("perf.results.dir")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        var here: Path? = Path.of("").toAbsolutePath()
        while (here != null) {
            if (here.resolve("settings.gradle").toFile().exists()) return here.resolve("perf-results")
            here = here.parent
        }
        error("cannot locate the repository root to place the performance record; pass -Dperf.results.dir")
    }

    /**
     * The revision this run measured, `-DIRTY` when the tree did not match it.
     *
     * Asked of `git`, not reconstructed from `.git`. The first version walked up and read `.git/HEAD` itself, which
     * is correct in an ordinary checkout and **wrong in a worktree**, where `.git` is a FILE holding `gitdir: …`:
     * `readText()` threw, `runCatching` swallowed it, and the run was recorded with no commit at all. Delegating
     * covers worktrees, detached HEAD and packed-refs-only for free. Runs once per JVM, outside every timed region.
     *
     * **The dirty marker is the point, not decoration.** A record's whole value is that a later run can be compared
     * against it, and a measurement taken on an edited tree did not measure the commit it names — `scripts/perf-anchor.sh`
     * has marked exactly this since it was written, and the record did not, so an uncommitted change could be
     * compared against its own baseline without anything saying so.
     */
    internal fun headCommit(): String {
        val head = git("rev-parse", "--short", "HEAD")?.takeIf { it.isNotEmpty() } ?: return "unknown"
        // Repo-wide (`:/`) because the test JVM's working directory is the module, not the root — and excluding
        // `perf-results`, since this very run leaves a new file there and would otherwise mark every honest
        // record DIRTY. The same pathspec the anchor refresh uses, for the same reason.
        val status = git("status", "--porcelain", "--", ":/", ":(top,exclude)perf-results")
            ?: return "$head-UNKNOWN-STATE"
        return commitLabel(head, dirty = status.isNotEmpty())
    }

    /** The rule, apart from the process call so it can be checked without a repository. */
    internal fun commitLabel(head: String, dirty: Boolean): String = if (dirty) "$head-DIRTY" else head

    /** Trimmed stdout of a git command, or `null` if it could not be run or failed. */
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", *args)).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) text else null
    }.getOrNull()

    private fun loadAverage(): Double =
        java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage

    /**
     * A write that fails FAILS the run. It used to be swallowed by a discarded `runCatching`, which made the one
     * outcome this mechanism exists to prevent — measuring everything and recording nothing — indistinguishable
     * from success: an unwritable or full `perf-results/` produced a green gate and an empty history. There is no
     * useful recovery here, so the only honest options are to write the line or to say that it could not be
     * written; a measurement that never reached the record is not a measurement anyone can compare against.
     */
    /** Start the run's file empty, with the same loud failure an append gets. */
    internal fun truncate(target: File) = write(target) { target.writeText("") }

    internal fun appendRecordTo(target: File, record: Map<String, Any?>) =
        write(target) { target.appendText(MAPPER.writeValueAsString(record) + "\n") }

    private fun write(target: File, body: () -> Unit) {
        try {
            body()
        } catch (failure: Exception) {
            throw IllegalStateException(
                "cannot write the performance record to $target — the run measured its numbers and would have " +
                    "discarded them, which is the failure this record exists to prevent",
                failure,
            )
        }
    }

    private fun File.appendLine(record: Map<String, Any?>) = appendRecordTo(this, record)

    fun record(key: ObservationKey, medianMs: Double, status: String) {
        file.appendLine(
            mapOf(
                "kind" to "observation",
                "environment" to PerfEnvironment.key,
                "key" to key.text,
                "scenario" to key.identityLabel,
                "lane" to key.lane,
                "n" to key.n,
                "engine" to key.engine,
                "clock" to key.clock,
                "medianMs" to medianMs,
                "status" to status,
                // Per observation, not per run: a machine that got busy halfway through is then visible in the
                // record rather than showing up later as an unexplained outlier.
                "loadAverage" to loadAverage(),
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
 *   under `-Dperf.anchor.required=true`, which the isolated `perfGateTest` lane sets unconditionally, it FAILS
 *   rather than quietly switching the bar off. It was CI-only until 2026-08-11, which put the failure on the one
 *   runner nobody watches while the local gate printed a note.
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
        val enforcement = TimingEnforcement.forAbsoluteAnchor()
        if (enforcement == TimingEnforcement.UNJUDGED) {
            // Not the isolated lane: an absolute-time bar measured under parallel forks would be a coin flip, so
            // record the observation and assert nothing. Announced, because a silently skipped bar is the defect
            // this whole mechanism exists to avoid.
            println("[perf] anchor not asserted (-D$LANE_PROPERTY absent): ${key.text} measured $observedMs ms")
            // `unasserted`, not `accepted`: this ran outside the isolated single-fork lane, where an absolute time
            // is a coin flip, so the refresh must not fold it even though it is a real observation.
            PerfObservations.record(key, observedMs, status = "unasserted")
            return
        }
        // Recorded on BOTH paths, and the status says which. The accepted value is what `scripts/perf-anchor.sh`
        // refreshes from — a breaching sample must never ratchet a future anchor up to a spike the assertion
        // rejected — but a failure that records NOTHING is worse: the missing-key throw (a lane added, or a
        // scenario whose fingerprint moved) used to suppress the very observation a refresh needs to author that
        // key, leaving no way forward but ALLOW_REMOVALS. So the failing path records too, marked `breached`, and
        // the refresh consumes only `accepted`.
        val accepted = try {
            AnchorBudget(anchored, required, PerfBounds.ANCHOR_TOLERANCE)
                .assertWithinBudget(key, observedMs, remeasure)
        } catch (structural: UnanchoredObservationException) {
            // Recorded as its own status rather than `breached`: there is no anchor here to ratchet, so this
            // observation is exactly what a refresh needs to AUTHOR the missing key, and `scripts/perf-anchor.sh`
            // folds it for that reason. Then rethrown unconditionally — see the type's own doc.
            try {
                PerfObservations.record(key, observedMs, status = "unanchored")
            } catch (unrecordable: Exception) {
                structural.addSuppressed(unrecordable)
            }
            throw structural
        } catch (failure: Throwable) {
            // A failed record must not REPLACE the breach it was recording: the perf failure is the finding, and
            // an IO error thrown from here would bury it behind an unrelated message. Attached, never swallowed —
            // the silent-loss path this closes was a discarded `runCatching`, and a mask is the same defect with
            // better manners.
            try {
                PerfObservations.record(key, observedMs, status = "breached")
            } catch (unrecordable: Exception) {
                failure.addSuppressed(unrecordable)
            }
            // Only two cases reach here — UNJUDGED returned above — so this is a two-way decision, not a dispatch
            // over the enum. An absolute time on shared hosted hardware cannot separate a regression from a
            // neighbour's memory traffic, and the number is in the record either way (TimingEnforcement).
            if (enforcement == TimingEnforcement.GATED) throw failure
            println(
                "[perf][FLAG] anchor breach reported, not failed on shared hosted hardware — " +
                    "see TimingEnforcement: ${failure.message}",
            )
            return
        }
        PerfObservations.record(key, accepted, status = "accepted")
    }
}

/**
 * [PerfAnchors]' decision, separated from the resource file and the system property so the rule itself is checked
 * on scripted numbers ([PerfAnchorBudgetTest]) instead of only by a timed run — the sibling of
 * [PairedAcceptance] and `GrowthFloorRetryTest`.
 */
/**
 * The anchor carries no number for this key while its runner class IS anchored — a lane was added, or a
 * scenario's fingerprint moved and took its anchor with it.
 *
 * **A distinct type because it is not a timing failure.** No clock is involved and no machine can make a key
 * appear, so `TimingEnforcement` must not govern it: shared-hardware contention is a reason to downgrade a
 * *measurement*, never a reason to stop noticing that a bar has gone missing. Before this type existed both
 * failures were `IllegalStateException` from a `check`, so the hosted flag swallowed this one too — which put
 * "a bar silently disappeared" back on exactly the runner nobody watches interactively, the inversion the
 * unconditional `perf.anchor.required` was introduced to remove.
 */
internal class UnanchoredObservationException(message: String) : IllegalStateException(message)

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
            if (unanchoredIsAFailure) {
                throw UnanchoredObservationException(
                    "$message; -D${PerfAnchors.REQUIRED_PROPERTY}=true refuses an unanchored observation in an " +
                        "anchored runner class, because that is how a bar silently disappears",
                )
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
    private fun ms(value: Double): String = "%.2fms".format(Locale.ROOT, value)
}
