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
 * Multiplatform (JVM + JS) lock for IF104 — a fired comparison consuming a `Having`-FILTERED star is
 * escalated to OMISSION unconditionally (the kernel combiners' `getValue(containsFilter)` marking; the
 * comparison twin of IF101's quantifier escalation): when the filter drops every row AND when it keeps a
 * filled row. Kernel-verified by the tri-check differential `adapter.laws.HavingStarComparisonPolarityDiffTest`.
 */
class HavingStarComparisonPolarityTest {

    private val GATE = "/Fleet/Tag"
    private val W = "/Fleet/Leases/WeeklyHours"
    private val FLAG = "/Fleet/Leases/Kind"

    private val def = ModelDef(
        fields = listOf(FieldDef(GATE, Kind.STRING), FieldDef(W, Kind.NUMBER, scale = 0), FieldDef(FLAG, Kind.STRING)),
        rules = listOf(RuleDef("/Fleet/R", "/Fleet",
            "FieldFilled(Tag) And FirstFilledValue(/Fleet/Leases*/WeeklyHours Having [/Fleet/Leases/Kind] == \"main\") != 40",
            "HRS", "../Tag")),
        computations = emptyList(),
        repeatableGroups = setOf("/Fleet/Leases"),
    )

    private fun firedType(rows: List<Pair<String?, String>>): MsgType? {
        val doc = def.newDocument().group("/Fleet", 1).field(GATE, "t", 1, 1)
        rows.forEachIndexed { i, (weekly, kind) ->
            doc.group("/Fleet/Leases", 1, i + 1).field(FLAG, kind, 1, i + 1, 1)
            weekly?.let { doc.field(W, it, 1, i + 1, 1) }
        }
        return DmInterpreter(def).validateFull(doc).firstOrNull { it.code == "HRS" }?.type
    }

    @Test fun filterDroppedAllRowsFiresOmission() =
        assertEquals(MsgType.OMISSION, firedType(listOf("50" to "backup")),
            "the only lease is filtered away — the empty-selection comparison fires TRUE_AF")

    @Test fun keptFilledRowFiresOmissionToo() =
        assertEquals(MsgType.OMISSION, firedType(listOf("50" to "main")),
            "a kept filled row disagrees (50 != 40) — the escalation is unconditional")

    @Test fun silentWhenTheKeptValueMatches() =
        assertEquals(null, firedType(listOf("40" to "main")))
}
