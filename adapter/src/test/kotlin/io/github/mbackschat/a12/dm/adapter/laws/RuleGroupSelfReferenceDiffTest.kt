package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — the `RuleGroup` self-reference keyword (the kernel's `RegelKontext`, IF63). `RuleGroup` denotes
 * the rule's OWN group — the `ruleGroup` the checker resolves in `CheckRegelKontextEntityImpl`. It is a reserved
 * group-context keyword: the only self-reference in the condition grammar, never starred
 * (`MVK_INVALID_WILDCARD_FOR_RULEGROUP`) and never a path prefix. The interpreter used to parse `RuleGroup` as a
 * nonexistent child group `<base>/RuleGroup`, so `GroupFilled(RuleGroup)` was always false (kernel-only firings the
 * interpreter missed) and `GroupNotFilled(RuleGroup)` was always true (interp-only extras). This was the WI-7
 * sweep's largest `validateFull` divergence class — it spans the reported "file/binary" attachment rules (which are
 * just `GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(id, content)` over field NAMES; no file field type
 * exists) AND the plain `GroupFilled/GroupNotFilled(RuleGroup)` forms; one root closes both.
 *
 * The key semantic (kernel-observed, matched here): on a rule that ITERATES a repeatable group, `GroupFilled(RuleGroup)`
 * is TRUE for **every instantiated row** (the row is present — even an all-empty instantiated row fires), so it acts
 * as an "is this repetition present" guard, not a "has content" test. On a NON-repeatable group it is the ordinary
 * "has a filled descendant". `GroupNotFilled(RuleGroup)` on a repeatable iteration group is authoring-rejected
 * (`MVK_NEG_CONDITION_IN_ITERATION`), so it appears only on non-repeatable groups.
 *
 * Both kernel strategies resolve `RegelKontext` to the rule group entity identically (runtime + codegen share the
 * parser's entity resolution). The model is authored once and loaded into BOTH engines via [ModelLoader] (so a
 * loader/eval split shows as a diff) and compared against the Groovy-dynamic anchor (+ static-JAVA for the flagship
 * shape). Own-domain e-commerce; conditions are stored-form strings (RuleGroup is not typed-DSL-authorable — DEV-API-GAPS).
 */
class RuleGroupSelfReferenceDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** Inject one rule `cond` (code [CODE]) into [group] with error field [errorRel]; instantiate `Items` rows =
     *  [rows] (each `(sku, count, unitWeight)`, null = left empty) and, when [address] is given, the non-repeatable
     *  ShippingAddress `(street, city)`. The kernel doc and interpreter doc are filled identically in lockstep
     *  (the kernel instantiates the `/Order` root; the interpreter's root is implicit — mirrors GroupFilledDiffTest). */
    private fun scenario(group: String, errorRel: String, cond: String,
                         rows: List<Triple<String?, String?, String?>> = emptyList(),
                         address: Pair<String?, String?>? = null): Scenario {
        val kModel = RuntimeLaws.withRule(order, group, "R", errorRel, CODE, cond)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument()
        address?.let { (street, city) ->
            kDoc.group(SHIP, 1, 1); iDoc.group(SHIP, 1, 1)
            if (street != null) { kDoc.field("$SHIP/Street", street, 1, 1, 1); iDoc.field("$SHIP/Street", street, 1, 1, 1) }
            if (city != null) { kDoc.field("$SHIP/City", city, 1, 1, 1); iDoc.field("$SHIP/City", city, 1, 1, 1) }
        }
        rows.forEachIndexed { i, (sku, count, uw) ->
            val r = i + 1
            kDoc.group(ITEMS, 1, r); iDoc.group(ITEMS, 1, r)
            if (sku != null) { kDoc.field("$ITEMS/Sku", sku, 1, r, 1); iDoc.field("$ITEMS/Sku", sku, 1, r, 1) }
            if (count != null) { kDoc.field("$ITEMS/Count", count, 1, r, 1); iDoc.field("$ITEMS/Count", count, 1, r, 1) }
            if (uw != null) { kDoc.field("$ITEMS/UnitWeight", uw, 1, r, 1); iDoc.field("$ITEMS/UnitWeight", uw, 1, r, 1) }
        }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    // The interpreter's MsgType renders as VALUE/OMISSION; the kernel's as VALUE_ERROR/OMISSION_ERROR — normalize.
    private fun interpSigs(s: Scenario) = s.interp.validateFull(s.iDoc).filter { it.code == CODE }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

    private fun groovySigs(s: Scenario) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == CODE }
        .map { "${it.field()}|${it.type().name}" }.sorted()

    /** interpreter == Groovy-dynamic (the anchor) for the rule's messages (pointer + polarity). */
    private fun agree(s: Scenario, msg: String) =
        assertEquals(groovySigs(s), interpSigs(s), "$msg\n  kernel=${groovySigs(s)}\n  interp=${interpSigs(s)}")

    // ── repeatable RuleGroup: "is THIS repetition present" (TRUE for every instantiated row) ──────────────────

    @Test fun groupFilledRuleGroupIsTrueForEveryInstantiatedRow() {
        // Fires on every instantiated Items row — even the all-empty one (row 2): the row's presence is content.
        val s = scenario(ITEMS, "../Count", "GroupFilled(RuleGroup)",
            rows = listOf(Triple("K1", "5", null), Triple(null, null, null), Triple("K3", "1", "2")))
        agree(s, "GroupFilled(RuleGroup) fires on all instantiated rows")
        assertTrue(interpSigs(s).size == 3) { "expected all 3 instantiated rows to fire, got ${interpSigs(s)}" }
    }

    @Test fun theAttachmentShape() {
        // The flagship real-corpus form: GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(Count, UnitWeight).
        // GroupFilled(RuleGroup) is true per instantiated row, so it reduces to NotExactlyOne: row1 (exactly one)
        // → no fire; row2 (empty) → fire; row3 (both) → fire; row4 (empty) → fire.
        val s = scenario(ITEMS, "../Count",
            "GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(Count, UnitWeight)",
            rows = listOf(Triple("K1", "5", null), Triple("K2", null, null),
                Triple("K3", "5", "2"), Triple(null, null, null)))
        agree(s, "attachment shape: fires on rows 2,3,4; not on the exactly-one row 1")
    }

    @Test fun combinedWithValueComparison() {
        val s = scenario(ITEMS, "../Count", "GroupFilled(RuleGroup) And [Count] != 9",
            rows = listOf(Triple("K1", "5", null), Triple("K2", "9", null), Triple("K3", "1", null)))
        agree(s, "GroupFilled(RuleGroup) And [Count]!=9: matches the kernel per row")
    }

    // ── non-repeatable RuleGroup: the ordinary "has a filled descendant" ──────────────────────────────────────

    @Test fun ruleGroupOnNonRepeatableGroupFilled() {
        val s = scenario(SHIP, "../Street", "GroupFilled(RuleGroup)", address = "Main St" to null)
        agree(s, "GroupFilled(RuleGroup) on a non-repeatable group: fires when the group has content")
        assertTrue(interpSigs(s).isNotEmpty()) { "expected a firing on the filled address group" }
    }

    @Test fun ruleGroupOnNonRepeatableGroupEmpty() {
        val s = scenario(SHIP, "../Street", "GroupFilled(RuleGroup)", address = null to null)
        agree(s, "GroupFilled(RuleGroup) on an empty non-repeatable group: no fire")
    }

    @Test fun groupNotFilledRuleGroupOnNonRepeatableFiresWhenEmpty() {
        val s = scenario(SHIP, "../Street", "GroupNotFilled(RuleGroup)", address = null to null)
        agree(s, "GroupNotFilled(RuleGroup) on an empty non-repeatable group: fires")
        assertTrue(interpSigs(s).isNotEmpty()) { "expected a firing on the empty address group" }
    }

    @Test fun groupNotFilledRuleGroupOnNonRepeatableSilentWhenFilled() {
        val s = scenario(SHIP, "../Street", "GroupNotFilled(RuleGroup)", address = "Main St" to null)
        agree(s, "GroupNotFilled(RuleGroup) on a filled non-repeatable group: no fire")
    }

    // ── both strategies: the flagship shape on the static-JAVA codegen path too ───────────────────────────────

    @Test fun codegenResolvesRuleGroupIdentically() {
        val s = scenario(ITEMS, "../Count",
            "GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(Count, UnitWeight)",
            rows = listOf(Triple("K1", "5", null), Triple("K2", null, null), Triple("K3", "5", "2")))
        val groovy = groovySigs(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, interpSigs(s), "codegen shape (interp≠groovy)\n  kernel=$groovy\n  interp=${interpSigs(s)}")
        assertEquals(groovy, javaStatic, "codegen shape (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }
}

private const val CODE = "GFRULE"
private const val ITEMS = "/Order/Items"
private const val SHIP = "/Order/ShippingAddress"
