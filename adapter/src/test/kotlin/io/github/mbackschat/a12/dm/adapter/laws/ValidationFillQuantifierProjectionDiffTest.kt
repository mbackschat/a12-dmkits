package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-20-13 differential: validation projects unavailable operands into the extensional fill tally
 * without counting them as filled or empty. Once a sufficient witness exists, the quantifier still fires with
 * its exact polarity; an unavailable-only tally remains observably silent. Every rule and condition is authored
 * through modelkit/rulekit and tri-checked against Groovy-dynamic, generated Java, and the interpreter.
 */
class ValidationFillQuantifierProjectionDiffTest {

    private val baseJson = DocumentModelDraft.named("projection").locale("en_US")
        .rootGroup("Projection") { root ->
            root.numberField("Anchor") { it.fractionDigits(0) }
            root.numberField("First") { it.fractionDigits(0) }
            root.numberField("Second") { it.fractionDigits(0) }
            root.numberField("Third") { it.fractionDigits(0) }
        }
        .build()
        .toJson()
    private val model = BoundModel.ofJson(baseJson)
    private val first = model.field(FIRST)
    private val second = model.field(SECOND)
    private val third = model.field(THIRD)
    private val rules = listOf(
        rule("MoreThanOne", MORE_THAN_ONE, Dsl.moreThanOneFieldFilled(first, second, third)),
        rule("NotExactlyOne", NOT_EXACTLY_ONE, Dsl.notExactlyOneFieldFilled(first, second, third)),
        rule("NotCollectively", NOT_COLLECTIVELY, Dsl.fieldsNotCollectivelyFilled(first, second, third)),
        rule("NoneFilled", NONE_FILLED, Dsl.noFieldFilled(first, second, third)),
    )
    private val modelJson = rules.fold(baseJson, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test
    fun twoFilledAndOneUnavailableRemainSufficientValueWitnesses() {
        agree(
            mapOf(FIRST to "10", SECOND to "20", THIRD to "invalid"),
            listOf("$MORE_THAN_ONE|VALUE_ERROR", "$NOT_EXACTLY_ONE|VALUE_ERROR"),
        )
    }

    @Test
    fun filledEmptyAndUnavailableRemainACollectiveOmissionWitness() {
        agree(
            mapOf(FIRST to "10", SECOND to null, THIRD to "invalid"),
            listOf("$NOT_COLLECTIVELY|OMISSION_ERROR"),
        )
    }

    @Test
    fun unavailableOnlyTallyCollapsesToSilence() {
        agree(
            mapOf(FIRST to "invalid-a", SECOND to "invalid-b", THIRD to "invalid-c"),
            emptyList(),
        )
    }

    private fun rule(name: String, code: String, condition: Condition): Rule =
        model.rule(model.group(ROOT)).name(name)
            .code(code)
            .errorField(first)
            .`when`(condition)
            .severity(Severity.ERROR)
            .message(Dsl.en(code))
            .build()

    private fun agree(values: Map<String, String?>, expected: List<String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(ANCHOR, "1", 1, 1)
        values.forEach { (path, value) ->
            if (value == null) kernelDocument.emptyField(path, 1, 1)
            else kernelDocument.field(path, value, 1, 1)
        }
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument)
            .filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }
            .sorted()
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument))
            .filter { it.code() in CODES }
            .map { "${it.code()}|${it.type().name}" }
            .sorted()

        val interpreterDocument = definition.newDocument().group(ROOT, 1).field(ANCHOR, "1", 1, 1)
        values.forEach { (path, value) ->
            if (value == null) interpreterDocument.emptyField(path, 1, 1)
            else interpreterDocument.field(path, value, 1, 1)
        }
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument)
            .filter { it.code in CODES }
            .map {
                val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
                "${it.code}|$type"
            }
            .sorted()

        assertEquals(expected, dynamic, "normative Groovy-dynamic projection")
        assertEquals(dynamic, generated, "generated Java projection")
        assertEquals(dynamic, interpreted, "interpreter projection")
    }

    private companion object {
        const val ROOT = "/Projection"
        const val ANCHOR = "$ROOT/Anchor"
        const val FIRST = "$ROOT/First"
        const val SECOND = "$ROOT/Second"
        const val THIRD = "$ROOT/Third"
        const val MORE_THAN_ONE = "MORE_THAN_ONE"
        const val NOT_EXACTLY_ONE = "NOT_EXACTLY_ONE"
        const val NOT_COLLECTIVELY = "NOT_COLLECTIVELY"
        const val NONE_FILLED = "NONE_FILLED"
        val CODES = setOf(MORE_THAN_ONE, NOT_EXACTLY_ONE, NOT_COLLECTIVELY, NONE_FILLED)
    }
}
