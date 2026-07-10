package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * **The IG56 validateFull-unit isolator** — the fired-code sibling of [RawAtomShrinkTest]: the sweep counts
 * validateFull units (`both-sides-differ` / `kernel-only` / `interp-extra`) but no isolator could target one, so
 * those units were never huntable. This driver finds ONE validateFull signature diff — a `code|type|pointer` the
 * kernel fires and the interpreter misses (`-Dvf.side=kernel-only`, the default) or the reverse
 * (`-Dvf.side=interp-only`) — and [ModelShrinker]-minimizes the model with the exact signature PINNED, over the
 * SAME seeded instance.
 *
 * It replicates the sweep's **unsupported-rule exclusion** ([CorpusShrinkSupport.kernelSigsExcludingUnsupported])
 * — a rule the interpreter reports unsupported is excluded from the kernel side too — so it hunts exactly the
 * units the sweep counts, never a phantom.
 *
 * Corpus selection: the workspaces (`-Da12.corpus1.dir`) by default; `-Dvf.kernelmm=true` switches to the
 * canonical kernel-mm models (the E1 first-ever kernel-mm unit). Hostile seeding aligns with a
 * `-Dcorpus.hostile=true` sweep. Pin with `-Dvf.model=<substring>` / `-Dvf.seed=<n>` / `-Dvf.sig=<substring>`
 * (matched against the raw signature — an input filter, never printed); dump the minimal + its placements with
 * `-Dvf.dump=<scratch-file>` (never a repo path).
 *
 * **License boundary:** reads the corpora in place, prints only the [ModelShrinker.redactedShape] digest, the
 * side, and counts — never a signature, code, path, or value. A kept divergence is re-expressed own-domain.
 */
@Tag("corpus-sweep")
class ValidateUnitShrinkTest {

    private companion object {
        const val INSTANCES = 5
        const val MAX_ROWS = 2
        const val CLOCK = "2026-07-07T12:00:00"
        const val MAX_MODELS_SCANNED = 400
    }

    private val side = System.getProperty("vf.side", "kernel-only")
    private val kernelMm = System.getProperty("vf.kernelmm") == "true"

    /** HOSTILE seeding (the IG56 hostile-mode units) — align the hunt with a `-Dcorpus.hostile=true` sweep. */
    private val strategy = if (System.getProperty("corpus.hostile") == "true") HostileValues
        else io.github.mbackschat.a12.dm.interpreter.gen.ValidValues

    @Test
    fun findAndShrinkOneValidateUnit() {
        val root: Path
        val files: List<Path>
        if (kernelMm) {
            val mm = CorpusShrinkSupport.locateKernelMm()
            assumeTrue(mm != null, "a12-kernel-mm not present — skipping")
            root = mm!!
            files = CorpusShrinkSupport.canonicalKernelMmModels(root)
        } else {
            val dir = System.getProperty("a12.corpus1.dir")
            assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "a12.corpus1.dir not set / absent — skipping")
            root = Path.of(dir)
            files = CorpusShrinkSupport.documentModels(root)
        }
        val resolver = ReferenceResolvers.directory(root)
        val modelFilter = System.getProperty("vf.model")
        val seedFilter = System.getProperty("vf.seed")?.toIntOrNull()
        println("\n[vf] validateFull-unit hunt: side=$side corpus=" + (if (kernelMm) "kernel-mm" else "workspaces"))

        var scanned = 0
        for (file in files) {
            if (modelFilter != null && !file.toString().contains(modelFilter)) continue
            if (scanned >= MAX_MODELS_SCANNED) break
            val kernelJson = runCatching { CorpusModelPreparer.prepareKernelJson(Files.readString(file), resolver) }.getOrNull() ?: continue
            val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(kernelJson) }.getOrNull() ?: continue
            val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: continue
            scanned++
            val locale = CorpusShrinkSupport.modelLocale(kernelJson)

            val unit = findUnit(model, kernelJson, interpJson, locale, seedFilter) ?: continue
            println("[vf] validateFull unit found (model #$scanned): seed=${unit.seed} sig=<redacted>")
            shrinkAndReport(kernelJson, unit, locale)
            return
        }
        println("[vf] no $side validateFull unit found in $scanned scanned models" +
            (modelFilter?.let { " (filter=$it)" } ?: ""))
    }

    private data class Unit(val seed: Int, val sig: String, val placements: List<Placement>)

    /** Scan the model's seeds for a signature on the tracked [side] — kernel-fires-interp-misses or the reverse
     *  — after the sweep's unsupported-rule exclusion. */
    private fun findUnit(model: ModelDef, kernelJson: String, interpJson: String, locale: java.util.Locale,
                         seedFilter: Int?): Unit? {
        val sigFilter = System.getProperty("vf.sig")
        val seeds = seedFilter?.let { listOf(it) } ?: (0 until INSTANCES).toList()
        for (seed in seeds) {
            val placements = runCatching { CorpusSeeding.seedPlacements(model, seed.toLong(), MAX_ROWS, strategy) }.getOrNull() ?: continue
            val (k, i) = bothSides(kernelJson, interpJson, model, placements, locale) ?: continue
            val diff = when (side) {
                "interp-only" -> i - k.toSet()
                else -> k - i.toSet()
            }
            val sig = diff.firstOrNull { sigFilter == null || it.contains(sigFilter) } ?: continue
            return Unit(seed, sig, placements)
        }
        return null
    }

    /** Both engines' validateFull signatures over one instance, the kernel side after the unsupported exclusion;
     *  null when either engine cannot evaluate (the sweep's skip — not a unit). */
    private fun bothSides(kernelJson: String, interpJson: String, model: ModelDef, placements: List<Placement>,
                          locale: java.util.Locale): Pair<List<String>, List<String>>? {
        val kAll = runCatching { CorpusEngines.groovyDynamic(kernelJson, placements, Op.ValidateFull, CLOCK, locale) }.getOrNull() ?: return null
        val out = runCatching { InterpreterReplay.run(interpJson, placements, Op.ValidateFull, CLOCK) }.getOrNull() ?: return null
        val k = CorpusShrinkSupport.kernelSigsExcludingUnsupported(kAll, model, out.unsupported)
        return k to out.signatures
    }

    private fun shrinkAndReport(kernelJson: String, unit: Unit, locale: java.util.Locale) {
        val shrinker = ModelShrinker({ candidate -> stillDiverges(candidate, unit, locale) })
        println("[vf] seed model shape:\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(kernelJson)))
        val minimal = shrinker.shrink(kernelJson)
        println("[vf] shrink spent ${shrinker.runsSpent} engine runs")
        println("[vf] MINIMAL model shape (license-safe — the shrink trace):\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(minimal)))
        System.getProperty("vf.dump")?.let { p ->
            Files.writeString(Path.of(p), minimal)
            println("[vf] minimal repro written to $p (scratch — do not commit)")
            val model = runCatching { ModelLoader.load(CorpusModelPreparer.toInterpreterJson(minimal)) }.getOrNull()
            if (model != null) {
                val pls = CorpusShrinkSupport.filterPlacements(model, unit.placements)
                val lines = pls.joinToString("\n") { "${it.kind}|${it.path}|${it.reps.joinToString(",")}|${it.value ?: ""}" }
                Files.writeString(Path.of("$p.placements"), lines)
                println("[vf] minimal placements written to $p.placements")
            }
        }
    }

    /** The unit predicate: over the same seeded instance (placements filtered to surviving elements), is the
     *  PINNED signature still on the tracked side of the diff — present in one engine, absent from the other —
     *  with the unsupported exclusion recomputed on the candidate's surviving rules? */
    private fun stillDiverges(candidate: String, unit: Unit, locale: java.util.Locale): Boolean {
        val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(candidate) }.getOrNull() ?: return false
        val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: return false
        val placements = CorpusShrinkSupport.filterPlacements(model, unit.placements)
        val (k, i) = bothSides(candidate, interpJson, model, placements, locale) ?: return false
        return when (side) {
            "interp-only" -> unit.sig in i && unit.sig !in k
            else -> unit.sig in k && unit.sig !in i
        }
    }
}
