package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ReferenceResolvers
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
 * **WI-T IG47 raw-outcome isolator** — the tooling gap the IG47 characterization recorded: the co-occurring
 * compute atoms (`kernel=ERRORED,interp=VALUE` — the ERRORED-classification residual; `kernel=VALUE,interp=absent`
 * / `kernel=CLEARED,interp=absent` — the iteration-scope misses) cannot be cleanly isolated by [ModelShrinkTest],
 * because THAT driver diffs the kernel against the **change-only granularity-projected** interpreter side
 * ([io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity]). That projection **drops a
 * seed-EQUAL interpreter VALUE** — so an `ERRORED-vs-VALUE(==seed)` atom reads as `interp=absent`, the shrink
 * predicate never matches, and the reduction drifts to a co-occurring divergence (probe-confirmed).
 *
 * This driver hunts + shrinks on the **RAW** interpreter outcome ([CorpusEngines.interpret], no granularity), so
 * a target atom class `(kernel-kind, interp-kind)` is matched per pointer exactly as the sweep measures it. The
 * atom is a PARAMETER — `-Dig47.kernel=<K>` / `-Dig47.interp=<K>` where a kind ∈ {`VALUE`,`CLEARED`,`ERRORED`,
 * `absent`} — so ONE tool serves every IG47 atom (default `ERRORED`/`VALUE`, the ERRORED-classification residual).
 *
 * Opt-in (`-PrunCorpusSweep`, tag `corpus-sweep`) + skip-if-absent (`-Da12.corpus1.dir`). Pin with
 * `-Dig47.model=<substring>` / `-Dig47.seed=<n>`; dump the minimal + its triggering placements to a SCRATCH file
 * with `-Dig47.dump=<scratch-file>` (never a repo path — the license boundary blocks persisting corpus content).
 *
 * **License boundary:** reads the corpora in place, prints only the [ModelShrinker.redactedShape] digest + the
 * atom kinds + counts (never a path or a value). A kept divergence is re-expressed own-domain elsewhere.
 */
@Tag("corpus-sweep")
class RawAtomShrinkTest {

    private companion object {
        const val INSTANCES = 5
        const val MAX_ROWS = 2
        const val CLOCK = "2026-07-07T12:00:00"
        const val MAX_MODELS_SCANNED = 400
    }

    private val targetKernel = System.getProperty("ig47.kernel", "ERRORED")
    private val targetInterp = System.getProperty("ig47.interp", "VALUE")

    @Test
    fun findAndShrinkOneRawAtom() {
        val dir = System.getProperty("a12.corpus1.dir")
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "a12.corpus1.dir not set / absent — skipping")
        val root = Path.of(dir)
        val resolver = ReferenceResolvers.directory(root)
        val modelFilter = System.getProperty("ig47.model")
        val seedFilter = System.getProperty("ig47.seed")?.toIntOrNull()
        println("\n[IG47] raw-atom hunt: target kernel=$targetKernel,interp=$targetInterp")

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

            val atom = findAtom(model, kernelJson, interpJson, locale, seedFilter) ?: continue
            println("[IG47] raw atom found (model #$scanned): seed=${atom.seed} pointer=<redacted>")
            shrinkAndReport(kernelJson, atom, locale)
            return
        }
        println("[IG47] no raw kernel=$targetKernel,interp=$targetInterp atom found in $scanned scanned models" +
            (modelFilter?.let { " (filter=$it)" } ?: ""))
    }

    private data class Atom(val seed: Int, val pointer: String, val placements: List<Placement>)

    /** Scan the model's seeds for a pointer whose RAW kernel/interp outcome kinds equal the target atom. */
    private fun findAtom(model: ModelDef, kernelJson: String, interpJson: String, locale: java.util.Locale,
                         seedFilter: Int?): Atom? {
        val seeds = seedFilter?.let { listOf(it) } ?: (0 until INSTANCES).toList()
        for (seed in seeds) {
            val placements = runCatching { CorpusSeeding.seedPlacements(model, seed.toLong(), MAX_ROWS) }.getOrNull() ?: continue
            val k = runCatching { CorpusEngines.groovyDynamic(kernelJson, placements, Op.Compute, CLOCK, locale) }.getOrNull() ?: continue
            val i = runCatching { CorpusEngines.interpret(interpJson, placements, Op.Compute, CLOCK) }.getOrNull() ?: continue
            val p = pointerMatching(k, i) ?: continue
            return Atom(seed, p, placements)
        }
        return null
    }

    /** The first pointer whose (kernel-kind, interp-kind) — RAW, no granularity — equals the target atom. */
    private fun pointerMatching(kernel: List<String>, interp: List<String>): String? {
        val kk = CorpusShrinkSupport.kinds(kernel)
        val ik = CorpusShrinkSupport.kinds(interp)
        return (kk.keys + ik.keys).firstOrNull { ptr ->
            (kk[ptr] ?: CorpusShrinkSupport.ABSENT) == targetKernel && (ik[ptr] ?: CorpusShrinkSupport.ABSENT) == targetInterp
        }
    }

    private fun shrinkAndReport(kernelJson: String, atom: Atom, locale: java.util.Locale) {
        val shrinker = ModelShrinker({ candidate -> stillDiverges(candidate, atom, locale) })
        println("[IG47] seed model shape:\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(kernelJson)))
        val minimal = shrinker.shrink(kernelJson)
        println("[IG47] shrink spent ${shrinker.runsSpent} engine runs")
        println("[IG47] MINIMAL model shape (license-safe — the shrink trace):\n" + CorpusShrinkSupport.indented(ModelShrinker.redactedShape(minimal)))
        System.getProperty("ig47.dump")?.let { p ->
            Files.writeString(Path.of(p), minimal)
            println("[IG47] minimal repro written to $p (scratch — do not commit)")
            val model = runCatching { ModelLoader.load(CorpusModelPreparer.toInterpreterJson(minimal)) }.getOrNull()
            if (model != null) {
                val pls = CorpusShrinkSupport.filterPlacements(model, atom.placements)
                val lines = pls.joinToString("\n") { "${it.kind}|${it.path}|${it.reps.joinToString(",")}|${it.value ?: ""}" }
                Files.writeString(Path.of("$p.placements"), lines)
                println("[IG47] minimal placements written to $p.placements")
            }
        }
    }

    /** The RAW divergence predicate: over the same seeded instance (placements filtered to surviving elements),
     *  does [candidate] still show the target atom's kernel-kind AND interp-kind at [atom.pointer] — reading the
     *  interpreter side RAW, with NO granularity projection (the whole point of this isolator)? */
    private fun stillDiverges(candidate: String, atom: Atom, locale: java.util.Locale): Boolean {
        val interpJson = runCatching { CorpusModelPreparer.toInterpreterJson(candidate) }.getOrNull() ?: return false
        val model = runCatching { ModelLoader.load(interpJson) }.getOrNull() ?: return false
        val placements = CorpusShrinkSupport.filterPlacements(model, atom.placements)
        val k = runCatching { CorpusEngines.groovyDynamic(candidate, placements, Op.Compute, CLOCK, locale) }.getOrNull() ?: return false
        val i = runCatching { CorpusEngines.interpret(interpJson, placements, Op.Compute, CLOCK) }.getOrNull() ?: return false
        return CorpusShrinkSupport.kindAt(k, atom.pointer) == targetKernel &&
            CorpusShrinkSupport.kindAt(i, atom.pointer) == targetInterp
    }
}
