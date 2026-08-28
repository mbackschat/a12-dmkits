package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `To`/`Zu` infix through [DmInterpreter.validate] rather than the bare parser: a rule using
 * `ValueNotConsistent`'s `field To value, …` shape degrades like any other unmodeled construct
 * ([UnsupportedDegradeTest]) — named in `outcome.unsupported`, not thrown out of `validate`, and not
 * taking the rest of the model's rules down with it.
 */
class ToInfixDegradeTest {

    private val status = "/Order/Status"
    private val category = "/Order/Category"
    private val region = "/Order/Region"
    private val channel = "/Order/Channel"
    private val customerName = "/Order/CustomerName"

    private val model = ModelDef(
        conditionLanguage = ConditionLanguage.EN,
        fields = listOf(
            FieldDef(status, Kind.STRING), FieldDef(category, Kind.STRING),
            FieldDef(region, Kind.STRING), FieldDef(channel, Kind.STRING),
            FieldDef(customerName, Kind.STRING),
        ),
        rules = listOf(
            RuleDef("/Order/R1", "/Order",
                "ValueNotConsistent(Status To Category, Region, Channel)", "R1", "../Status"),
            // a healthy rule that must still evaluate (and fire) despite R1's degrade
            RuleDef("/Order/R2", "/Order", "FieldFilled(CustomerName)", "R2", "../CustomerName"),
        ),
    )

    @Test
    fun theUnmodeledConstructDegradesWithoutSinkingTheRun() {
        val doc = model.newDocument().group("/Order", 1).field(customerName, "Acme", 1, 1)
        val outcome = DmInterpreter(model).validate(doc)   // must not throw
        assertEquals(listOf("R2"), outcome.messages.map { it.code }, "the healthy rule still evaluates")
        assertTrue(
            outcome.unsupported.any { it.rulePath == "/Order/R1" && "ValueNotConsistent" in it.reason },
            "R1 is reported unsupported naming the construct: ${outcome.unsupported}",
        )
    }
}
