package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * **WI-7 — the opt-in real-corpus tri-engine sweep** (SEMANTICS-FORMALIZATION-PROPOSAL §6.7). For every model in
 * `../a12-kernel-mm` (the kernel team's metamodels) and the A12 Tools workspaces, it runs the differential the
 * committed fuzz can't: real, kernel-team-authored rules over seeded instances, asserting the interpreter agrees
 * with the production-normative Groovy-dynamic kernel (and, best-effort, the static-JAVA codegen).
 *
 * Pipeline per model — [CorpusModelPreparer] (kernel `expand` → self-contained; inline typedefs + normalize ids
 * for the interpreter) → [ModelLoader] → [CorpusSeeding] deterministic instances → [CorpusEngines] three ways.
 *
 * **License boundary (absolute):** both corpora are EUPL / proprietary and consult-only. NOTHING from them is
 * persisted — this test reads them in place and prints only aggregate statistics + kernel-generic construct
 * classes to the console (never committed). A divergence worth keeping is minimized and re-expressed in an
 * own-domain committed corpus case elsewhere; it never lands here.
 *
 * **Opt-in + skip-if-absent:** tagged `corpus-sweep` (excluded from the default/CI run — enable with
 * `-PrunCorpusSweep`) and each corpus [assumeTrue]-skips when absent (kernel-mm sibling / `-Da12.corpus1.dir`).
 *
 * **Skip-and-report:** a model or instance the pipeline can't handle (a construct the interpreter or kernel
 * codegen rejects, a custom condition with no SPI, …) is tallied by CLASS, never silently dropped — the tally is
 * itself a finding (interpreter coverage measured against real usage). Only a genuine **divergence** (both
 * engines evaluate but disagree) fails the sweep, forcing the WI-T triage.
 */
@Tag("corpus-sweep")
class RealCorpusSweepTest {

    private companion object {
        const val INSTANCES = 5          // deterministic seeded instances per model
        const val MAX_ROWS = 2           // rows per repeatable group (0..MAX_ROWS, seed-varied)
        // A fixed wall-clock instant pinned into BOTH engines (kernel currentDateForTest ⇄ interpreter EvalClock),
        // so Today/Now models evaluate deterministically instead of the interpreter refusing "needs a clock".
        const val CLOCK = "2026-07-07T12:00:00"
        val MAPPER = ObjectMapper()
        /** Java-static (in-memory javac) is best-effort — enable with -Dcorpus.sweep.java=true (slower). */
        val WITH_JAVA = System.getProperty("corpus.sweep.java") == "true"
    }

    @Test
    fun kernelMmSweep() {
        val root = locateKernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping real-corpus sweep")
        sweep("kernel-mm", root!!, canonicalKernelMmModels(root))
    }

    @Test
    fun workspacesSweep() {
        val dir = System.getProperty("a12.corpus1.dir")
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)),
            "a12.corpus1.dir not set / absent — skipping workspaces sweep")
        val root = Path.of(dir)
        sweep("workspaces", root, documentModels(root))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun sweep(label: String, corpusRoot: Path, modelFiles: List<Path>) {
        val resolver = ReferenceResolvers.directory(corpusRoot)
        val report = SweepReport(label)
        for (file in modelFiles) {
            val kernelJson = prepare(file, resolver, report) ?: continue
            val interpJson = try {
                CorpusModelPreparer.toInterpreterJson(kernelJson)
            } catch (t: Throwable) { report.modelSkip("interp-json-failed", file, t); continue }
            val model = try {
                ModelLoader.load(interpJson)
            } catch (t: Throwable) { report.modelSkip("load-failed", file, t); continue }
            report.modelSwept(model)

            val javaEngine = if (WITH_JAVA) tryJavaEngine(kernelJson, file, report) else null
            val locale = modelLocale(kernelJson)   // the kernel rejects a locale the model doesn't declare
            for (seed in 0 until INSTANCES) {
                val placements = try {
                    CorpusSeeding.seedPlacements(model, seed.toLong(), MAX_ROWS)
                } catch (t: Throwable) { report.evalSkip("seed-failed", file, t); continue }
                compareValidateFull(file, kernelJson, interpJson, model, placements, seed, javaEngine, locale, report)
                if (model.computations.isNotEmpty()) {
                    compareCompute(file, kernelJson, interpJson, placements, seed, locale, report)
                }
            }
        }
        report.print()
        // A DISCOVERY instrument, not a green gate (proposal §6.7 "skip-and-report triage list"): it reads the
        // external EUPL/proprietary corpora and surfaces divergences for WI-T triage; the durable regression
        // guards are the own-domain repros + the CI differential/fuzz. It asserts only that it actually RAN
        // (models swept, comparisons made) — a silent no-op would hide a broken pipeline. The findings live in
        // the printed class histogram + INTERPRETER-GAPS.
        assertTrue(report.models > 0 && report.validateComparisons > 0) {
            "$label: the sweep made no comparisons (${report.models} models, ${report.validateComparisons} runs) — " +
                "the pipeline is broken (locate/prepare/load), not merely divergent"
        }
    }

    private fun prepare(file: Path, resolver: com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver,
                        report: SweepReport): String? = try {
        CorpusModelPreparer.prepareKernelJson(Files.readString(file), resolver)
    } catch (t: Throwable) { report.modelSkip("prepare-failed", file, t); null }

    private fun tryJavaEngine(kernelJson: String, file: Path, report: SweepReport): RuntimeLaws.JavaKernelEngine? = try {
        RuntimeLaws.kernelJavaEngine(kernelJson)
    } catch (t: Throwable) { report.modelSkip("java-static-codegen-failed", file, t); null }

    private fun compareValidateFull(file: Path, kernelJson: String, interpJson: String, model: ModelDef,
                                    placements: List<Placement>, seed: Int, java: RuntimeLaws.JavaKernelEngine?,
                                    locale: java.util.Locale, report: SweepReport) {
        val kAll = try {
            CorpusEngines.groovyDynamic(kernelJson, placements, Op.ValidateFull, CLOCK, locale)
        } catch (t: Throwable) { report.evalSkip("kernel-eval:" + classify(t), file, t); return }
        val out = try {
            InterpreterReplay.run(interpJson, placements, Op.ValidateFull, CLOCK)
        } catch (t: Throwable) { report.evalSkip("interp-eval:" + classify(t), file, t); return }
        // A rule the interpreter can't evaluate (a CustomCondition with no SPI, or a construct gap) is reported
        // unsupported, not fired — so exclude its code from the kernel side too (else it reads as a spurious
        // kernel-only divergence). Both engines then compare only the rules the interpreter can model.
        val unsupportedCodes = model.rules.filter { rule -> out.unsupported.any { it.startsWith("${rule.path}:") } }
            .map { it.errorCode }.toSet()
        out.unsupported.forEach { report.unsupportedRule(classifyReason(it)) }
        val k = kAll.filterNot { it.substringBefore('|') in unsupportedCodes }
        val i = out.signatures
        report.validated(k)
        if (i != k) report.divergence(file, seed, "validateFull", k, i)
        // java-static is a recorded characterization, never the anchor (S3) — tally a split, don't fail on it.
        if (java != null) runCatching {
            val j = java.messagesModern(kernelDoc(placements), clockDate(), locale).map { "${it.code()}|${it.type().name}|${it.field()}" }
                .filterNot { it.substringBefore('|') in unsupportedCodes }.sorted()
            if (j != k) report.javaStaticSplit(file, "validateFull")
        }.onFailure { report.evalSkip("java-static-eval-failed", file, it) }
    }

    private fun compareCompute(file: Path, kernelJson: String, interpJson: String, placements: List<Placement>,
                               seed: Int, locale: java.util.Locale, report: SweepReport) {
        val k = try {
            CorpusEngines.groovyDynamic(kernelJson, placements, Op.Compute, CLOCK, locale)
        } catch (t: Throwable) { report.evalSkip("kernel-compute:" + classify(t), file, t); return }
        val i = try {
            CorpusEngines.interpret(interpJson, placements, Op.Compute, CLOCK)
        } catch (t: Throwable) { report.evalSkip("interp-compute:" + classify(t), file, t); return }
        report.computed()
        if (i != k) report.divergence(file, seed, "compute", k, i)
    }

    /** The pinned [CLOCK] as a [java.util.Date] for the java-static leg's kernel config. */
    private fun clockDate(): java.util.Date =
        java.util.Date.from(java.time.LocalDateTime.parse(CLOCK).atZone(java.time.ZoneId.systemDefault()).toInstant())

    /** The model's first declared locale (`header.locales[0].code`, e.g. `en` / `de` / `en_US`) as a [java.util.Locale];
     *  the kernel validates only against a declared locale. Falls back to [java.util.Locale.US]. */
    private fun modelLocale(kernelJson: String): java.util.Locale {
        val code = runCatching {
            MAPPER.readTree(kernelJson).path("header").path("locales").firstOrNull()?.let {
                if (it.isTextual) it.asText() else it.path("code").asText("")
            } ?: ""
        }.getOrDefault("")
        return if (code.isEmpty()) java.util.Locale.US else java.util.Locale.forLanguageTag(code.replace('_', '-'))
    }

    /** Replay placements into a kernel [RuntimeLaws.Doc] for the java-static leg's own instance building. */
    private fun kernelDoc(placements: List<Placement>): RuntimeLaws.Doc {
        val b = RuntimeLaws.doc()
        for (p in placements) when (p.kind) {
            PlacementKind.GROUP -> b.group(p.path, *p.reps.toIntArray())
            PlacementKind.FIELD -> b.field(p.path, p.value!!, *p.reps.toIntArray())
            PlacementKind.EMPTY -> b.emptyField(p.path, *p.reps.toIntArray())
        }
        return b
    }

    // ── skip classification — a license-safe, model-independent bucket key from an exception ────────────────

    /** Classify an interpreter unsupported-rule entry (`rulePath: reason`) into a license-safe bucket: a
     *  `CustomCondition` with no SPI (the expected skip for these corpora) vs a genuine construct gap (a finding).*/
    private fun classifyReason(entry: String): String {
        val reason = entry.substringAfter(": ", entry)
        return if (reason.contains("custom condition", ignoreCase = true)) "custom-condition (no SPI)"
        else "unsupported-construct: " + reason.replace(Regex("\"[^\"]*\""), "\"…\"")
            .replace(Regex("'[^']*'"), "'…'").take(70).trim()
    }

    /** A normalized bucket for a skip: the root exception's simple name + its message with quoted identifiers,
     *  digits, and paths stripped (so distinct models collapse to one construct class — never leaking content). */
    private fun classify(t: Throwable): String {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val msg = (root.message ?: "")
            .replace(Regex("'[^']*'"), "'…'")
            .replace(Regex("\"[^\"]*\""), "\"…\"")
            .replace(Regex("/[A-Za-z0-9_/]+"), "…")
            .replace(Regex("\\d+"), "#")
            .take(90).trim()
        return "${root::class.simpleName}: $msg"
    }

    // ── corpus model enumeration ───────────────────────────────────────────────────────────────────────────

    /** Newest non-previous file per kernel-mm model dir, preferring the a12- variant, else codegen-, else none-
     *  (mirrors KernelMmIntegrationTest — the render-variant that carries the full rule set). */
    private fun canonicalKernelMmModels(root: Path): List<Path> {
        val result = mutableListOf<Path>()
        Files.list(root).use { dirs ->
            dirs.filter { Files.isDirectory(it) }.sorted().forEach { dir ->
                var best: Path? = null
                var bestRank = Int.MAX_VALUE
                Files.list(dir).use { files ->
                    files.sorted().forEach { f ->
                        val n = f.name
                        if (n.endsWith(".json") && !n.contains("-previous")) {
                            val rank = when { n.startsWith("a12-") -> 0; n.startsWith("codegen-") -> 1; else -> 2 }
                            if (rank < bestRank) { bestRank = rank; best = f }
                        }
                    }
                }
                best?.let { result.add(it) }
            }
        }
        return result
    }

    /** Every `header.modelType == "document"` file under [root] (the evaluable models; `_TDM` libraries have no
     *  rules but load harmlessly). */
    private fun documentModels(root: Path): List<Path> {
        val out = mutableListOf<Path>()
        Files.walk(root).use { paths ->
            paths.filter { it.extension == "json" }.sorted().forEach { f ->
                val ok = runCatching { MAPPER.readTree(Files.readString(f)).path("header").path("modelType").asText() == "document" }
                    .getOrDefault(false)
                if (ok) out.add(f)
            }
        }
        return out
    }

    private fun locateKernelMm(): Path? = listOfNotNull(
        System.getProperty("a12.kernel.mm.dir"), "../a12-kernel-mm/documentModels", "../../a12-kernel-mm/documentModels",
    ).map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }

    // ── the report ─────────────────────────────────────────────────────────────────────────────────────────

    private class SweepReport(val label: String) {
        var models = 0
        var rules = 0
        var computationsDeclared = 0
        var validateComparisons = 0
        var computeComparisons = 0
        var javaStaticSplits = 0
        private val firedCodes = HashSet<String>()
        private val modelSkips = LinkedHashMap<String, MutableList<String>>()
        private val evalSkips = LinkedHashMap<String, Int>()
        private val unsupportedRules = LinkedHashMap<String, Int>()
        val divergences = mutableListOf<String>()

        fun modelSwept(m: ModelDef) { models++; rules += m.rules.size; computationsDeclared += m.computations.size }
        fun validated(sigs: List<String>) { validateComparisons++; sigs.forEach { firedCodes.add(it.substringBefore('|')) } }
        fun computed() { computeComparisons++ }
        fun javaStaticSplit(file: Path, op: String) { javaStaticSplits++ }
        fun unsupportedRule(cls: String) { unsupportedRules.merge(cls, 1) { a, b -> a + b } }
        fun modelSkip(cls: String, file: Path, t: Throwable) {
            modelSkips.getOrPut(cls) { mutableListOf() }.add(file.name)
        }
        fun evalSkip(cls: String, file: Path, t: Throwable) { evalSkips.merge(cls, 1) { a, b -> a + b } }
        private val divergenceClasses = LinkedHashMap<String, Int>()
        fun divergence(file: Path, seed: Int, op: String, kernel: List<String>, interp: List<String>) {
            val kOnly = kernel.toMutableList().apply { interp.forEach { remove(it) } }
            val iOnly = interp.toMutableList().apply { kernel.forEach { remove(it) } }
            divergences.add("${file.name} seed=$seed $op  kernel-only=${kOnly.take(6)} interp-only=${iOnly.take(6)}")
            // A license-safe shape class (op + which side has extra signatures) — the WI-T triage taxonomy; no
            // corpus codes leak (the raw kernel-only/interp-only lists are console-only, never committed).
            val shape = when {
                kOnly.isNotEmpty() && iOnly.isNotEmpty() -> "both-sides-differ"
                kOnly.isNotEmpty() -> "kernel-only (interp misses)"
                else -> "interp-only (interp extra)"
            }
            divergenceClasses.merge("$op: $shape", 1) { a, b -> a + b }
        }
        fun divergenceClassHistogram(): Map<String, Int> = divergenceClasses

        fun print() {
            println("\n[corpus-sweep] ===== $label =====")
            println("  models swept:        $models")
            println("  rules declared:      $rules   computations declared: $computationsDeclared")
            println("  validateFull runs:   $validateComparisons   compute runs: $computeComparisons")
            println("  distinct fired codes: ${firedCodes.size}")
            println("  java-static splits:  $javaStaticSplits")
            if (unsupportedRules.isNotEmpty()) {
                println("  unsupported rules by class (excluded from the differential, tallied per (rule,instance)):")
                unsupportedRules.entries.sortedByDescending { it.value }.forEach { (cls, n) -> println("     [$n] $cls") }
            }
            if (modelSkips.isNotEmpty()) {
                println("  model-level skips by class:")
                modelSkips.forEach { (cls, files) -> println("     [${files.size}] $cls   e.g. ${files.take(3)}") }
            }
            if (evalSkips.isNotEmpty()) {
                println("  eval-level skips by class:")
                evalSkips.entries.sortedByDescending { it.value }.forEach { (cls, n) -> println("     [$n] $cls") }
            }
            println("  interp≠kernel divergences: ${divergences.size}")
            if (divergenceClasses.isNotEmpty()) {
                println("  divergence classes (shape — the WI-T triage taxonomy):")
                divergenceClasses.entries.sortedByDescending { it.value }.forEach { (cls, n) -> println("     [$n] $cls") }
            }
            divergences.take(40).forEach { println("     · $it") }
        }
    }
}
