package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF68 (graduated from IG41 "selection-model default-consistency"): a group-count quantifier over a
 * STARRED repeatable-group operand — `NoGroupFilled(G*)` / `AtLeastOneGroupFilled(G*)`. The interpreter mis-routed
 * the single starred operand to the FIELD-list tally (`starQuantTally`), which resolved the group's PARENT and
 * counted zero rows — so `NoGroupFilled(G*)` OVER-fired (said "empty" even when the group had rows) and
 * `AtLeastOneGroupFilled(G*)` UNDER-fired (the two polarities of one bug). The fix tallies the group's ROWS: every
 * INSTANTIATED row counts as a "filled" group (a created row is content — IF46), `t = n = rows`, `f = 0`; and the
 * polarity for a starred group is fixed by ADDING a row (so `NoGroupFilled(G*)` at zero rows is OMISSION,
 * `AtLeastOneGroupFilled(G*)` is VALUE). This is the shared root of the mm selection-model DEFAULT_* rules
 * (`[Choice] == … And NoGroupFilled(A*) And AtLeastOneGroupFilled(B*)`) and the workspaces `NoGroupFilled(<star>)`
 * over-fire class. Both kernel strategies share the runtime tally ⟹ codegen-parity (static-JAVA tri-checked).
 *
 * Own-domain (benefit enrollment): a rule over `/Enrollment` referencing the sibling `Choice` field (so the error
 * field is referenced — `MVK_ERROR_FIELD_NOT_REFERENCED`) and the two repeatable subgroups `Active`/`Waived`.
 * Conditions are stored-form (starred group quantifiers are not typed-DSL-authorable here — DEV-API-GAPS).
 */
class StarredGroupQuantifierDiffTest {

    private val base = DocumentModelDraft.named("enroll").locale("en_US")
        .rootGroup("Enrollment") { e ->
            e.stringField("Choice") { }
            e.stringField("Note") { }
            e.group("Active") { a -> a.repeatable(10).stringField("Item") { } }
            e.group("Waived") { w -> w.repeatable(10).stringField("Item") { } }
        }.build().toJson()

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** [activeSpec]/[waivedSpec]: >0 = that many FILLED rows; <0 = |n| instantiated-but-EMPTY rows; 0 = none.
     *  [choice] fills `Choice` (unless blank); [note] optionally fills the guard field `Note`. */
    private fun scenario(cond: String, activeSpec: Int, waivedSpec: Int, choice: String = "Waived", note: String? = null): Scenario {
        val kModel = RuntimeLaws.withRule(base, "/Enrollment", "R", "../Choice", CODE, cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Enrollment", 1)
        val iDoc = def.newDocument().apply { group("/Enrollment", 1) }
        if (choice.isNotEmpty()) { kDoc.field("/Enrollment/Choice", choice, 1, 1); iDoc.field("/Enrollment/Choice", choice, 1, 1) }
        if (note != null) { kDoc.field("/Enrollment/Note", note, 1, 1); iDoc.field("/Enrollment/Note", note, 1, 1) }
        fun place(group: String, spec: Int) {
            for (r in 1..kotlin.math.abs(spec)) {
                kDoc.group(group, 1, r); iDoc.group(group, 1, r)
                if (spec > 0) { kDoc.field("$group/Item", "v$r", 1, r, 1); iDoc.field("$group/Item", "v$r", 1, r, 1) }
            }
        }
        place("/Enrollment/Active", activeSpec); place("/Enrollment/Waived", waivedSpec)
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun kSigs(s: Scenario) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == CODE }
        .map { "${it.field()}|${it.type().name}" }.sorted()
    private fun iSigs(s: Scenario) = s.interp.validateFull(s.iDoc).filter { it.code == CODE }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
    private fun agree(s: Scenario, msg: String) = assertEquals(kSigs(s), iSigs(s), "$msg\n  kernel=${kSigs(s)}\n  interp=${iSigs(s)}")

    // ── NoGroupFilled(Active*): fires (OMISSION) iff the group has ZERO rows ──────────────────────────────────
    private fun noActive(a: Int, w: Int) = scenario("[Choice] == \"Waived\" And NoGroupFilled(Active*)", a, w)
    @Test fun noGroupFilledZeroRowsFiresOmission() = agree(noActive(0, 1), "NoGroupFilled(Active*): 0 rows → fires OMISSION (add a row to fix)")
    @Test fun noGroupFilledFilledRowNoFire() = agree(noActive(1, 1), "NoGroupFilled(Active*): a filled row → no fire")
    @Test fun noGroupFilledInstantiatedEmptyRowNoFire() = agree(noActive(-1, 1), "NoGroupFilled(Active*): an instantiated-empty row counts as filled → no fire")

    // ── AtLeastOneGroupFilled(Waived*): fires (VALUE) iff the group has ≥1 row ────────────────────────────────
    private fun atLeastWaived(a: Int, w: Int) = scenario("[Choice] == \"Waived\" And AtLeastOneGroupFilled(Waived*)", a, w)
    @Test fun atLeastOneFilledRowFiresValue() = agree(atLeastWaived(0, 1), "AtLeastOneGroupFilled(Waived*): a filled row → fires VALUE")
    @Test fun atLeastOneInstantiatedEmptyRowFiresValue() = agree(atLeastWaived(0, -1), "AtLeastOneGroupFilled(Waived*): an instantiated-empty row counts → fires VALUE")
    @Test fun atLeastOneZeroRowsNoFire() = agree(atLeastWaived(0, 0), "AtLeastOneGroupFilled(Waived*): 0 rows → no fire")

    // ── the mm selection-DEFAULT shape: [Choice]==… And NoGroupFilled(A*) And AtLeastOneGroupFilled(B*) ───────
    private fun defaultShape(a: Int, w: Int) =
        scenario("[Choice] == \"Waived\" And NoGroupFilled(Active*) And AtLeastOneGroupFilled(Waived*)", a, w)
    @Test fun defaultShapeFiresWhenNoActiveButSomeWaived() = agree(defaultShape(0, 1), "no Active rows AND ≥1 Waived → fires (OMISSION)")
    @Test fun defaultShapeSilentWhenActivePresent() = agree(defaultShape(1, 1), "some Active rows → the NoGroupFilled conjunct is false → no fire")

    // ── a STARRED group scope MIXED with a FIELD operand in one entity list (IF219; the star contributes
    //    one "filled" per instantiated row to the same (t,f,n) tally the field operand feeds) ──────────────────
    private fun atLeastMixed(choice: String, w: Int) =
        scenario("AtLeastOneGroupFilled(Choice, Waived*)", 0, w, choice)
    @Test fun mixedAtLeastOneFieldFilledFires() =
        agree(atLeastMixed("Waived", 0), "mixed: filled field + 0 star rows → the field is the ≥1 witness (VALUE)")
    @Test fun mixedAtLeastOneStarRowFires() =
        agree(atLeastMixed("", 1), "mixed: empty field + 1 star row → the star row is the ≥1 witness (VALUE)")
    @Test fun mixedAtLeastOneAllEmptyNoFire() =
        agree(atLeastMixed("", 0), "mixed: empty field + 0 star rows → nothing filled → no fire")

    private fun noMixed(choice: String, w: Int) =
        scenario("[Note] == \"g\" And NoGroupFilled(Choice, Waived*)", 0, w, choice, note = "g")
    @Test fun mixedNoGroupFilledAllEmptyFires() =
        agree(noMixed("", 0), "mixed: empty field + 0 star rows → all definitely empty → fires (OMISSION)")
    @Test fun mixedNoGroupFilledStarRowBlocks() =
        agree(noMixed("", 1), "mixed: empty field + 1 star row → the star row is filled → no fire")
    @Test fun mixedNoGroupFilledFieldBlocks() =
        agree(noMixed("Waived", 0), "mixed: filled field + 0 star rows → the field is filled → no fire")

    // ── SPEC-2026-07-23-09 follow-up: the SAME starred group may occupy several authored slots ────────────────
    // The kernel's direct-duplicate gate (`checkDuplicatesInDirectRefs`) continues past a wildcarded operand,
    // so `NoGroupFilled(G*, G*)` and `AtLeastOneGroupFilled(G*, G*)` are ADMITTED (locked against the real
    // kernel in `rulekit.validate.laws.GroupListEntityOperandLawsTest`); `AllGroupsFilled` cannot be exercised
    // because the wildcard bar rejects it outright. Repeating the witness is truth-idempotent, but the POLARITY
    // and the row tally are not idempotent by construction, so the kernel — not the single-operand form — is
    // what settles the repeated shape.
    private fun noActiveTwice(a: Int, w: Int) =
        scenario("[Choice] == \"Waived\" And NoGroupFilled(Active*, Active*)", a, w)
    @Test fun repeatedStarNoGroupFilledZeroRows() =
        agree(noActiveTwice(0, 1), "NoGroupFilled(Active*, Active*): 0 rows")
    @Test fun repeatedStarNoGroupFilledFilledRow() =
        agree(noActiveTwice(1, 1), "NoGroupFilled(Active*, Active*): a filled row")
    @Test fun repeatedStarNoGroupFilledThreeRows() =
        agree(noActiveTwice(3, 1), "NoGroupFilled(Active*, Active*): three filled rows")

    private fun atLeastActiveTwice(a: Int, w: Int) =
        scenario("[Choice] == \"Waived\" And AtLeastOneGroupFilled(Active*, Active*)", a, w)
    @Test fun repeatedStarAtLeastOneZeroRows() =
        agree(atLeastActiveTwice(0, 1), "AtLeastOneGroupFilled(Active*, Active*): 0 rows")
    @Test fun repeatedStarAtLeastOneFilledRow() =
        agree(atLeastActiveTwice(1, 1), "AtLeastOneGroupFilled(Active*, Active*): a filled row")
    @Test fun repeatedStarAtLeastOneInstantiatedEmptyRow() =
        agree(atLeastActiveTwice(-1, 1), "AtLeastOneGroupFilled(Active*, Active*): an instantiated-empty row")

    // ── both strategies: the group-count tally is runtime-shared; static-JAVA codegen must agree ──────────────
    @Test fun codegenTalliesStarredGroupIdentically() {
        val s = defaultShape(0, 1)
        val groovy = kSigs(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, iSigs(s), "codegen (interp≠groovy)\n  groovy=$groovy\n  interp=${iSigs(s)}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }
}

private const val CODE = "SEL"
