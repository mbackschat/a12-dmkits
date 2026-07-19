package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Live-catalog strategy evidence for every ordinary condition-form operator.
 *
 * Every catalog [io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry.validExample] is already
 * kernel-acceptance-locked by N7. This test injects all ordinary examples into one model, materializes one
 * deterministic non-empty instance, and evaluates that exact model through the interpreter,
 * Groovy-dynamic anchor, and generated-Java strategy. One generated model keeps the test cheap while each
 * catalog entry remains an independently derived matrix cell. The four SPI/computation specialists have
 * dedicated tri-checks because they need a consumer callback or the compute API.
 */
class CatalogOperatorStrategyDiffTest {

    private val catalog = OperatorCatalog.load()
    private val base = RuntimeLaws.model("models/order.dm.json")
    private val baseDef = ModelLoader.load(base)

    @Test
    fun everyOrdinaryCatalogOperatorRunsThroughBothKernelStrategies() {
        val entries = catalog.entries().filter { it.validExample() != null && it.id() !in SPECIALISTS }
        val model = entries.fold(base) { json, entry ->
            val condition = entry.validExample().dsl()
            RuntimeLaws.withRule(
                json,
                "/Order",
                "StrategyEvidence_${entry.id()}",
                "../${referencedErrorField(condition)}",
                evidenceCode(entry.id()),
                condition,
            )
        }
        val placements = CorpusSeeding.seedPlacementsFixed(ModelLoader.load(model), seed = 94L, rows = 2)

        val groovy = CorpusEngines.groovyDynamic(model, placements, Op.ValidateFull)
        val interpreter = CorpusEngines.interpret(model, placements, Op.ValidateFull)
        val javaStatic = CorpusEngines.javaStatic(model, placements, Op.ValidateFull)

        assertEquals(groovy, interpreter, "live catalog model: interpreter differs from Groovy-dynamic")
        assertEquals(groovy, javaStatic, "live catalog model: generated Java differs from Groovy-dynamic")
    }

    @Test
    fun liveCatalogIsExactlyPartitionedIntoOrdinaryAndSpecialistStrategyEvidence() {
        val operatorIds = catalog.entries().map { it.id() }.toSet()
        val ordinary = catalog.entries()
            .filter { it.validExample() != null && it.id() !in SPECIALISTS }
            .map { it.id() }
            .toSet()
        val computationOnly = catalog.entries()
            .filter { it.computationExample() != null }
            .map { it.id() }
            .toSet()

        assertEquals(SPECIALISTS, operatorIds intersect SPECIALISTS)
        assertEquals(operatorIds, ordinary + SPECIALISTS + computationOnly)
        assertTrue((ordinary intersect SPECIALISTS).isEmpty())
        assertTrue((ordinary intersect computationOnly).isEmpty())
        assertTrue((SPECIALISTS intersect computationOnly).isEmpty())
    }

    private fun referencedErrorField(condition: String): String {
        val relativeFields = baseDef.fields.map { it.path.removePrefix("/Order/") }
        return relativeFields.firstOrNull { condition.references(it) }
            ?: relativeFields.firstOrNull { field ->
                field.substringBefore('/').takeIf { it != field }?.let { condition.references(it) } == true
            }
            ?: error("no /Order field or group is referenced by catalog example: $condition")
    }

    private fun String.references(pathOrName: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(pathOrName)}(?![A-Za-z0-9_])").containsMatchIn(this)

    companion object {
        /** Consumer-SPI operators whose generated-Java checks live in their dedicated differential. */
        val SPECIALISTS: Set<String> = setOf("CustomCondition", "ValidType", "InvalidType")

        fun evidenceCode(operatorId: String): String = "EVIDENCE_$operatorId"
    }
}
