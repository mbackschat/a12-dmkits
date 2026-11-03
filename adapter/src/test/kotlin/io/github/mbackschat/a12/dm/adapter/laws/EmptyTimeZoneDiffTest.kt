package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which zone a model with an EMPTY `timeZone` evaluates under: the absent one's, on the kernel and on the interpreter
 * (KERNEL-FINDINGS KF658).
 *
 * Kernel 31.1.1 admits `"timeZone": ""` where it refuses a blank zone, because its expand copy omits the empty
 * string. That is an admission fact; which zone the runtime then uses is a separate claim, so it is measured here
 * on one discriminating rule: across the Europe/Berlin spring-forward night, `01:30` to `03:30` is one real hour in
 * Berlin and two wherever there is no daylight saving. The Berlin row is the control that the rule can tell the
 * zones apart; the interpreter reads each model through its own loader, so the row covers the loader too.
 */
class EmptyTimeZoneDiffTest {

    @Test
    fun anEmptyZoneEvaluatesAsAnAbsentOne() {
        val zones = linkedMapOf(
            "Europe/Berlin — the control" to "\"timeZone\": \"Europe/Berlin\",",
            "UTC" to "\"timeZone\": \"UTC\",",
            "absent" to "",
            "empty" to "\"timeZone\": \"\",",
        )
        val observed = zones.mapValues { (_, member) ->
            val model = RuntimeLaws.withRule(
                BERLIN.replace("\"timeZone\": \"Europe/Berlin\",", member), "/Order", "R", "../ScheduledAt", CODE,
                "DifferenceInHours(ScheduledAt, CompletedAt) == 2",
            )
            val kernel = RuntimeLaws.fires(model, CODE,
                RuntimeLaws.doc().group("/Order", 1).field(SA, START, 1, 1).field(CA, END, 1, 1))
            val definition = ModelLoader.load(model)
            val interpreter = DmInterpreter(definition)
                .validateFull(definition.newDocument().field(SA, START, 1, 1).field(CA, END, 1, 1), "en_US")
                .any { it.code == CODE }
            kernel to interpreter
        }
        assertEquals(
            linkedMapOf(
                "Europe/Berlin — the control" to (false to false),
                "UTC" to (true to true),
                "absent" to (true to true),
                "empty" to (true to true),
            ),
            observed,
            "(zone → kernel fires, interpreter fires) for two wall hours across the spring-forward night",
        )
    }

    private companion object {
        const val CODE = "TWO_HOURS"
        const val SA = "/Order/ScheduledAt"
        const val CA = "/Order/CompletedAt"
        const val START = "2024-03-31T01:30:00"
        const val END = "2024-03-31T03:30:00"
        val BERLIN: String = RuntimeLaws.model("models/order.dm.json")
    }
}
