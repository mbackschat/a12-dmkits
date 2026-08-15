package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/** Partial-validation differential for the kernel's rule-level `Having` skip. */
class PartialValidationFilterSkipDiffTest {

    private val json = DocumentModelDraft.named("partial-filter-skip").locale("en_US")
        .rootGroup("Order", Consumer { root: GroupBuilder ->
            root.group("Items", Consumer<GroupBuilder> { items ->
                items.repeatable(3).numberField("Count", Consumer { it.fractionDigits(0) })
            })
        })
        .build().toJson()
    private val session = EditSession.read(json)
    private val order = session.group("/Order")
    private val items = session.group("/Order/Items")
    private val count = items.num("Count")
    private val selfExcluded = Dsl.currentRepetition(items.ref()).ne(Dsl.currentRepetition(items.ref(), true))
    private val rules = listOf(
        correlationRule("EQ", count.eq(count.outer())),
        correlationRule("LT", count.lt(count.outer())),
    )
    private val kernelModel = rules.fold(json) { model, rule -> TypedRules.inject(model, rule) }
    private val interpreterModel = ModelDef(
        fields = listOf(FieldDef(count.field().path(), Kind.NUMBER, scale = 0)),
        rules = rules.map(TypedRules::def),
        repeatableGroups = setOf(items.ref().path().value()),
        declaredReps = mapOf(items.ref().path().value() to 3),
    )

    @Test
    fun partialValidationSkipsHavingRulesEvenWhenEveryFieldInstanceIsRelevant() {
        assertFullAndPartial(listOf("5", "5"), setOf("EQ"))
        assertFullAndPartial(listOf("6", "5"), setOf("LT"))
    }

    private fun correlationRule(code: String, comparison: Condition): Rule =
        order.rule {
            it.name("R_$code").code(code).errorField(count.field()).`when`(
                count.filled().and(
                    Dsl.atLeastOneFieldFilled(
                        order.flatten("Items*/Count").having(selfExcluded.and(comparison)),
                    ),
                ),
            ).severity(Severity.ERROR).message(Dsl.en("x")).build()
        }

    private fun assertFullAndPartial(values: List<String>, fullExpected: Set<String>) {
        val kernelDocument = kernelDocument(values)
        val interpreterDocument = interpreterDocument(values)
        val kernelFull = RuntimeLaws.messages(kernelModel, kernelDocument)
            .map { it.code() }.filterTo(sortedSetOf()) { it in CODES }
        val interpreterFull = DmInterpreter(interpreterModel).validateFull(interpreterDocument)
            .map { it.code }.filterTo(sortedSetOf()) { it in CODES }
        assertEquals(fullExpected, kernelFull, "kernel full characterization")
        assertEquals(kernelFull, interpreterFull, "full values=$values")

        val kernelRelevant = setOf(
            Relevant.of(count.field().path(), RelevantEntity.ALL, RelevantEntity.ALL, RelevantEntity.ALL),
        )
        val interpreterRelevant = setOf(RelevantEntity.anyRep(count.field().path()))
        val kernelPartial = RuntimeLaws.messagesPart(kernelModel, kernelDocument, kernelRelevant)
            .map { it.code() }.filterTo(sortedSetOf()) { it in CODES }
        val interpreterPartial = DmInterpreter(interpreterModel).validatePart(interpreterDocument, interpreterRelevant)
            .messages.map { it.code }.filterTo(sortedSetOf()) { it in CODES }
        assertEquals(emptySet<String>(), kernelPartial, "kernel partial characterization")
        assertEquals(kernelPartial, interpreterPartial, "partial values=$values")
    }

    private fun kernelDocument(values: List<String>): RuntimeLaws.Doc {
        val document = RuntimeLaws.doc().group(order.ref().path().value(), 1)
        values.forEachIndexed { index, value ->
            val row = index + 1
            document.group(items.ref().path().value(), 1, row).field(count.field().path(), value, 1, row, 1)
        }
        return document
    }

    private fun interpreterDocument(values: List<String>) = interpreterModel.newDocument().also { document ->
        document.group(order.ref().path().value(), 1)
        values.forEachIndexed { index, value ->
            val row = index + 1
            document.group(items.ref().path().value(), 1, row).field(count.field().path(), value, 1, row, 1)
        }
    }

    private companion object {
        val CODES = setOf("EQ", "LT")
    }
}
