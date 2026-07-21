package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Live-catalog strategy evidence for every ordinary condition-form operator.
 *
 * Every catalog [io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry.validExample] is already
 * kernel-acceptance-locked by N7. This test partitions ordinary examples by their fixture domain into the
 * Order model and the Warehouse multi-star model, materializes deterministic non-empty instances, and
 * evaluates each model through the interpreter, Groovy-dynamic anchor, and generated-Java strategy. Two
 * generated models keep the test cheap while each catalog entry remains an independently derived matrix
 * cell. Consumer-SPI specialists and computation-only operators keep their dedicated tri-checks.
 */
class CatalogOperatorStrategyDiffTest {

    private val catalog = OperatorCatalog.load()
    private val base = RuntimeLaws.model("models/order.dm.json")
    private val baseDef = ModelLoader.load(base)
    private val multiStarBase = DocumentModelDraft.named("warehouse").locale("en_US")
        .rootGroup("Warehouse") { warehouse ->
            warehouse.group("Aisles") { aisles ->
                aisles.repeatable(20)
                    .stringField("Code") { }
                    .group("Bins") { bins -> bins.repeatable(20).numberField("Quantity") { } }
            }
        }
        .build().toJson()
    private val multiStarDef = ModelLoader.load(multiStarBase)

    @Test
    fun everyOrdinaryCatalogOperatorRunsThroughBothKernelStrategies() {
        val entries = catalog.entries().filter { it.validExample() != null && it.id() !in SPECIALISTS }
        assertStrategyEvidence(base, baseDef, "/Order", entries.filter { it.id() !in MULTI_STAR_IDS })
        assertStrategyEvidence(
            multiStarBase,
            multiStarDef,
            "/Warehouse",
            entries.filter { it.id() in MULTI_STAR_IDS },
        )
    }

    private fun assertStrategyEvidence(
        fixture: String,
        fixtureDef: ModelDef,
        ruleGroup: String,
        entries: List<OperatorEntry>,
    ) {
        val model = entries.fold(fixture) { json, entry ->
            val condition = entry.validExample().dsl()
            RuntimeLaws.withRule(
                json,
                ruleGroup,
                "StrategyEvidence_${entry.id()}",
                "../${referencedErrorField(condition, fixtureDef, ruleGroup)}",
                evidenceCode(entry.id()),
                condition,
            )
        }
        val placements = CorpusSeeding.seedPlacementsFixed(ModelLoader.load(model), seed = 94L, rows = 2)

        val groovy = CorpusEngines.groovyDynamic(model, placements, Op.ValidateFull)
        val interpreter = CorpusEngines.interpret(model, placements, Op.ValidateFull)
        val javaStatic = CorpusEngines.javaStatic(model, placements, Op.ValidateFull)

        assertEquals(groovy, interpreter, "live catalog model $ruleGroup: interpreter differs from Groovy-dynamic")
        assertEquals(groovy, javaStatic, "live catalog model $ruleGroup: generated Java differs from Groovy-dynamic")
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

    private fun referencedErrorField(condition: String, fixtureDef: ModelDef, ruleGroup: String): String {
        val relativeFields = fixtureDef.fields.map { it.path.removePrefix("$ruleGroup/") }
        return relativeFields.firstOrNull { condition.references(it) }
            ?: relativeFields.firstOrNull { field ->
                field.substringBefore('/').takeIf { it != field }?.let { condition.references(it) } == true
            }
            ?: error("no $ruleGroup field or group is referenced by catalog example: $condition")
    }

    private fun String.references(pathOrName: String): Boolean =
        Regex("(?<![A-Za-z0-9_])${Regex.escape(pathOrName)}(?![A-Za-z0-9_])").containsMatchIn(this)

    companion object {
        /** Consumer-SPI operators whose generated-Java checks live in their dedicated differential. */
        val SPECIALISTS: Set<String> = setOf("CustomCondition", "ValidType", "InvalidType")
        val MULTI_STAR_IDS: Set<String> = setOf("Having", "CurrentRepetition")

        fun evidenceCode(operatorId: String): String = "EVIDENCE_$operatorId"
    }
}
