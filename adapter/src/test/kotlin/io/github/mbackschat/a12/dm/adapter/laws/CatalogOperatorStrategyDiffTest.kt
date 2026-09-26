package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
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

    @Test
    fun everyOrdinaryCatalogOperatorRunsThroughBothKernelStrategies() {
        val entries = catalog.entries().filter { it.validExample() != null && it.id() !in SPECIALISTS }
        assertStrategyEvidence(CatalogHosting.order, entries.filter { it.id() !in MULTI_STAR_IDS })
        assertStrategyEvidence(CatalogHosting.warehouse, entries.filter { it.id() in MULTI_STAR_IDS })
    }

    private fun assertStrategyEvidence(fixture: CatalogHosting.Fixture, entries: List<OperatorEntry>) {
        val ruleGroup = fixture.ruleGroup
        val model = entries.fold(fixture.json) { json, entry ->
            val condition = entry.validExample().dsl()
            RuntimeLaws.withRule(
                json,
                ruleGroup,
                "StrategyEvidence_${entry.id()}",
                "../${fixture.errorFieldOf(condition)}",
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
        // Computation-ONLY means exactly that: no rule-context example, so this lane is the only one covering it.
        // Keying on `computationExample() != null` alone conflated the lane with the EVIDENCE — an operator may
        // legitimately carry both, and seven of them now do (the kf167 numeric wrappers and time extractors gained
        // computation examples so their MVK_NO_CONST boundary is executed in both consumer contexts). That made this
        // partition claim false while every lane's actual coverage was intact: the premise "a computation example
        // implies computation-only" was true when written and silently stopped being true.
        val computationOnly = catalog.entries()
            .filter { it.computationExample() != null && it.validExample() == null }
            .map { it.id() }
            .toSet()

        assertEquals(SPECIALISTS, operatorIds intersect SPECIALISTS)
        assertEquals(operatorIds, ordinary + SPECIALISTS + computationOnly)
        assertTrue((ordinary intersect SPECIALISTS).isEmpty())
        assertTrue((ordinary intersect computationOnly).isEmpty())
        assertTrue((SPECIALISTS intersect computationOnly).isEmpty())
    }

    companion object {
        val SPECIALISTS: Set<String> = CatalogHosting.SPECIALISTS
        val MULTI_STAR_IDS: Set<String> = CatalogHosting.MULTI_STAR_IDS

        fun evidenceCode(operatorId: String): String = "EVIDENCE_$operatorId"
    }
}
