package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * **WI-T IG44 driver** — find ONE compute CLEARED-vs-VALUE divergence in the (EUPL/proprietary) workspaces
 * corpus and minimize the model with [ModelShrinker], IN MEMORY, printing only the license-safe redacted shape
 * of the minimal model (the "minimal interaction" the isolated own-domain probes couldn't reach). The reduced
 * model is never persisted; the trace I write to the docs is re-expressed own-domain.
 *
 * Opt-in (`-PrunCorpusSweep`, tag `corpus-sweep`) and skip-if-absent (`-Da12.corpus1.dir`). Pin the divergence
 * to shrink with `-Dig44.model=<substring>` / `-Dig44.seed=<n>` to re-drive a specific one.
 */
@Tag("corpus-sweep")
class ModelShrinkTest {

    /** HOSTILE seeding (the IG56 hostile-mode atoms) — align the hunt with a `-Dcorpus.hostile=true` sweep. */
    private val strategy = if (System.getProperty("corpus.hostile") == "true") HostileValues
        else io.github.mbackschat.a12.dm.interpreter.gen.ValidValues

    private companion object {
        const val INSTANCES = 5
        const val MAX_ROWS = 2
        const val CLOCK = "2026-07-07T12:00:00"
        const val MAX_MODELS_SCANNED = 400
    }

    @Test
    fun findAndShrinkOneComputeCvv() {
        val dir = System.getProperty("a12.corpus1.dir")
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "a12.corpus1.dir not set / absent — skipping")
        val root = Path.of(dir)
        val resolver = ReferenceResolvers.directory(root)
        val modelFilter = System.getProperty("ig44.model")
        val seedFilter = System.getProperty("ig44.seed")?.toIntOrNull()

        var scanned = 0
        for (file in CorpusShrinkSupport.documentModels(root)) {
            if (modelFilter != null && !file.toString().contains(modelFilter)) continue
            if (scanned >= MAX_MODELS_SCANNED) break
            val kernelJson = runCatching { CorpusModelPreparer.prepareKernelJson(Files.readString(file), resolver) }.getOrNull() ?: continue
            val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(kernelJson) }.getOrNull() ?: continue
            val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: continue
            if (model.computations.isEmpty()) continue
            scanned++
            val locale = CorpusShrinkSupport.modelLocale(kernelJson)

            val cvv = findCvv(model, kernelJson, interpJson, locale, seedFilter) ?: continue
            println("\n[IG44] CVV divergence found (model #$scanned): seed=${cvv.seed} pointer=<redacted-path>")
            shrinkAndReport(kernelJson, cvv, locale)
            return
        }
        println("\n[IG44] no compute CLEARED-vs-VALUE divergence found in $scanned scanned models" +
            (modelFilter?.let { " (filter=$it)" } ?: ""))
    }

    private data class Cvv(val seed: Int, val pointer: String, val placements: List<Placement>)

    // -Dig44.vcv=true flips the hunt to the OVER-suppression direction (kernel=VALUE, interp=CLEARED) — the
    // co-occurring residual (IG44), for characterizing where the interpreter clears but the kernel computes.
    // (The IF76-era IG50 "formalError false-positive" reading of this direction was disconfirmed by IF78.)
    private val vcv = System.getProperty("ig44.vcv") == "true"

    /** Scan the model's seeds for a pointer that diverges in the tracked direction: default CVV (kernel CLEARED,
     *  interp VALUE); with [vcv] the reverse (kernel VALUE, interp CLEARED). */
    private fun findCvv(model: ModelDef, kernelJson: String, interpJson: String, locale: Locale,
                        seedFilter: Int?): Cvv? {
        val (clearedTag, valueTag) = if (vcv) "interp" to "kernel" else "kernel" to "interp"
        val seeds = seedFilter?.let { listOf(it) } ?: (0 until INSTANCES).toList()
        for (seed in seeds) {
            val placements = runCatching { CorpusSeeding.seedPlacements(model, seed.toLong(), MAX_ROWS, strategy) }.getOrNull() ?: continue
            val k = runCatching { CorpusEngines.groovyDynamic(kernelJson, placements, Op.Compute, CLOCK, locale) }.getOrNull() ?: continue
            val i = runCatching {
                kernelComputeGranularity(CorpusEngines.interpret(interpJson, placements, Op.Compute, CLOCK), placements, model)
            }.getOrNull() ?: continue
            val cleared = (if (clearedTag == "kernel") k else i).filter { it.endsWith("|CLEARED") }.map { it.removeSuffix("|CLEARED") }
            val valued = if (valueTag == "kernel") k else i
            val p = cleared.firstOrNull { p -> valued.any { it.startsWith("$p|VALUE") } } ?: continue
            return Cvv(seed, p, placements)
        }
        return null
    }

    private fun shrinkAndReport(kernelJson: String, cvv: Cvv, locale: Locale) {
        val predicate: (String) -> Boolean = { candidate -> stillDiverges(candidate, cvv, locale) }
        val shrinker = ModelShrinker(predicate)
        println("[IG44] seed model shape:\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(kernelJson)))
        val minimal = shrinker.shrink(kernelJson)
        println("[IG44] shrink spent ${shrinker.runsSpent} engine runs")
        println("[IG44] MINIMAL model shape (license-safe — the shrink trace):\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(minimal)))
        // WI-T investigation dump: write the minimal repro to a scratch path for in-memory root-causing (the
        // license boundary blocks PERSISTING corpus content in the repo, not investigating it — IG44). Off by
        // default; point -Dig44.dump=<scratch-file> at a session scratchpad. NEVER a repo path.
        System.getProperty("ig44.dump")?.let { p ->
            Files.writeString(Path.of(p), minimal)
            println("[IG44] minimal repro written to $p (scratch — do not commit)")
            // Also dump the EXACT triggering instance (the minimal's filtered placements) so the divergence is
            // reproducible standalone — fresh random re-seeding of the minimal misses the specific value pattern.
            // Placements are OUR deterministic seeding (not corpus content), so this is license-safe.
            val model = runCatching { ModelLoader.load(CorpusModelPreparer.toInterpreterJson(minimal)) }.getOrNull()
            if (model != null) {
                val pls = CorpusShrinkSupport.filterPlacements(model, cvv.placements)
                val lines = pls.joinToString("\n") { "${it.kind}|${it.path}|${it.reps.joinToString(",")}|${it.value ?: ""}" }
                Files.writeString(Path.of("$p.placements"), lines)
                println("[IG44] minimal placements written to $p.placements")
            }
        }
    }

    /** The divergence predicate: does [candidate] (a reduced kernel model) still clear [cvv.pointer] in the
     *  kernel while the interpreter computes a value — over the SAME seeded instance (original placements
     *  filtered to surviving fields/groups, so removing a field doesn't reshuffle the seed's other values)? */
    private fun stillDiverges(candidate: String, cvv: Cvv, locale: Locale): Boolean {
        val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(candidate) }.getOrNull() ?: return false
        val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: return false
        val placements = CorpusShrinkSupport.filterPlacements(model, cvv.placements)
        val k = runCatching { CorpusEngines.groovyDynamic(candidate, placements, Op.Compute, CLOCK, locale) }.getOrNull() ?: return false
        val i = runCatching {
            kernelComputeGranularity(CorpusEngines.interpret(interpJson, placements, Op.Compute, CLOCK), placements, model)
        }.getOrNull() ?: return false
        val cleared = if (vcv) i else k
        val valued = if (vcv) k else i
        return cleared.contains("${cvv.pointer}|CLEARED") && valued.any { it.startsWith("${cvv.pointer}|VALUE") }
    }
}
