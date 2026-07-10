package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the CROSS-SUBTREE group-star binding — the group-star sibling of IF93's
 * anchor rule: iterating a starred group's rows from an enclosing row binds the row's indices only along the
 * SHARED named ancestry (`commonPrefixLen`), never POSITIONALLY. From `Claims[2]`, a
 * `/Insurance/Settings/Regions*` read must span all Regions rows (shared ancestry = the root), not address the
 * nonexistent `Settings[2]` — the pre-fix behavior that under-fired `NumberOfFilledGroups`/
 * `AtLeastOneGroupFilled` and over-fired `NoGroupFilled` from every row ≥ 2 (the first kernel-mm divergence).
 * Kernel-verified by the tri-check differential `adapter.laws.CrossSubtreeGroupStarDiffTest`.
 */
class CrossSubtreeGroupStarBindingTest {

    private val REGION_CODE = "/Insurance/Settings/Regions/RegionCode"
    private val NOTE_TEXT = "/Insurance/Claims/Notes/NoteText"

    private fun model(cond: String, code: String) = ModelDef(
        fields = listOf(FieldDef(REGION_CODE, Kind.STRING), FieldDef(NOTE_TEXT, Kind.STRING)),
        rules = listOf(RuleDef("/Insurance/Claims/R", "/Insurance/Claims", cond, code, "../Notes/NoteText")),
        computations = emptyList(),
        repeatableGroups = setOf("/Insurance/Settings/Regions", "/Insurance/Claims", "/Insurance/Claims/Notes"),
    )

    private val countCond =
        "NumberOfFilledGroups(Notes*) > 0 And NumberOfFilledGroups(Notes*) < NumberOfFilledGroups(\n        /Insurance/Settings/Regions*)"
    private val noGroupCond = "NumberOfFilledGroups(Notes*) > 0 And NoGroupFilled(/Insurance/Settings/Regions*)"

    /** [regions] filled Regions rows; two Claims rows with one filled Note each. */
    private fun fired(cond: String, code: String, regions: Int): List<String> {
        val def = model(cond, code)
        val doc = def.newDocument().group("/Insurance", 1)
        for (r in 1..regions) doc.group("/Insurance/Settings/Regions", 1, 1, r).field(REGION_CODE, "R$r", 1, 1, r, 1)
        for (c in 1..2) doc.group("/Insurance/Claims", 1, c)
            .group("/Insurance/Claims/Notes", 1, c, 1).field(NOTE_TEXT, "note $c", 1, c, 1, 1)
        return DmInterpreter(def).validateFull(doc).filter { it.code == code }.map { it.errorPath }.sorted()
    }

    @Test fun crossSubtreeCountIsTheSameFromEveryClaimsRow() = assertEquals(
        listOf("/Insurance[1]/Claims[1]/Notes[1]/NoteText", "/Insurance[1]/Claims[2]/Notes[1]/NoteText"),
        fired(countCond, "XLANG", regions = 2),
        "0 < |Notes|=1 < |Regions|=2 holds identically from Claims[1] and Claims[2]")

    @Test fun noGroupFilledDoesNotOverFireFromLaterRows() = assertEquals(
        emptyList(), fired(noGroupCond, "NOREG", regions = 1),
        "a region exists — NoGroupFilled is false from every Claims row (no positional Settings[2] miss)")

    @Test fun noGroupFilledStillFiresEverywhereWithNoRegions() = assertEquals(
        listOf("/Insurance[1]/Claims[1]/Notes[1]/NoteText", "/Insurance[1]/Claims[2]/Notes[1]/NoteText"),
        fired(noGroupCond, "NOREG", regions = 0),
        "no regions — the quantifier still holds from every row (the all-rows control)")
}
