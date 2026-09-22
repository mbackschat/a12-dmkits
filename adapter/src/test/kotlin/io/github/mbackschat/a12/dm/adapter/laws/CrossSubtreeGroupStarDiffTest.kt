package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a **CROSS-SUBTREE group star read
 * from an ITERATING row** (the IG56 E1 drill: the FIRST-ever kernel-mm divergence, a hostile-mode validateFull
 * kernel-only unit; own-domain re-expression of the kernel team's "an error message per supported language"
 * metamodel rule).
 *
 * A per-row rule in `Claims*` reads `NumberOfFilledGroups(/Insurance/Settings/Regions*)` — a starred group in a
 * DIFFERENT subtree (shared ancestry only at the root). The kernel binds a star's enclosing-row indices only
 * along the SHARED named ancestry; the starred level and everything below its subtree fork iterate fully — so
 * the Regions count is the same from EVERY Claims row. The interpreter's `RowContext.rowContexts` (the
 * GROUP-star row scope: `NumberOfFilledGroups`, `NoGroupFilled`/`AtLeastOneGroupFilled`, the `Having` row
 * contexts) took the row's rep vector POSITIONALLY (`rowReps.take(cap)` with no common-prefix guard — the
 * guard `starScopePrefix` (IF93) already applied on the FIELD-star path), so from `Claims[2]` the scope prefix
 * `[1,2]` addressed the nonexistent `Settings[2]` → 0 rows: the rule under-fired on every Claims row ≥ 2
 * (kernel-only units), and the reversed polarity (`NoGroupFilled`) over-fired (interp-extra units).
 *
 * The corpus form is pinned exactly in [corpusStoredFormRelativePlusAbsoluteWithNewline]: the relative
 * `Notes*` conjunct + the absolute cross-subtree operand carrying a NEWLINE + indent inside the parens (the
 * stored form the kernel team's model actually carries — parse tolerance included).
 */
class CrossSubtreeGroupStarDiffTest {

    private companion object {
        const val CODE = "XLANG"
        const val REGION_CODE = "/Insurance/Settings/Regions/RegionCode"
        const val NOTE_TEXT = "/Insurance/Claims/Notes/NoteText"
        const val CLAIMS = "/Insurance/Claims"
        const val NOTES = "/Insurance/Claims/Notes"
        const val REGIONS = "/Insurance/Settings/Regions"
    }

    private val base = DocumentModelDraft.named("claimbook").locale("en_US")
        .rootGroup("Insurance") { g ->
            g.group("Settings") { s ->
                s.group("Regions") { r -> r.repeatable(10).stringField("RegionCode") { } }
            }
            g.group("Claims") { c ->
                c.repeatable(10).group("Notes") { n -> n.repeatable(10).stringField("NoteText") { } }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** The found instance, typed-DSL-authored: per-Claims `0 < |Notes| < |Regions|` (both operands absolute). */
    private val countCond = RuntimeLaws.render(
        Dsl.numberOfFilledGroups(m.group(NOTES)).gt(0)
            .and(Dsl.numberOfFilledGroups(m.group(NOTES)).lt(Dsl.numberOfFilledGroups(m.group(REGIONS)))))

    /** The EXACT corpus stored form: relative `Notes*` + absolute cross-subtree operand with a newline+indent
     *  inside the parens (starred group quantifiers are stored-form — the recorded DEV-API-GAPS row). */
    private val corpusCond = "NumberOfFilledGroups(Notes*) > 0\nAnd NumberOfFilledGroups(Notes*) < NumberOfFilledGroups(\n        /Insurance/Settings/Regions*)"

    /** The predicted SECOND instance — same mechanism, another operator: per-Claims presence of any Region.
     *  (The `Notes*` guard conjunct references the error field's subtree — the kernel codegen rejects a rule
     *  whose error field is not referenced in the condition, `MVK_ERROR_FIELD_NOT_REFERENCED` — the same shape
     *  the corpus rule has.) */
    private val atLeastOneCond = "NumberOfFilledGroups(Notes*) > 0 And AtLeastOneGroupFilled(/Insurance/Settings/Regions*)"

    /** The reversed polarity — pre-fix the interpreter OVER-fires this from Claims[2] (interp-extra). */
    private val noGroupCond = "NumberOfFilledGroups(Notes*) > 0 And NoGroupFilled(/Insurance/Settings/Regions*)"

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    /** [regions] filled region codes; [notesPerClaim] one entry per Claims row = its filled Notes rows. */
    private fun build(b: Both, regions: Int, notesPerClaim: List<Int>) {
        b.group("/Insurance", 1)
        for (r in 1..regions) b.group(REGIONS, 1, 1, r).field(REGION_CODE, "R$r", 1, 1, r, 1)
        notesPerClaim.forEachIndexed { i, notes ->
            val c = i + 1
            b.group(CLAIMS, 1, c)
            for (n in 1..notes) b.group(NOTES, 1, c, n).field(NOTE_TEXT, "note $c.$n", 1, c, n, 1)
        }
    }

    /** Tri-check the fire-set (pointer|polarity) of [cond] against [expect], over the given instance. */
    private fun agree(cond: String, regions: Int, notesPerClaim: List<Int>, expect: List<String>, msg: String) {
        val kModel = RuntimeLaws.withRule(base, CLAIMS, "R", "../Notes/NoteText", CODE, cond)
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b, regions, notesPerClaim)
        val groovy = RuntimeLaws.messages(kModel, b.kDoc).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()), regions, notesPerClaim)
        val javaStatic = engine.messagesModern(kDoc2, null, java.util.Locale.US).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val interp = DmInterpreter(def).validateFull(b.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(expect.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    private fun at(claim: Int, polarity: String = "OMISSION_ERROR") =
        "/Insurance[1]/Claims[$claim]/Notes[1]/NoteText|$polarity"

    // ── arm 1: the found instance — the count fires on EVERY Claims row, not just row 1 ─────────────────────

    @Test fun countFiresOnEveryClaimRow() =
        agree(countCond, regions = 2, notesPerClaim = listOf(1, 1), expect = listOf(at(1), at(2)),
            "0 < |Notes|=1 < |Regions|=2 holds per Claims row")

    @Test fun corpusStoredFormRelativePlusAbsoluteWithNewline() =
        agree(corpusCond, regions = 2, notesPerClaim = listOf(1, 1), expect = listOf(at(1), at(2)),
            "the exact corpus stored form (relative Notes* + newline-indented absolute operand)")

    @Test fun perRowNotesCountStaysRowScoped() =
        agree(countCond, regions = 2, notesPerClaim = listOf(2, 1), expect = listOf(at(2)),
            "Claims[1] has |Notes|=2 = |Regions| (silent); Claims[2] has 1 < 2 (fires)")

    @Test fun singleRegionControlIsSilent() =
        agree(countCond, regions = 1, notesPerClaim = listOf(1, 1), expect = emptyList(),
            "|Notes|=1 < |Regions|=1 is false — silent on every row")

    // ── arm 2: the predicted second instance — presence via AtLeastOneGroupFilled ───────────────────────────

    @Test fun atLeastOneGroupFilledFiresOnEveryClaimRow() =
        agree(atLeastOneCond, regions = 1, notesPerClaim = listOf(1, 1, 1),
            expect = listOf(at(1, "VALUE_ERROR"), at(2, "VALUE_ERROR"), at(3, "VALUE_ERROR")),
            "a region exists — the presence check holds from EVERY Claims row")

    // ── arm 3: the reversed polarity — NoGroupFilled must not over-fire from row ≥ 2 ────────────────────────

    @Test fun noGroupFilledDoesNotOverFireFromLaterRows() =
        agree(noGroupCond, regions = 1, notesPerClaim = listOf(1, 1), expect = emptyList(),
            "a region exists — NoGroupFilled is false from EVERY Claims row (no interp-extra fire)")

    @Test fun noGroupFilledFiresEverywhereWhenNoRegions() =
        agree(noGroupCond, regions = 0, notesPerClaim = listOf(1, 1),
            expect = listOf(at(1, "OMISSION_ERROR"), at(2, "OMISSION_ERROR")),
            "no regions — NoGroupFilled holds from every Claims row (the all-rows control)")
}
