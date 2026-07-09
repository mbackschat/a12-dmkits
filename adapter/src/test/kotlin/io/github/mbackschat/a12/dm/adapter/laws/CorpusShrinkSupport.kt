package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension

/**
 * Shared scaffolding for the WI-T corpus SHRINK drivers — [ModelShrinkTest] (the granularity-projected CVV/VCV
 * hunt, IG44) and [RawAtomShrinkTest] (the RAW-outcome per-pointer atom hunt, IG47). Kept in one place so the two
 * drivers don't each re-copy the corpus enumeration, the model-declared locale, the placement filter, and the
 * compute-signature outcome-kind map.
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

    private fun indent(s: String) = s.lines().joinToString("\n") { "    $it" }

    /** Indent a redacted shape for console printing (the only surface safe to log). */
    fun indented(s: String) = indent(s)
}
