package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Shared scaffolding for the WI-T corpus SHRINK drivers — [ModelShrinkTest] (the granularity-projected CVV/VCV
 * hunt, IG44), [RawAtomShrinkTest] (the RAW-outcome per-pointer atom hunt — IF88/IF89's drills), and
 * [ValidateUnitShrinkTest] (the validateFull fired-code unit hunt — the IG56 drills) — plus the sweep itself
 * ([RealCorpusSweepTest]). Kept in one place so the drivers
 * don't each re-copy the corpus enumeration, the model-declared locale, the placement filter, the
 * compute-signature outcome-kind/value maps, and the sweep's unsupported-rule exclusion.
 *
 * **License boundary (absolute).** Every helper reads the EUPL/proprietary corpora in place and persists NOTHING:
 * paths and values are used only to drive engines / filter placements, never surfaced. The only thing safe to
 * print is a [ModelShrinker.redactedShape] digest, which lives on the shrinker.
 */
object CorpusShrinkSupport {

    private val MAPPER = ObjectMapper()

    /** Every `header.modelType == "document"` file under [root] (sorted) — the evaluable models. */
    fun documentModels(root: Path): List<Path> {
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

    /** The model's first declared locale (`header.locales[0]`, textual or `.code`) as a [Locale] — the kernel
     *  validates only against a declared locale. Falls back to [Locale.US]. */
    fun modelLocale(kernelJson: String): Locale {
        val code = runCatching {
            MAPPER.readTree(kernelJson).path("header").path("locales").firstOrNull()?.let {
                if (it.isTextual) it.asText() else it.path("code").asText("")
            } ?: ""
        }.getOrDefault("")
        return if (code.isEmpty()) Locale.US else Locale.forLanguageTag(code.replace('_', '-'))
    }

    /** Keep only placements whose path still exists in [model] after a reduction: a FIELD/EMPTY on a declared
     *  field, a GROUP on a path that is still an ancestor of some surviving field. So removing a field doesn't
     *  reshuffle the seed's other values (the divergence stays pinned to the same instance). */
    fun filterPlacements(model: ModelDef, placements: List<Placement>): List<Placement> {
        val fields = model.kinds.keys
        val groups = fields.flatMap { f -> ancestorPaths(f) }.toSet()
        return placements.filter { p ->
            when (p.kind) {
                PlacementKind.GROUP -> p.path in groups
                else -> p.path in fields
            }
        }
    }

    private fun ancestorPaths(fieldPath: String): List<String> {
        val parts = fieldPath.trim('/').split('/')
        return (1 until parts.size).map { "/" + parts.take(it).joinToString("/") }
    }

    /** The signature kind of "no outcome at this pointer" (neither engine reported it). */
    const val ABSENT = "absent"

    /** The per-pointer compute-outcome KIND map of a signature list (`pointer|VALUE|value` / `…|CLEARED` /
     *  `…|ERRORED` → `pointer → VALUE|CLEARED|ERRORED`). Content-free: the value tail after the second `|` is
     *  discarded, only the outcome kind is kept. */
    fun kinds(signatures: List<String>): Map<String, String> =
        signatures.associate { it.substringBefore('|') to it.substringAfter('|').substringBefore('|') }

    /** The outcome kind at [pointer] in [signatures], or [ABSENT] when the engine reported nothing there. */
    fun kindAt(signatures: List<String>, pointer: String): String = kinds(signatures)[pointer] ?: ABSENT

    /** The per-pointer VALUE-string map of a compute signature list (`pointer|VALUE|value` → `pointer → value`);
     *  a CLEARED/ERRORED pointer is absent. The value-comparing complement of [kinds] — for the kind-EQUAL
     *  `VALUE`-vs-`VALUE` diffs a kind-keyed hunt cannot see. */
    fun values(signatures: List<String>): Map<String, String> =
        signatures.filter { "|VALUE|" in it }.associate { it.substringBefore('|') to it.substringAfter("|VALUE|") }

    /** The kernel-side validateFull signatures with the interpreter-UNSUPPORTED rules' codes excluded — the
     *  sweep's comparison rule ([RealCorpusSweepTest]): a rule the interpreter reports unsupported (a
     *  CustomCondition with no SPI, a construct gap) is not fired, so its code must not read as a kernel-only
     *  divergence. A validateFull isolator MUST apply the same exclusion or it hunts phantom units. */
    fun kernelSigsExcludingUnsupported(kernelSigs: List<String>, model: ModelDef, unsupported: List<String>): List<String> {
        val codes = model.rules.filter { rule -> unsupported.any { it.startsWith("${rule.path}:") } }
            .map { it.errorCode }.toSet()
        return kernelSigs.filterNot { it.substringBefore('|') in codes }
    }

    /** Newest non-previous file per kernel-mm model dir, preferring the a12- variant, else codegen-, else none-
     *  (mirrors KernelMmIntegrationTest — the render-variant that carries the full rule set). */
    fun canonicalKernelMmModels(root: Path): List<Path> {
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

    /** The consult-only kernel-mm corpus root (`-Da12.kernel.mm.dir` or the sibling clone), null when absent. */
    fun locateKernelMm(): Path? = KernelMmCorpus.migrated()

    private fun indent(s: String) = s.lines().joinToString("\n") { "    $it" }

    /** Indent a redacted shape for console printing (the only surface safe to log). */
    fun indented(s: String) = indent(s)
}
