package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real DSL corpora, harvested once for every parser that wants to be held to them.
 *
 * Two parsers are: the interpreter's, which builds an AST it then evaluates, and the checker's, which only
 * decides whether the text forms a condition. Both have the same reason to care — these are strings the real
 * Kernel stores, so a parser that refuses one refuses a model that exists — and they must be held to the SAME
 * population, or a sweep that is green for one says nothing about the other. Hence one harvest with two
 * consumers rather than two harvests that can drift apart.
 *
 * Both corpora are external siblings under EUPL/proprietary licence and are never committed here; a sweep
 * skips when its corpus is absent.
 */
internal object ParserCorpora {

    private val MAPPER = ObjectMapper()

    /** The `a12-kernel-mm` metamodels at the kernel's model version ([KernelMmCorpus]), or null when absent. */
    fun kernelMm(): Path? = KernelMmCorpus.migrated()

    /** The A12 Tools workspaces, whose mount path is machine-local and so supplied as a property. */
    fun workspaces(): Path? =
        System.getProperty("a12.corpus1.dir")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    /**
     * Every DISTINCT (text, condition-language) pair for conditions and operations in the DocumentModels under [root], each with its own
     * condition language. Object Models also have an `operation` member, but it is not DocumentModel DSL.
     *
     * The language is read from the model rather than assumed, because assuming it is how a sweep measures
     * nothing: put through the wrong language every operator keyword reads as an undeclared name, so a
     * faithful parser refuses the whole corpus and the failure looks like a parser defect. Measured
     * 2026-09-13 — all 59 `a12-kernel-mm` models declare `en_US` outright, and one run that assumed German
     * reported 655 of their 657 conditions as over-rejections.
     *
     * That corpus declares ONE language, so it cannot by itself show this reading the declaration rather
     * than returning a constant; `ParserCorporaTest` supplies the model that does.
     */
    fun harvest(root: Path): Harvested {
        val conditions = linkedSetOf<Text>()
        val operations = linkedSetOf<Text>()
        CorpusShrinkSupport.documentModels(root).forEach { file ->
            val tree = try {
                MAPPER.readTree(Files.readString(file))
            } catch (_: Exception) {
                return@forEach
            }
            val language = tree.path("content").path("modelConfig").path("conditionLanguage")
                .path("code").asText("").ifEmpty { DEFAULT_LANGUAGE }
            collect(tree, language, conditions, operations)
        }
        return Harvested(conditions, operations)
    }

    private fun collect(
        node: JsonNode,
        language: String,
        conditions: MutableSet<Text>,
        operations: MutableSet<Text>,
    ) {
        when {
            node.isObject -> node.fields().forEach { (key, value) ->
                if (value.isTextual && value.asText().isNotBlank()) {
                    when (key) {
                        "errorCondition", "commonPrecondition", "precondition" -> conditions.add(Text(value.asText(), language))
                        "operation" -> operations.add(Text(value.asText(), language))
                    }
                }
                collect(value, language, conditions, operations)
            }
            node.isArray -> node.forEach { collect(it, language, conditions, operations) }
            else -> {}
        }
    }

    /** The A12 default when a model declares none, which is what `ConditionLanguage.DEFAULT` resolves to. */
    private const val DEFAULT_LANGUAGE = "en_US"

    /** Language is part of identity: the same bytes can exercise different parser behavior in EN and DE. */
    data class Text(val text: String, val language: String)

    data class Harvested(val conditions: Set<Text>, val operations: Set<Text>)
}
