package io.github.mbackschat.a12.dm.adapter.perf

import com.fasterxml.jackson.databind.JsonNode
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

    val processors: Int get() = Runtime.getRuntime().availableProcessors()

    /**
     * The MACHINE this run measured on, inside the runner class [key] names — see [MachineIdentity].
     *
     * Probed from the operating system, because the JVM exposes no CPU identity at all: `os.arch` names an
     * instruction set and `availableProcessors` a core count, and two machines can agree on both while differing
     * materially in absolute time. Probed once per JVM and lazily, so the cost lands between measurements rather
     * than inside one; a probe that fails degrades to [MachineIdentity.UNKNOWN_MODEL], which the gated check then
     * refuses to treat as an identity.
     */
    val machine: MachineIdentity by lazy { MachineIdentity(probeModel(), processors) }

    private fun probeModel(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val probed = when {
            // Both, and in this order: `hw.model` names the product (a Mac mini and a MacBook Air of the same
            // generation cool differently and time differently), `machdep.cpu.brand_string` names the chip.
            os.startsWith("mac") || os.startsWith("darwin") ->
                listOfNotNull(capture(null, "sysctl", "-n", "hw.model"), capture(null, "sysctl", "-n", "machdep.cpu.brand_string"))
                    .joinToString(" ")
            os.startsWith("windows") -> System.getenv("PROCESSOR_IDENTIFIER")
            // A file read rather than a subprocess. `lscpu` would name the same chip through a formatter that
            // varies by distribution, and this is the field a hosted Linux runner actually carries.
            else -> File("/proc/cpuinfo").takeIf { it.canRead() }?.useLines { lines ->
                lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')
            }
        }
        return probed?.trim()?.ifBlank { null } ?: MachineIdentity.UNKNOWN_MODEL
    }

    val describe: String
        get() = "$key jdk=${System.getProperty("java.version")} machine=${machine.text}"
}

/**
 * The machine an absolute number belongs to: an OS-level model string plus the core count.
 *
 * **Why the runner class is not enough.** `PerfEnvironment.key` names an os/arch family, which is what an artifact
 * and a committed block are addressed by — but absolute timings are a property of the machine. The block used to
 * record only [processors], which narrows the family without closing it: two machines with the same core count and
 * different generations still collided, and the rule that kept them apart ("one anchoring machine per runner class")
 * lived in prose that nothing could check. This is that rule, mechanized (IF280).
 */
/**
 * The JVM a perf number was produced on: the VM **family** and the language **major**.
 *
 * <p>Not the full version string. `java.version` alone cannot separate the vendors — Oracle GraalVM 21 reports
 * `21.0.12` and Homebrew OpenJDK reports `21.0.12.1`, which differ by coincidence rather than by family — while a
 * patch-exact identity would redden CI on every Temurin bump. Family plus major is the granularity that separates
 * the thing that actually moves numbers from the thing that does not.
 *
 * PERF RUNS ON OPENJDK 21 AND NOTHING ELSE (owner rule, 2026-08-23), and [requireSupported] refuses rather than
 * measures. The rule has a measured basis: on 2026-08-19 `brew install openjdk@21` displaced GraalVM 21.0.12 as
 * the build JDK — `java_home -v 21` returns the highest-versioned JDK 21 — and the same commit then measured
 * 105.88 ms under OpenJDK against 66.07 ms under GraalVM on the same machine on the same day, breaching a gate
 * that was read at first as an interpreter regression. Across all 91 comparable lanes the swap moved 45 of them
 * (median 1.15x) and made 10 faster, which is a JIT-characteristics change and not something any anchor can
 * absorb. [io.github.mbackschat.a12.dm.adapter.perf.PerfJdkGateTest] holds both directions.
 *
 * <p>Refusing beats disarming here, unlike [MachineIdentity], and the asymmetry is deliberate: a second MACHINE is
 * a legitimate thing to measure on and reads as bootstrap, whereas a second JDK means the pinned toolchain was not
 * applied — a misconfiguration to fix, not a state to tolerate. `scripts/gradle-environment.sh` makes it rare; this
 * makes it impossible to miss.
 */
internal data class JdkIdentity(val family: String, val major: String) {

    val text: String get() = "$family $major"

    internal companion object {

        /** Moves together with the build toolchain's `JavaLanguageVersion`; both are deliberately explicit. */
        const val REQUIRED_FAMILY = "OpenJDK"
        const val REQUIRED_MAJOR = "21"

        /**
         * GraalVM is detected by `jvmci` in `java.vm.version` (`21.0.12+7-LTS-jvmci-23.1-b96`) rather than by
         * vendor, because its `java.vm.name` is the Oracle-branded `Java HotSpot(TM) 64-Bit Server VM` and reads
         * as stock. An unrecognised VM keeps its own name instead of being bucketed as OpenJDK, so it is refused
         * and NAMED rather than silently accepted.
         */
        fun of(vmName: String, vmVersion: String, specificationVersion: String): JdkIdentity = JdkIdentity(
            family = when {
                vmVersion.contains("jvmci", ignoreCase = true) -> "GraalVM"
                vmName.startsWith("OpenJDK") -> "OpenJDK"
                else -> vmName.ifBlank { "unknown" }
            },
            major = specificationVersion.ifBlank { "unknown" },
        )

        val current: JdkIdentity by lazy {
            of(
                System.getProperty("java.vm.name").orEmpty(),
                System.getProperty("java.vm.version").orEmpty(),
                System.getProperty("java.specification.version").orEmpty(),
            )
        }

        /** The refusal, worded so the reader can act on it without finding this file. */
        fun refusalFor(jdk: JdkIdentity): String? {
            if (jdk.family == REQUIRED_FAMILY && jdk.major == REQUIRED_MAJOR) {
                return null
            }
            return "[perf] REFUSING to measure on ${jdk.text} — perf runs on $REQUIRED_FAMILY $REQUIRED_MAJOR and " +
                "nothing else (owner rule 2026-08-23), because a JIT change moves absolute numbers by more than " +
                "any anchor tolerance absorbs and reads as a code regression. Fix the toolchain rather than the " +
                "anchors: ./build.sh applies the pin in scripts/gradle-environment.sh, which selects OpenJDK 21 " +
                "and skips GraalVM (GraalVM is for :cli:nativeCompile via GRAALVM_HOME). Running JVM: " +
                "java.vm.name=${System.getProperty("java.vm.name")}, " +
                "java.vm.version=${System.getProperty("java.vm.version")}."
        }

        /** Called from the one seam EVERY record kind reaches, so no lane can measure around it. */
        fun requireSupported() {
            refusalFor(current)?.let { throw IllegalStateException(it) }
        }
    }
}

internal data class MachineIdentity(val model: String, val processors: Int) {

    val text: String get() = "$model ×$processors"

    val isKnown: Boolean get() = model != UNKNOWN_MODEL

    internal companion object {

        /** A machine the probe could not name. Never an identity: it would match every other unnamed machine. */
        const val UNKNOWN_MODEL = "unknown"

        /**
         * Whether a block captured on [captured] may be compared with a run on [current], and what to say about it.
         *
         * The three enforcement levels want three different things, so this switches on the enum rather than
         * applying one rule everywhere:
         * - [TimingEnforcement.GATED] — the number decides the build, so an identity is **required**: a mismatch, a
         *   block that does not name its machine, and a run that cannot name its own all read as bootstrap
         *   (reported, never failed). That is the same treatment a brand-new machine already got, and it is what
         *   makes "one anchoring machine per runner class" a check instead of a convention.
         * - [TimingEnforcement.FLAGGED] — a hosted runner is a VM on a shared physical host, chosen by the provider
         *   for that job. Demanding an identity there would leave the nightly with no absolute bar on any night the
         *   provider handed out different hardware, which trades a possibly-noisy signal for none at all. So the bar
         *   stays armed and the run states its machine, which is what lets a flag be attributed later.
         * - [TimingEnforcement.UNJUDGED] — nothing is asserted, so there is nothing for a machine to qualify.
         */
        fun check(
            captured: MachineIdentity?,
            current: MachineIdentity,
            enforcement: TimingEnforcement,
        ): MachineCheck = when (enforcement) {
            TimingEnforcement.UNJUDGED -> MachineCheck(assertAgainstBlock = false, note = null)
            TimingEnforcement.FLAGGED -> MachineCheck(
                assertAgainstBlock = true,
                note = "[perf] anchor for ${PerfEnvironment.key} is a bar over the runner CLASS, not over one " +
                    "machine: this run measured on ${current.text}" +
                    (captured?.let { ", the block was captured on ${it.text}" }
                        ?: ", and the block names no machine BY DESIGN — a hosted class does not have one") +
                    " — a breach here is flagged, never failed (TimingEnforcement)",
            )
            TimingEnforcement.GATED -> when {
                captured == null -> MachineCheck(
                    assertAgainstBlock = false,
                    note = "[perf] the anchor block for ${PerfEnvironment.key} does not name the machine it was " +
                        "captured on, so it could have come from any machine in the class — not asserting; " +
                        "refresh it on this machine (scripts/perf-anchor.sh refresh) to arm the bar",
                )
                !current.isKnown -> MachineCheck(
                    assertAgainstBlock = false,
                    note = "[perf] this run cannot name its own machine (${current.text}), so it cannot claim to be " +
                        "the one ${captured.text} was captured on — not asserting",
                )
                captured != current -> MachineCheck(
                    assertAgainstBlock = false,
                    note = "[perf] anchor for ${PerfEnvironment.key} was captured on ${captured.text} and this run " +
                        "is on ${current.text} — not asserting, since an absolute time is a property of the " +
                        "machine, and a second machine must read as bootstrap rather than redden every lane",
                )
                else -> MachineCheck(assertAgainstBlock = true, note = null)
            }
        }
    }
}

/** [MachineIdentity.check]'s outcome: whether to hold this run against the block, and what to print either way. */
internal data class MachineCheck(val assertAgainstBlock: Boolean, val note: String?)

/**
 * Trimmed stdout of a command, or `null` if it could not be run or failed. [dir] is its working directory when the
 * command needs one. Runs once per JVM per call site, outside every timed region.
 */
private fun capture(dir: File?, vararg args: String): String? = runCatching {
    val process = ProcessBuilder(args.toList())
        .apply { if (dir != null) directory(dir) }
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) text else null
}.getOrNull()

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
 * 2-vCPU hosted runner are not comparable numbers: environment key, os/arch/JDK, processor count, the commit
 * asked of `git` and marked `-DIRTY` when the tree did not match it, the CI run id when present, and the
 * **system load average sampled per observation** so a run that got busy halfway through is visible rather than
 * merely puzzling.
 *
 * Append-per-call, as `PerfHarness.progress` is: Gradle buffers test stdout until a task ends, so an incrementally
 * written file is the only readable record of a long run.
 */
internal object PerfObservations {

    /**
     * How many runs one environment keeps. Enforced HERE, at the moment of writing, and only backstopped by
     * `PerformanceRecordShapeGuardTest`.
     *
     * **Why the writer has to own it.** The guard cannot see the breach it guards: a perf lane must run alone and
     * therefore runs last, its record is written by that run, and the record is committed *after* the session's
     * estate check — so the run that crosses the horizon ships red by construction, which is exactly what
     * happened (2026-08-14, `darwin-aarch64` at 31). Pruning on write means the tree can never cross it.
     *
     * The guard keeps its own number as a backstop. If the two ever disagree the guard fires on the very next
     * run, loudly, so the duplication is self-correcting rather than silent.
     */
    internal const val MAX_RUNS_PER_ENVIRONMENT = 30

    private val file: File by lazy {
        // BEFORE the prune, not merely before the write. A refused run must leave the record tree exactly as it
        // found it, and `pruneToRetentionBound` below DELETES a committed run to make room for this one — so
        // gating only the append let a GraalVM run evict real history and then write a 0-byte file in its place.
        // Both were observed on 2026-08-24 while proving the append gate; this is the ordering that fixes them.
        JdkIdentity.requireSupported()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        pruneToRetentionBound(resultsDirectory().resolve(PerfEnvironment.key).toFile())
        resultsDirectory().resolve(PerfEnvironment.key).resolve("$stamp.jsonl").toFile().apply {
            // Through the same guarded path as every observation. `writeText("")` used to sit here bare, so an
            // unwritable results directory — the exact shape a mistyped `-Dperf.results.dir` produces — surfaced
            // as a raw FileNotFoundException from a lazy initializer instead of the explanation this mechanism
            // advertises, and it did so BEFORE any observation could reach the guarded append.
            truncate(this)
            appendLine(runHeader(stamp))
        }
    }

    /**
     * The run header, as a function so its **shape** can be held by a test.
     *
     * `machine` is the field the whole anchor mechanism rests on — `scripts/perf-anchor.sh` refuses an input that
     * omits it and the gated lane refuses a block that does not name one — so renaming or dropping it here would
     * break the refresh with a message about a missing machine and leave every test green.
     * [PerfObservationWriteTest] asserts the keys a consumer reads.
     */
    internal fun runHeader(stamp: String): Map<String, Any?> = mapOf(
        "kind" to "run",
        "environment" to PerfEnvironment.key,
        "capturedAt" to stamp,
        "commit" to headCommit(),
        "os" to System.getProperty("os.name"),
        "arch" to System.getProperty("os.arch"),
        "jdk" to System.getProperty("java.version"),
        // The FAMILY, alongside the version: `java.version` cannot separate Oracle GraalVM 21 (21.0.12) from
        // Homebrew OpenJDK (21.0.12.1), and it was that indistinguishability that let a compiler swap read as a
        // code regression for a day. Recorded for provenance; the gate itself is JdkIdentity.requireSupported.
        "jdkFamily" to JdkIdentity.current.family,
        "processors" to PerfEnvironment.processors,
        // The MACHINE, not just its core count: an absolute number is only comparable on the machine it
        // was measured on, and a refresh writes this into the block so the check can be made (IF280).
        "machine" to PerfEnvironment.machine.model,
        "loadAverage" to loadAverage(),
        "ci" to !System.getenv("CI").isNullOrBlank(),
        "runId" to System.getenv("GITHUB_RUN_ID"),
        "runnerName" to System.getenv("RUNNER_NAME"),
    )

    /**
     * Delete the oldest run files until this environment has room for one more, **never touching a run some
     * document cites**.
     *
     * The citation check is what makes the pruning safe rather than merely bounded: `perf-results/README.md`
     * states that past the horizon the durable references are the committed anchor and the checkpoints that cite
     * a run, so a cited file is the one thing that must survive its turn in the queue. Filenames are timestamps,
     * so lexical order IS chronological order.
     *
     * Best-effort by design: a prune that cannot run must never fail a measurement, because the record's job is
     * to observe and a writer that refuses to write loses the observation it was there to keep.
     *
     * It ANNOUNCES what it deleted, because these files are TRACKED: a gate run that silently removes committed
     * content leaves a bare `D perf-results/…` in `git status`, which reads as a defect rather than as retention.
     * That cost a v0.13.0 release-path investigation — the mechanism was correct and documented, and the only
     * thing missing was the one line saying it had happened.
     */
    internal fun pruneToRetentionBound(dir: File) {
        val runs = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: return
        val excess = runs.size - (MAX_RUNS_PER_ENVIRONMENT - 1)
        if (excess <= 0) return
        val cited = citedRunStems(dir)
        val pruned = mutableListOf<String>()
        for (run in runs) {
            if (pruned.size >= excess) break
            if (run.nameWithoutExtension in cited) continue
            if (run.delete()) pruned += run.nameWithoutExtension
        }
        if (pruned.isNotEmpty()) {
            println(
                "[perf][retention] pruned ${pruned.size} uncited record(s) past the $MAX_RUNS_PER_ENVIRONMENT-run " +
                    "bound for ${dir.name}: ${pruned.joinToString(", ")} — tracked files, so they appear as " +
                    "deletions in git status",
            )
        }
    }

    /** Run-file stems named anywhere in the committed prose — a cited run is never pruned. */
    private fun citedRunStems(dir: File): Set<String> {
        val root = dir.parentFile?.parentFile ?: return emptySet()
        val prose = sequenceOf(root.resolve("docs"), root.resolve("perf-results"))
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "md" } }
        return runCatching {
            val text = prose.joinToString("\n") { it.readText() }
            Regex("""\d{4}-\d{2}-\d{2}T\d{6}Z""").findAll(text).map { it.value }.toSet()
        }.getOrDefault(emptySet())
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
    internal fun headCommit(): String = headCommitIn(File("."))

    /**
     * The working directory is a parameter so the rule can be exercised against a REAL throwaway repository.
     * Without that seam the only available test compared this function against `git` run with the same pathspec,
     * which on a clean checkout asserts `false == false` — it passes for an implementation that never marks
     * anything, and CI only ever runs on a clean checkout.
     */
    internal fun headCommitIn(dir: File): String {
        val head = git(dir, "rev-parse", "--short", "HEAD")?.takeIf { it.isNotEmpty() } ?: return "unknown"
        // Repo-wide (`:/`) because the test JVM's working directory is the module, not the root — and excluding
        // `perf-results`, since this very run leaves a new file there and would otherwise mark every honest
        // record DIRTY. Same RULE as the anchor refresh's, not the same pathspec text: that script runs from the
        // repository root and can spell it `.` with a plain `exclude`, while this must anchor both at the root.
        val status = git(dir, "status", "--porcelain", "--", ":/", ":(top,exclude)perf-results")
            ?: return "$head-UNKNOWN-STATE"
        return commitLabel(head, dirty = status.isNotEmpty())
    }

    /** The rule, apart from the process call so it can be checked without a repository. */
    internal fun commitLabel(head: String, dirty: Boolean): String = if (dirty) "$head-DIRTY" else head

    /** Trimmed stdout of a git command run in [dir], or `null` if it could not be run or failed. */
    private fun git(dir: File, vararg args: String): String? = capture(dir, "git", *args)

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

    /**
     * The ONE seam every record kind reaches — observation, warm, cost, margin, and the run header itself — so
     * the JDK gate below cannot be measured around. It was on `record()` alone for its first hour, which gated
     * the medians and left `recordWarm`/`recordCost`/`recordMargin` writing: a refused GraalVM run then produced
     * a file with 102 warm records and 0 observations (measured 2026-08-24), which `PerformanceRecordShapeGuardTest`
     * correctly rejected as malformed. Gating one entry point of four is the symptom fix; this is the class fix.
     */
    private fun File.appendLine(record: Map<String, Any?>) {
        JdkIdentity.requireSupported()
        appendRecordTo(this, record)
    }

    /**
     * One engine instance's steady-state warm outcome, as a function so its **shape** can be held by a test.
     *
     * Recorded because it **qualifies** every cross-engine ratio measured on that instance: only `flattened` means
     * the instance actually reached steady state, and a still-falling kernel arm reads higher than its settled cost,
     * which makes our ratio look better than it is ([IF282]). It used to be printed and nothing more — so the cap's
     * justification lived in a `build/` log, a returning cap stop failed nothing, and no later run could compare
     * against it (IG121's fourth piece). The fields are the observation's own join key plus the outcome.
     */
    internal fun warmRecord(
        lane: String,
        scenario: String,
        n: Int,
        engine: String,
        runs: Int,
        stop: String,
    ): Map<String, Any?> = mapOf(
        "kind" to "warm",
        "environment" to PerfEnvironment.key,
        "lane" to lane,
        "scenario" to scenario,
        "n" to n,
        "engine" to engine,
        "runs" to runs,
        "stop" to stop,
        "loadAverage" to loadAverage(),
    )

    /** What one lane cost to MEASURE — the other number that lived only in a run log (IG121's fourth piece). */
    internal fun costRecord(lane: String, scenario: String, wallMs: Double): Map<String, Any?> = mapOf(
        "kind" to "cost",
        "environment" to PerfEnvironment.key,
        "lane" to lane,
        "scenario" to scenario,
        "wallMs" to wallMs,
        "loadAverage" to loadAverage(),
    )

    fun recordWarm(lane: String, scenario: String, n: Int, engine: String, runs: Int, stop: String) =
        file.appendLine(warmRecord(lane, scenario, n, engine, runs, stop))

    fun recordCost(lane: String, scenario: String, wallMs: Double) =
        file.appendLine(costRecord(lane, scenario, wallMs))

    /**
     * One engine arm's cross-engine MARGIN and the committed floor it was held against ([IF292]). Its own
     * record kind rather than a field on `observation`, because a margin is not a measurement of one engine: it
     * joins two arms of the same row, so it has no `medianMs` and would have to null out half an observation's
     * shape. [floor] is absent for a row the committed file does not band.
     */
    fun recordMargin(key: ObservationKey, margin: Double, floor: Double?, status: String) =
        file.appendLine(
            mapOf(
                "kind" to "margin",
                "environment" to PerfEnvironment.key,
                "key" to key.text,
                "scenario" to key.identityLabel,
                "lane" to key.lane,
                "n" to key.n,
                "engine" to key.engine,
                "clock" to key.clock,
                "margin" to margin,
                "status" to status,
            ) + (floor?.let { mapOf("floor" to it) } ?: emptyMap()),
        )

    /**
     * The environment-quality counters at the previous observation write, so each row can carry the delta over the
     * window that CONTAINS its measurement (IG121's third piece; the window's exact definition and the reason these
     * may never gate are on [PerfEnvironmentQuality]).
     */
    private var lastQuality: PerfEnvironmentQuality.Snapshot? = null

    /**
     * `@Synchronized` because the quality delta is **order-dependent** state, unlike everything else written here:
     * two interleaved writers would each read the other's snapshot and both report a wrong window, silently. The
     * lanes are single-fork and JUnit parallelism is off, so this changes no behaviour today — it stops a future
     * parallel lane from corrupting a number nothing would flag. (The plain file append is unsynchronized for every
     * record kind, which is pre-existing and left alone: a torn *line* is malformed JSON the record guard rejects
     * loudly, whereas a torn *delta* is well-formed and wrong, which is why only this one needs the lock.)
     */
    @Synchronized
    fun record(key: ObservationKey, medianMs: Double, status: String) {
        val quality = PerfEnvironmentQuality.snapshot()
        val window = PerfEnvironmentQuality.delta(lastQuality, quality)
        lastQuality = quality
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
            ) + window,
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
 * The band an observation must stay inside to say nothing.
 *
 * Above: how far above an anchor a median may sit before it FAILS — a multiplicative [factor] for the measurement's
 * own scale plus an absolute [slackMs] so a sub-millisecond lane cannot flap on a fraction of a millisecond. The
 * shape mirrors `CharsetPerfTest`'s `FACTOR × baseline + 50 ms`.
 *
 * Below: how far below it may sit before the anchor is REPORTED as stale ([staleFraction]) — an anchor is only a bar
 * for the revision it was captured on, and an observation at a third of its anchor means the ceiling is sitting at
 * three times the real cost. That side never fails: the usual cause is a legitimate optimization, and a red until
 * someone refreshes would make green mean "nobody sped anything up lately".
 */
internal data class AnchorTolerance(val factor: Double, val slackMs: Double, val staleFraction: Double) {
    init {
        require(factor > 1.0) { "a factor of $factor would fail an unchanged measurement" }
        require(slackMs >= 0.0) { "slack must not be negative, was $slackMs" }
        require(staleFraction > 0.0 && staleFraction < 1.0) {
            "a stale fraction of $staleFraction would report every honest observation below the anchor"
        }
    }

    fun ceilingFor(anchorMs: Double): Double = anchorMs * factor + slackMs

    /** The same absolute term as the ceiling, because the same tiny lanes need it for the same reason. */
    fun staleFloorFor(anchorMs: Double): Double = anchorMs * staleFraction - slackMs

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
        observationsFor(root, PerfEnvironment.key, PerfEnvironment.machine, TimingEnforcement.forAbsoluteAnchor())
    }

    /**
     * The numbers this run may be held against, or `null` when there are none — a class with no block at all, or one
     * whose block belongs to another machine.
     *
     * **Separated from the lazy and from the classpath so the JOIN is checkable.** [MachineIdentity.check] is
     * scripted in [PerfMachineIdentityTest] and the block's `machine` field is held by [PerfAnchorResourceTest],
     * while nothing exercised "the block names machine M, this run is on N, therefore do not assert" — the
     * parsed-but-never-consumed shape, on the wiring between two tested ends. [PerfAnchorWiringTest] now drives
     * this function directly.
     *
     * The note is emitted once per JVM because the decision is: it lives here rather than beside a per-key
     * assertion that would print it 36 times.
     */
    internal fun observationsFor(
        root: JsonNode,
        environmentKey: String,
        current: MachineIdentity,
        enforcement: TimingEnforcement,
        announce: (String) -> Unit = ::println,
    ): Map<String, Double>? {
        val block = root.path("environments").path(environmentKey)
        if (block.isMissingNode) return null
        val decision = MachineIdentity.check(capturedMachine(block), current, enforcement)
        decision.note?.let(announce)
        if (!decision.assertAgainstBlock) return null
        return block.path("observations").properties().associate { (key, value) -> key to value.asDouble() }
    }

    /** The machine a block names, or `null` when it names none — which the gated check refuses to trust. */
    private fun capturedMachine(block: JsonNode): MachineIdentity? {
        val model = block.path("machine").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val processors = block.path("processors").takeIf { it.isInt }?.asInt()
        return if (model == null || processors == null) null else MachineIdentity(model, processors)
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
                .assertWithinBudget(key, observedMs, enforcement, remeasure)
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
            // `breached` ONLY where the breach is a verdict. On shared hosted hardware it is a FLAG, and marking it
            // `breached` there had a consequence one layer away: `scripts/perf-anchor.sh` folds only `accepted`/
            // `unanchored`, so one noise-driven hosted flag drops that lane from the artifact and the next refresh
            // is REFUSED for incomplete coverage — the documented recovery path defeated by the noise it was
            // designed to tolerate. Latent while the hosted ceilings were 2-5x loose (nothing could breach); live
            // from the 2026-08-13 refresh that tightened them. A flag is a foldable observation, and the ratchet
            // this separation risks is bounded by the refresh being a reviewed diff (`perf-anchor.sh show` first).
            val status = if (enforcement == TimingEnforcement.GATED) "breached" else "flagged"
            try {
                PerfObservations.record(key, observedMs, status = status)
            } catch (unrecordable: Exception) {
                failure.addSuppressed(unrecordable)
            }
            // Only two cases reach here — UNJUDGED returned above — so this is a two-way decision, not a dispatch
            // over the enum. An absolute time on shared hosted hardware cannot separate a regression from a
            // neighbour's memory traffic, and the number is in the record either way (TimingEnforcement).
            if (enforcement == TimingEnforcement.GATED) throw failure
            println(hostedFlagFor(failure))
            return
        }
        PerfObservations.record(key, accepted, status = "accepted")
    }

    /**
     * The hosted flag, in TWO TIERS ([IG137](a)).
     *
     * **The measurement this rests on, and its limits.** On the `linux-amd64-ci` class the committed record holds
     * two same-commit triplets. Across the sequential one (`15b3ed0`, 138 shared keys) the *absolute* medians of
     * identical code span a median of 1.13x, a p90 of 1.85x and a worst of **2.29x** run-to-run; across the
     * earlier concurrently-dispatched one (`1845f8af`, 34 keys — contended against each other, IF280, so it
     * overstates) the worst is 1.98x. So identical code on this runner class has actually produced observations
     * differing by 2.29x, which is why one 1.65x threshold cannot say whether a flag means anything.
     *
     * [HOSTED_SAME_COMMIT_SPREAD] is that worst measured figure. Below it a breach is inside what identical code
     * has been seen to do here and is not attributable; at or above it the observation is outside anything the
     * record contains for unchanged code, which makes it worth a targeted re-measure. Deliberately a REPORT
     * boundary and not a bar: the estimate rests on two triplets, one of them contended, and a threshold that
     * failed a build would need a far stronger basis than that.
     *
     * The comparison is conservative in the safe direction — a run-to-run max/min spread is at least as large as
     * either run's deviation from the true level — so the band over-tolerates rather than crying wolf.
     */
    internal fun hostedFlagFor(failure: Throwable): String {
        val head = "[perf][FLAG] anchor breach reported, not failed on shared hosted hardware — see TimingEnforcement"
        val breach = failure as? AnchorBreachException
            ?: return "$head: ${failure.message}"
        val tier = if (breach.multiple >= HOSTED_SAME_COMMIT_SPREAD) {
            "BEYOND the same-commit spread this runner class has produced " +
                "(${"%.2f".format(Locale.ROOT, breach.multiple)}x of anchor, worst measured " +
                "${"%.2f".format(Locale.ROOT, HOSTED_SAME_COMMIT_SPREAD)}x for UNCHANGED code) — treat as a real " +
                "candidate and re-measure this lane in isolation before dismissing it"
        } else {
            "within the same-commit spread this runner class has produced " +
                "(${"%.2f".format(Locale.ROOT, breach.multiple)}x of anchor, against " +
                "${"%.2f".format(Locale.ROOT, HOSTED_SAME_COMMIT_SPREAD)}x measured for UNCHANGED code) — not " +
                "attributable on its own"
        }
        return "$head. Tier: $tier. ${breach.message}"
    }

    /**
     * The worst run-to-run spread of absolute medians the committed record holds for ONE commit on a hosted
     * runner class (`15b3ed0`, three sequential nightly runs, 138 shared keys). See [hostedFlagFor] for what it
     * does and does not license; re-derive it when the hosted record gains further same-commit runs.
     */
    internal const val HOSTED_SAME_COMMIT_SPREAD = 2.29
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

/**
 * An absolute-anchor breach, carrying the two numbers that produced it.
 *
 * **Why a type rather than the `check` it replaced.** On shared hosted hardware a breach is FLAGGED rather than
 * failed, and one flag line said the same thing at every size — which is the whole of [IG137](a): a single
 * threshold cannot separate drift from noise on a runner class whose *same commit* has been measured spanning
 * over 2x. Classifying the flag needs the accepted median and the anchor at the reporting site, and reading them
 * back out of a formatted message would be the kind of coupling that rots on the next wording change.
 */
internal class AnchorBreachException(
    message: String,
    val accepted: Double,
    val anchor: Double,
) : IllegalStateException(message) {

    /** How far above its anchor this observation sits — the quantity the hosted tier classifies. */
    val multiple: Double get() = accepted / anchor
}

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
    fun assertWithinBudget(
        key: ObservationKey,
        observedMs: Double,
        /**
         * Where this is running, which decides whether a STALE claim is meaningful. Injected so both arms are
         * testable without a hosted runner, exactly as [MachineIdentity.check] takes it.
         *
         * **Required, with no default, and that is the point.** It defaulted to `forAbsoluteAnchor()` — the very
         * expression [PerfAnchors.assertWithinBudget] computes and passes in — so removing the pass-through at the
         * call site was a semantic NO-OP and no test could ever distinguish it. A mutation review duly reported it
         * as an uncovered wiring, which it was not: it was an *equivalent* mutant, and the equivalence was the
         * defect. Two copies of one decision stay equal only until someone edits one. With the default gone the
         * value has a single source and dropping the pass-through no longer compiles, which beats any test.
         */
        enforcement: TimingEnforcement,
        remeasure: () -> Double,
    ): Double {
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
        // A STALE claim needs a STABLE machine, so it is not made on a hosted class. Measured 2026-08-16: three
        // runs of ONE commit on one JDK, differing only in which CPU the provider handed out, read 7.92 / 15.09 /
        // 17.12 ms at n=1500 and 31.59 / 71.05 / 77.73 at n=6000 — a 2.16x and 2.46x spread on byte-identical
        // code. "Measured well below the anchor" and "drew a fast CPU" are the same observation there, so the
        // report is pure noise that generates maintenance work forever: it fired on 2 of 4 earlier runs and
        // asked for a refresh each time, while the three-run median sits at 0.93x/0.89x of the very anchor it
        // called stale. The CPU lottery is a permanent property of hosted runners, so this is not a gap waiting
        // on better data — an absolute bar there answers a question about the provider's fleet, not the code.
        // WHAT THIS LOSES ON HOSTED, stated exactly, because the first version of this comment named the wrong
        // replacement. The within-run cross-engine ratios do NOT keep this coverage: they divide out the machine
        // by construction (IF281), and that same property makes them blind to the thing STALE detects — an
        // anchor that stopped describing the code. A uniform speedup leaves every ratio unchanged while the
        // anchor drifts to a multiple of the real cost, which is precisely the failure a ratio cannot see.
        // The real replacement is the STEP view of the committed record (`scripts/perf-trend.sh`), which the
        // nightly runs (`.github/workflows/nightly-performance.yml`) and which reads a level dropping across
        // runs rather than one run against a constant — so it needs no stable machine. It REPORTS and exits 0,
        // so on a hosted class this coverage is a report a maintainer reads, not a gate. That is the honest
        // trade, and it is a downgrade rather than an equivalence.
        if (enforcement != TimingEnforcement.FLAGGED) {
            staleReportFor(key, observedMs)?.let { println(it) }
        }
        val ceiling = tolerance.ceilingFor(anchor)
        if (observedMs <= ceiling) return observedMs
        val seen = mutableListOf(observedMs)
        while (seen.size < attempts) seen += remeasure()
        val accepted = seen.sorted()[seen.size / 2]
        if (accepted > ceiling) {
            throw AnchorBreachException(
                "${key.identityLabel} at n=${key.n} (${key.lane}): ${key.engine} ${key.clock}-clock median " +
                    "${ms(accepted)} exceeds ${ms(ceiling)} — this runner class (${PerfEnvironment.describe}) is " +
                    "anchored at ${ms(anchor)} and the tolerance is $tolerance. Attempts: " +
                    seen.joinToString { ms(it) } +
                    "; acceptance is their median, so one contended slice cannot fail it and a real slowdown " +
                    "cannot pass. A growth ratio cannot see this class of regression at all — it is unchanged by " +
                    "a uniform slowdown. If the new cost is intended, refresh the anchor " +
                    "(scripts/perf-anchor.sh) in the same commit.",
                accepted = accepted,
                anchor = anchor,
            )
        }
        return accepted
    }

    /**
     * Why this run says the anchor no longer describes the code, or `null` when it does not say so.
     *
     * Separated from the assertion so the rule can be checked on scripted numbers, exactly as acceptance is
     * ([PerfStaleAnchorTest]). An unanchored key has nothing to be stale against.
     */
    internal fun staleReportFor(key: ObservationKey, observedMs: Double): String? {
        val anchor = anchored?.get(key.text) ?: return null
        val floor = tolerance.staleFloorFor(anchor)
        if (observedMs >= floor) return null
        return "[perf][STALE] ${key.identityLabel} at n=${key.n} (${key.lane}): ${key.engine} measured " +
            "${ms(observedMs)} against an anchor of ${ms(anchor)} — ${"%.2f".format(Locale.ROOT, observedMs / anchor)}× " +
            "of it, so the ceiling this lane is held to sits at ${"%.1f".format(Locale.ROOT, anchor / observedMs)}× " +
            "the real cost and cannot fail. An anchor is only a bar for the revision it was captured on: refresh it " +
            "(scripts/perf-anchor.sh) in the commit that made the lane faster."
    }

    /** [java.util.Locale.ROOT], so a number a maintainer may copy into the anchor file is not `12,45` on a German
     *  JVM and `12.45` on an English one. The surrounding `[perf]` log lines now hold the same rule, which
     *  `PerfNumberLocaleContractTest` enforces across the package. */
    private fun ms(value: Double): String = "%.2fms".format(Locale.ROOT, value)
}
