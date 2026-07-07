package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF68: a group-count quantifier over a STARRED repeatable group counts the
 * group's INSTANTIATED rows (a created row is content — `t = n = rows`, `f = 0`), and its polarity is fixed by
 * ADDING a row (`NoGroupFilled(G*)` at zero rows = OMISSION; `AtLeastOneGroupFilled(G*)` = VALUE). The kernel
 * differential (both strategies) is `adapter.laws.StarredGroupQuantifierDiffTest`.
 */
class StarredGroupQuantifierTest {

    // /Enrollment/Choice + a repeatable /Enrollment/Active group; a rule over /Enrollment referencing Choice.
    private fun model(cond: String) = ModelDef(
        fields = listOf(FieldDef("/Enrollment/Choice", Kind.STRING), FieldDef("/Enrollment/Active/Item", Kind.STRING)),
        rules = listOf(RuleDef("/Enrollment/R", "/Enrollment", cond, "SEL", "../Choice")),
        repeatableGroups = setOf("/Enrollment/Active"),
        declaredReps = mapOf("/Enrollment/Active" to 10),
    )

    /** rows >0 = filled rows; <0 = |rows| instantiated-empty rows. */
    private fun doc(m: ModelDef, rows: Int): Document = m.newDocument().field("/Enrollment/Choice", "Waived", 1, 1).also { d ->
        for (r in 1..kotlin.math.abs(rows)) {
            d.group("/Enrollment/Active", 1, r)
            if (rows > 0) d.field("/Enrollment/Active/Item", "v$r", 1, r, 1)
        }
    }

    private fun sig(cond: String, rows: Int): String? {
        val m = model(cond)
        return DmInterpreter(m).validateFull(doc(m, rows)).firstOrNull { it.code == "SEL" }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
    }

    @Test fun noGroupFilledZeroRowsFiresOmission() = assertEquals("OMISSION", sig("NoGroupFilled(Active*)", 0), "0 rows → fires OMISSION")
    @Test fun noGroupFilledFilledRowNoFire() = assertEquals(null, sig("NoGroupFilled(Active*)", 1), "a filled row → no fire")
    @Test fun noGroupFilledInstantiatedEmptyNoFire() = assertEquals(null, sig("NoGroupFilled(Active*)", -1), "an instantiated-empty row counts → no fire")
    @Test fun atLeastOneFilledFiresValue() = assertEquals("VALUE", sig("AtLeastOneGroupFilled(Active*)", 1), "a filled row → fires VALUE")
    @Test fun atLeastOneInstantiatedEmptyFiresValue() = assertEquals("VALUE", sig("AtLeastOneGroupFilled(Active*)", -1), "an instantiated-empty row counts → fires VALUE")
    @Test fun atLeastOneZeroRowsNoFire() = assertEquals(null, sig("AtLeastOneGroupFilled(Active*)", 0), "0 rows → no fire")
}
