package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF101 — a fired fill quantifier over a sole `Having`-FILTERED field star is
 * UNCONDITIONALLY `TRUE_AF` → OMISSION (the fill-quantifier twin of the §8 value-list escalation rule),
 * in BOTH directions: `NoFieldFilled(star Having …)` fired with nothing kept, AND
 * `AtLeastOneFieldFilled(star Having …)` fired over a fully-kept FILLED row. Kernel-verified by the tri-check
 * differential `adapter.laws.HavingQuantifierPolarityDiffTest`.
 */
class HavingQuantifierPolarityTest {

    private val NOTE = "/Shop/Note"
    private val PRICE = "/Shop/Items/Price"
    private val CATEGORY = "/Shop/Items/Category"

    private fun model(cond: String) = ModelDef(
        fields = listOf(FieldDef(NOTE, Kind.STRING), FieldDef(PRICE, Kind.NUMBER, scale = 2),
            FieldDef(CATEGORY, Kind.STRING)),
        rules = listOf(RuleDef("/Shop/R", "/Shop", cond, "HOTQ", "../Note")),
        computations = emptyList(),
        repeatableGroups = setOf("/Shop/Items"),
    )

    private fun firedType(cond: String, rows: List<Pair<String?, String>>): MsgType? {
        val def = model(cond)
        val doc = def.newDocument().group("/Shop", 1).field(NOTE, "n", 1, 1)
        rows.forEachIndexed { idx, (price, cat) ->
            val r = idx + 1
            doc.group("/Shop/Items", 1, r).field(CATEGORY, cat, 1, r, 1)
            price?.let { doc.field(PRICE, it, 1, r, 1) }
        }
        return DmInterpreter(def).validateFull(doc).firstOrNull { it.code == "HOTQ" }?.type
    }

    private val noHot = "FieldFilled(Note) And NoFieldFilled(/Shop/Items*/Price Having [/Shop/Items/Category] == \"hot\")"
    private val someHot = "FieldFilled(Note) And AtLeastOneFieldFilled(/Shop/Items*/Price Having [/Shop/Items/Category] == \"hot\")"

    @Test fun firedNoFieldFilledWithHavingIsOmission() =
        assertEquals(MsgType.OMISSION, firedType(noHot, listOf("10.00" to "basic")),
            "nothing kept by the filter — fired TRUE_AF, not a kept-rows VALUE")

    @Test fun firedAtLeastOneWithHavingIsOmissionToo() =
        assertEquals(MsgType.OMISSION, firedType(someHot, listOf("10.00" to "hot")),
            "the escalation is unconditional — even a fully-kept filled row fires OMISSION")

    @Test fun silentControl() =
        assertEquals(null, firedType(noHot, listOf("10.00" to "hot")))
}
