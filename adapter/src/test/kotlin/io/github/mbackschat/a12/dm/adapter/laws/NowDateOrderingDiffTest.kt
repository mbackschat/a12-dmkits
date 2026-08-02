package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

/** Tri-engine lock for the kernel-legal DATE-vs-DATE_TIME ordering domain. */
class NowDateOrderingDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")
    private val model = BoundModel.ofJson(base)
    private val modelJson = listOf(
        rule(model, BEFORE, model.date(FIELD).before(Dsl.now())),
        rule(model, AFTER, model.date(FIELD).after(Dsl.now())),
    ).fold(base, TypedRules::inject)
    private val java by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }
    private val definition by lazy { ModelLoader.load(modelJson) }

    @Test
    fun aPlainDateOrdersAgainstNowOnTheCommonTimeline() {
        agree("2025-06-22", setOf(BEFORE))
        agree("2025-06-23", setOf(BEFORE))
        agree("2025-06-24", setOf(AFTER))
    }

    private fun agree(value: String, expected: Set<String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1).field(FIELD, value, 1, 1)
        val clock = Date.from(Instant.parse("2025-06-23T10:00:00Z"))

        val dynamic = RuntimeLaws.messagesModern(modelJson, kernelDocument, clock).map { it.code() }.toSet()
        val generated = java.messagesModern(kernelDocument, clock).map { it.code() }.toSet()

        val document = definition.newDocument().group(ROOT, 1).field(FIELD, value, 1, 1)
        val interpreted = DmInterpreter(
            definition,
            EvalClock(today = "2025-06-23", now = "2025-06-23T12:00:00"),
        ).validateFull(document).map { it.code }.toSet()

        assertEquals(expected, dynamic, "normative dynamic-Groovy ($value)")
        assertEquals(dynamic, generated, "generated Java ($value)")
        assertEquals(dynamic, interpreted, "interpreter ($value)")
    }

    private fun rule(model: BoundModel, code: String, condition: Condition): Rule =
        model.rule(model.group(ROOT))
            .name(code)
            .errorField(model.date(FIELD))
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("Cross-kind temporal ordering probe."))
            .build()

    private companion object {
        const val ROOT = "/Order"
        const val FIELD = "/Order/OrderDate"
        const val BEFORE = "DateBeforeNow"
        const val AFTER = "DateAfterNow"
    }
}
