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

/** Tri-engine lock for `Now` retaining the exact millisecond identity of the injected validation clock. */
class NowMillisecondIdentityDiffTest {

    @Test
    fun exactIdentityDifferencesAndSubDayShiftAgree() {
        val base = RuntimeLaws.model("models/order.dm.json")
        val model = BoundModel.ofJson(base)
        val rules = listOf(
            rule(model, "EqualRenderedSecond", model.dateTime(FIELD).eq(Dsl.now())),
            rule(model, "BeforeExactNow", model.dateTime(FIELD).before(Dsl.now())),
            rule(model, "ForwardResidual", Dsl.differenceInSeconds(model.dateTime(FIELD), Dsl.now()).eq(0)),
            rule(model, "ReverseResidual", Dsl.differenceInSeconds(Dsl.now(), model.dateTime(FIELD)).eq(0)),
            rule(model, "OneSecondShift", model.dateTime(FIELD).filled().and(
                Dsl.differenceInSeconds(Dsl.now(), Dsl.addSeconds(Dsl.now(), 1)).eq(1),
            )),
            rule(model, "ShiftedBefore", model.dateTime(FIELD).before(Dsl.addSeconds(Dsl.now(), 1))),
            // SPEC-2026-07-29-01's millisecond arm: a DAY shift must carry the source instant's sub-second
            // remainder, not just its clock. CompletedAt is the exact second one day out, so it is BEFORE the
            // shifted Now iff the .999 survived, and EQUAL to it iff the remainder was dropped.
            rule(model, "DayShiftKeepsMillis", model.dateTime(FIELD).filled()
                .and(model.dateTime(COMPLETED).before(Dsl.addDays(Dsl.now(), 1)))),
            rule(model, "DayShiftDroppedMillis", model.dateTime(FIELD).filled()
                .and(model.dateTime(COMPLETED).eq(Dsl.addDays(Dsl.now(), 1)))),
        )
        val modelJson = rules.fold(base, TypedRules::inject)
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(FIELD, "2025-06-23T12:00:00", 1, 1)
            .field(COMPLETED, "2025-06-24T12:00:00", 1, 1)
        val clock = Date.from(Instant.parse("2025-06-23T10:00:00.999Z"))

        val dynamic = RuntimeLaws.messagesModern(modelJson, kernelDocument, clock).map { it.code() }.toSet()
        val java = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = java.messagesModern(kernelDocument, clock).map { it.code() }.toSet()

        val definition = ModelLoader.load(modelJson)
        val interpreterDocument = definition.newDocument().group(ROOT, 1)
            .field(FIELD, "2025-06-23T12:00:00", 1, 1)
            .field(COMPLETED, "2025-06-24T12:00:00", 1, 1)
        val interpreted = DmInterpreter(
            definition,
            EvalClock(today = "2025-06-23", now = "2025-06-23T12:00:00.999"),
        ).validateFull(interpreterDocument).map { it.code }.toSet()

        val expected = setOf("BeforeExactNow", "ForwardResidual", "ReverseResidual", "OneSecondShift",
            "ShiftedBefore", "DayShiftKeepsMillis")
        assertEquals(expected, dynamic, "normative dynamic-Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun rule(model: BoundModel, code: String, condition: Condition): Rule =
        model.rule(model.group(ROOT))
            .name(code)
            .errorField(model.dateTime(FIELD))
            .`when`(condition)
            .code(code)
            .severity(Severity.ERROR)
            .message(Dsl.en("Clock identity probe."))
            .build()

    private companion object {
        const val ROOT = "/Order"
        const val FIELD = "/Order/ScheduledAt"
        const val COMPLETED = "/Order/CompletedAt"
    }
}
