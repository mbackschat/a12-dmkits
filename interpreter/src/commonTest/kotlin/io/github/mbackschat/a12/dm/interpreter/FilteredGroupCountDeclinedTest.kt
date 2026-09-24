package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `Having` on a `NumberOfFilledGroups` operand is declined by the count's value, not only by the polarity walk
 * that types a fired rule. A12 refuses the shape at either operand position (`MVK_NO_GROUPS_ALLOWED`,
 * `validate.laws.GroupOperandFilterLawsTest`), so only a model that never passed consistency carries it, and no
 * measured count exists to match.
 *
 * The rung is `== 1` over a document with no `Items` row, so it does not fire whether or not the filter is read:
 * the polarity walk never runs, and only the value's own refusal can report the rule.
 */
class FilteredGroupCountDeclinedTest {

    private val customer = "/Order/CustomerName"

    private val model = ModelDef(
        fields = listOf(FieldDef(customer, Kind.STRING), FieldDef("/Order/Items/Tag", Kind.STRING)),
        rules = listOf(
            RuleDef("/Order/R1", "/Order",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items* Having [Items/Tag] == \"Y\") == 1",
                "R1", "../CustomerName"),
        ),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 5),
    )

    @Test
    fun aFilteredGroupCountThatDoesNotFireIsStillDeclined() {
        val outcome = DmInterpreter(model).validate(model.newDocument().group("/Order", 1).field(customer, "Acme", 1, 1))
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/R1" && "Having" in it.reason },
            "the value path declines the filter by name: ${outcome.unsupported}")
    }
}
