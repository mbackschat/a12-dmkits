package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Decisive RNU evidence: typed key equality and rich duplicate-cluster messages, compared against
 * Groovy-dynamic and static-Java. The portable sibling test repeats the implementation cases on JVM and Node.
 */
class RepetitionNotUniqueExactnessDiffTest {

    private data class Item(val sku: String, val count: String?, val weight: String?)

    private data class Signature(
        val field: String,
        val type: String,
        val referenced: Set<String>,
        val fillToFix: Set<String>,
    )

    private class Scenario(
        val kernelModel: String,
        val kernelDocument: RuntimeLaws.Doc,
        val model: ModelDef,
        val document: Document,
    )

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val bound = BoundModel.ofJson(order)
    private val fields = listOf(
        FieldDef(SKU, Kind.STRING),
        FieldDef(COUNT, Kind.NUMBER),
        FieldDef(WEIGHT, Kind.NUMBER),
    )

    private fun render(condition: Condition): String = RuntimeLaws.render(condition)

    private fun scenario(condition: String, errorRel: String, items: List<Item>): Scenario {
        val kernelModel = RuntimeLaws.withRule(order, "/Order", "RU", errorRel, "RU", condition)
        val model = ModelDef(
            fields,
            listOf(RuleDef("/Order/RU", "/Order", condition, "RU", errorRel)),
            repeatableGroups = setOf("/Order/Items"),
            declaredReps = mapOf("/Order/Items" to 50),
            indexFields = setOf(SKU),
        )
        val kernelDocument = RuntimeLaws.doc().group("/Order", 1)
        val document = model.newDocument().group("/Order", 1)
        items.forEachIndexed { index, item ->
            val row = index + 1
            kernelDocument.group("/Order/Items", 1, row).field(SKU, item.sku, 1, row, 1)
            document.group("/Order/Items", 1, row).field(SKU, item.sku, 1, row, 1)
            item.count?.let {
                kernelDocument.field(COUNT, it, 1, row, 1)
                document.field(COUNT, it, 1, row, 1)
            }
            item.weight?.let {
                kernelDocument.field(WEIGHT, it, 1, row, 1)
                document.field(WEIGHT, it, 1, row, 1)
            }
        }
        return Scenario(kernelModel, kernelDocument, model, document)
    }

    private fun RuntimeLaws.Msg.signature() =
        Signature(field(), type().name, referenced().toSet(), omissionResponsible().toSet())

    private fun Message.signature() =
        Signature(errorPath, "${type.name}_ERROR", referenced.toSet(), fillToFix.toSet())

    private fun agreeTri(condition: String, errorRel: String, items: List<Item>, reason: String) {
        val scenario = scenario(condition, errorRel, items)
        val dynamic = RuntimeLaws.messages(scenario.kernelModel, scenario.kernelDocument)
            .filter { it.code() == "RU" }.map { it.signature() }.sortedBy { it.field }
        val staticEngine = RuntimeLaws.kernelJavaEngine(scenario.kernelModel)
        val static = staticEngine.messages(staticEngine.instance(scenario.kernelDocument))
            .filter { it.code() == "RU" }.map { it.signature() }.sortedBy { it.field }
        val interpreted = DmInterpreter(scenario.model).validateFull(scenario.document)
            .filter { it.code == "RU" }.map { it.signature() }.sortedBy { it.field }

        assertEquals(dynamic, interpreted, "$reason (interpreter vs Groovy-dynamic)")
        assertEquals(dynamic, static, "$reason (static-Java vs Groovy-dynamic)")
    }

    private fun agreePartialTri(
        condition: String,
        items: List<Item>,
        kernelRelevant: Set<RuntimeLaws.Relevant>,
        interpreterRelevant: Set<RelevantEntity>,
        expectedCount: Int,
        reason: String,
    ) {
        val scenario = scenario(condition, "../Items/Count", items)
        val dynamic = RuntimeLaws.messagesPart(scenario.kernelModel, scenario.kernelDocument, kernelRelevant)
            .filter { it.code() == "RU" }.map { it.signature() }.sortedBy { it.field }
        val staticEngine = RuntimeLaws.kernelJavaEngine(scenario.kernelModel)
        val static = staticEngine.messagesPart(scenario.kernelDocument, kernelRelevant)
            .filter { it.code() == "RU" }.map { it.signature() }.sortedBy { it.field }
        val interpreted = DmInterpreter(scenario.model).validatePart(scenario.document, interpreterRelevant).messages
            .filter { it.code == "RU" }.map { it.signature() }.sortedBy { it.field }

        assertEquals(expectedCount, dynamic.size, "$reason (decisive kernel outcome)")
        assertEquals(dynamic, interpreted, "$reason (interpreter vs Groovy-dynamic)")
        assertEquals(dynamic, static, "$reason (static-Java vs Groovy-dynamic)")
    }

    @Test
    fun numericKeySpellingsCompareByValueAcrossBothStrategies() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(WEIGHT)))
        agreeTri(
            condition,
            "../Items/UnitWeight",
            listOf(Item("K1", "1", "5"), Item("K2", "2", "5.00")),
            "5 and 5.00 are one numeric duplicate key",
        )
    }

    @Test
    fun mixedCompositeNumericSpellingsCompareByValueAcrossBothStrategies() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(COUNT), bound.field(WEIGHT)))
        agreeTri(
            condition,
            "../Items/Count",
            listOf(Item("K1", "7", "5"), Item("K2", "7", "5.00")),
            "composite numeric atoms use value equality component-wise",
        )
    }

    @Test
    fun textKeySpellingsRemainExactAcrossBothStrategies() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(SKU)))
        agreeTri(
            condition,
            "../Items/Sku",
            listOf(Item("5", "1", null), Item("5.00", "2", null)),
            "text keys preserve exact spelling",
        )
    }

    @Test
    fun valueDuplicateClusterProjectsEveryPeerKey() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(COUNT), bound.field(WEIGHT)))
        agreeTri(
            condition,
            "../Items/Count",
            List(3) { index -> Item("K${index + 1}", "7", "5") },
            "three-row value cluster carries all peer key pointers",
        )
    }

    @Test
    fun omissionDuplicateClusterProjectsEveryPeerKey() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(COUNT), bound.field(WEIGHT)))
        agreeTri(
            condition,
            "../Items/Count",
            List(3) { index -> Item("K${index + 1}", "7", null) },
            "three-row omission cluster carries all peer key pointers and fill-to-fix pointers",
        )
    }

    @Test
    fun composedBranchKeepsOrdinaryReferencesOnTheCurrentRow() {
        val rnu = Dsl.repetitionNotUnique(bound.field(COUNT))
        val condition = render(rnu.or(bound.num(WEIGHT).gt(100)))
        agreeTri(
            condition,
            "../Items/Count",
            listOf(Item("K1", "5", "1"), Item("K2", "5", "1"), Item("K3", "9", "200")),
            "RNU refs expand over duplicate peers while the independent Or branch stays current-row",
        )
    }

    @Test
    fun everyCompositeKeyMustBeRelevantInBothKernelStrategies() {
        val condition = render(Dsl.repetitionNotUnique(bound.field(COUNT), bound.field(WEIGHT)))
        val items = listOf(Item("K1", "5", "7"), Item("K2", "5", "7"))

        agreePartialTri(
            condition,
            items,
            setOf(RuntimeLaws.Relevant.of(COUNT, 0, 0, 0), RuntimeLaws.Relevant.of(WEIGHT, 0, 0, 0)),
            setOf(RelevantEntity.anyRep(COUNT), RelevantEntity.anyRep(WEIGHT)),
            expectedCount = 2,
            "all composite-key cells relevant",
        )
        agreePartialTri(
            condition,
            items,
            setOf(RuntimeLaws.Relevant.of(COUNT, 0, 0, 0)),
            setOf(RelevantEntity.anyRep(COUNT)),
            expectedCount = 0,
            "nonrelevant weight excludes each RNU participant",
        )
    }

    @Test
    fun aRelevantTrueOrBranchStillFiresWhenOneRnuKeyIsNonrelevant() {
        val rnu = Dsl.repetitionNotUnique(bound.field(COUNT), bound.field(WEIGHT))
        val condition = render(rnu.or(bound.num(COUNT).gt(0)))
        val countOnlyKernel = setOf(RuntimeLaws.Relevant.of(COUNT, 0, 0, 0))
        val countOnlyInterpreter = setOf(RelevantEntity.anyRep(COUNT))

        agreePartialTri(
            condition,
            listOf(Item("K1", "5", "7"), Item("K2", "5", "7")),
            countOnlyKernel,
            countOnlyInterpreter,
            expectedCount = 2,
            "the relevant true branch remains decisive",
        )
    }

    private companion object {
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
        const val WEIGHT = "/Order/Items/UnitWeight"
    }
}
