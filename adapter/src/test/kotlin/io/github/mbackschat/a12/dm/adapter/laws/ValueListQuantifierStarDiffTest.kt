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
 * Differential — the field-list × value-list quantifiers with **starred operands** (the WI-7 sweep's `Expr$Star` crash
 * class in `ValueListQuant` fields — IF60) and their per-cell classification, kernel-exact per 30.8.1 source
 * (`mindestensEinFeldWertInWerteListe` / `keinFeldWertInWerteListe` / `nichtAlleFeldWerteInWerteListe`):
 *
 *  - a STARRED fields/values entry expands to one cell per instantiated row; each cell classifies by
 *    `isWertAngegeben`: filled+relevant (counted), empty (skipped), malformed (op-dependent, below);
 *  - **malformed asymmetry** — a malformed FIELDS cell is SKIPPED by `AtLeastOne`/`NotAll` but POISONS `No`
 *    (FALSE_OR_UNKNOWN); a malformed VALUES member is SKIPPED by `AtLeastOne` but POISONS `No`/`NotAll`;
 *  - an EMPTY VALUES member contributes nothing (never the number 0) and flags a fired `No`/`NotAll` as
 *    OMISSION (`einWertDerWerteListeNichtAngegeben` → TRUE_AF); `AtLeastOne` ignores empties entirely;
 *  - guards: `AtLeastOne` with an empty value SET does not fire; `NotAll` needs a filled fields cell;
 *    `No` fires even with nothing filled;
 *  - polarity: a fired `No` is OMISSION on any empty fields cell / un-instantiated declared tail / empty
 *    values member; a fired `NotAll` is OMISSION only on the VALUES side's account (NO fields-tail flag —
 *    the No-vs-NotAll asymmetry); a fired `AtLeastOne` is VALUE.
 *
 * `ValueListQuantifierDiffTest` covers the plain literal form, `EntityValueListDiffTest` the values-star form;
 * this locks the starred FIELDS side + the malformed/empty classification both sides.
 */
class ValueListQuantifierStarDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private val CN = "/Order/CustomerName"
    private val PC = "/Order/ProductCode"
    private val QTY = "/Order/Quantity"
    private val SOH = "/Order/StockOnHand"
    private val SKU = "/Order/Items/Sku"
    private val COUNT = "/Order/Items/Count"
    private val SKUS = "/Order/Items*/Sku"
    private val COUNTS = "/Order/Items*/Count"

    private fun guarded(cond: String) = "FieldFilled(CustomerName) And ($cond)"

    /** [rows] = per Items row (sku | null, count | null); [fv] extra plain fields. */
    private fun docs(model: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>): Pair<RuntimeLaws.Doc, Pair<DmInterpreter, Document>> {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1)
        for ((f, v) in fv) { kDoc.field(f, v, 1, 1); iDoc.field(f, v, 1, 1) }
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r)
            iDoc.group("/Order/Items", 1, r)
            if (sku != null) { kDoc.field(SKU, sku, 1, r, 1); iDoc.field(SKU, sku, 1, r, 1) }
            if (count != null) { kDoc.field(COUNT, count, 1, r, 1); iDoc.field(COUNT, count, 1, r, 1) }
        }
        return kDoc to (DmInterpreter(def) to iDoc)
    }

    private fun agree(cond0: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "VLQ", "../CustomerName", "VLQ", cond)
        val (kDoc, interp) = docs(model, rows, *fv)
        val kernel = RuntimeLaws.firedCodes(model, kDoc)
        val outcome = interp.first.validate(interp.second)
        assertTrue(outcome.unsupported.isEmpty()) { "degraded, not evaluated: ${outcome.unsupported}" }
        assertEquals(kernel, outcome.messages.map { it.code }.toSet(), "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    private fun agreeType(cond0: String, rows: List<Pair<String?, String?>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "VLQ", "../CustomerName", "VLQ", cond)
        val (kDoc, interp) = docs(model, rows, *fv)
        val kernel = RuntimeLaws.typeOf(model, "VLQ", kDoc).map { it.name }.orElse(null)
        val msg = interp.first.validate(interp.second).messages.firstOrNull { it.code == "VLQ" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interpType, "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    // ── AtLeastOne: starred STRING fields against literals ──────────────────────────────────────────
    @Test fun atLeastOneOverStarredFields() {
        agree("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\", \"B\")", listOf("A" to null, "C" to null))
        agree("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\", \"B\")", listOf("C" to null, "D" to null))
        agree("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\", \"B\")", emptyList())
        agree("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\", \"B\")", listOf(null to "5", "C" to null))
    }

    // ── AtLeastOne: NUMBER star fields, field-valued values — malformed/empty members SKIP, never poison,
    //     and an empty number member is NOT the value 0 ────────────────────────────────────────────────
    @Test fun atLeastOneNumberStarFieldValues() {
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY, $SOH)",
            listOf("a" to "5", "b" to "7"), QTY to "7")
        // a malformed FIELDS cell is skipped — the 5 still matches (a poison would suppress the fire)
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY, $SOH)",
            listOf("a" to "5", "b" to "abc"), QTY to "5")
        // an EMPTY values member contributes nothing (QTY empty; SOH=9 matches)
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY, $SOH)",
            listOf("a" to "9"), SOH to "9")
        // …and it is NOT the number 0 (a count of 0 must not match an empty member)
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY, $SOH)",
            listOf("a" to "0"), SOH to "9")
        // a MALFORMED values member is skipped by AtLeastOne (No/NotAll would poison)
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY, $SOH)",
            listOf("a" to "9"), QTY to "abc", SOH to "9")
    }

    // ── No: starred fields — malformed FIELDS cell POISONS (asymmetry vs AtLeastOne/NotAll) ─────────
    @Test fun noOverStarredFields() {
        agree("NoFieldValueIncludedInValueList($SKUS In \"A\")", listOf("C" to null, "D" to null))
        agree("NoFieldValueIncludedInValueList($SKUS In \"A\")", listOf("A" to null, "C" to null))
        agree("NoFieldValueIncludedInValueList($SKUS In \"A\")", listOf("C" to null, null to "5"))
        // (a NUMBER value list is only expressible with FIELD members — a numeric literal is
        // MVK_INVALID_ENTITY unquoted / MVK_INVALID_TYPES_FOR_COMPARISON quoted)
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("a" to "7", "b" to "abc"), QTY to "5")
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("a" to "7", "b" to "9"), QTY to "5")
    }

    // ── No: values-side field classes — empty member → still fires (∅ set); malformed member → poison ─
    @Test fun noValuesSideMembers() {
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("a" to "7"))
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("a" to "7"), QTY to "7")
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("a" to "9"), QTY to "abc")
    }

    // ── NotAll: starred fields + the guards ──────────────────────────────────────────────────────────
    @Test fun notAllOverStarredFields() {
        agree("NotAllFieldValuesIncludedInValueList($SKUS In \"A\", \"B\")", listOf("A" to null, "C" to null))
        agree("NotAllFieldValuesIncludedInValueList($SKUS In \"A\", \"B\")", listOf("A" to null, "B" to null))
        agree("NotAllFieldValuesIncludedInValueList($SKUS In \"A\", \"B\")", listOf(null to "5", null to "7"))
        // malformed FIELDS cell skipped: the filled 7 is IN → no fire; the filled 9 is OUT → fires
        agree("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY)", listOf("a" to "abc", "b" to "7"), QTY to "7")
        agree("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY)", listOf("a" to "abc", "b" to "9"), QTY to "7")
        // malformed VALUES member poisons
        agree("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY)", listOf("a" to "9"), QTY to "abc")
    }

    // ── formal errors on the STARRED FIELD are field-LOCAL (Sku is Items' index field, auto-required per
    //     row): a starred INDEX field's empty cell is mandatory-empty → not check-relevant → UNKNOWN
    //     (poisons No, skipped by AtLeastOne); a CLEAN SIBLING field (Count) in the same row reads normally —
    //     the row is NOT collectively compromised ─────────────────────────────────────────────────────────
    @Test fun compromisedIndexRows() {
        // Count star with an empty-INDEX row: Count itself is clean → its cells all count normally
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY)", listOf(null to "5", "A" to "7"), QTY to "5")
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY)", listOf(null to "5", "A" to "7"), QTY to "7")
        agree("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY)", listOf(null to "5", "A" to "7"), QTY to "7")
        agree("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY)", listOf(null to "9", "A" to "7"), QTY to "9")
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf(null to "5", "A" to "7"), QTY to "9")
        // duplicate index values: the starred sibling Count still reads normally
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("A" to "5", "A" to "7"), QTY to "7")
        agree("AtLeastOneFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("A" to "5", "A" to "7"), QTY to "5")
        agree("NoFieldValueIncludedInValueList($COUNTS In $QTY)", listOf("A" to "5", "A" to "7"), QTY to "9")
        // the starred INDEX FIELD itself with DUPLICATE values (uniqueIndex error on the dup cell)
        agree("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\")", listOf("A" to null, "A" to null))
        agree("NoFieldValueIncludedInValueList($SKUS In \"X\")", listOf("A" to null, "A" to null))
        // No over a values-side star of the INDEX field with an empty cell → poison
        agree("NoFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null, null to "5"), PC to "X")
    }

    // ── values-side STAR with a plain field on the fields side (the EntityValueList form, presence edges) ─
    @Test fun valuesStarWithPlainField() {
        agree("AtLeastOneFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null, "B" to null), PC to "A")
        agree("AtLeastOneFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null), PC to "X")
        agree("NoFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null), PC to "X")
        // No fires even when NO fields-side entry is filled at all
        agree("NoFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null))
    }

    // ── fired polarity: No's fields-tail/empty flag; NotAll's values-only flag; AtLeastOne always VALUE ─
    @Test fun firedPolarity() {
        // No: 2 of 50 declared rows → un-instantiated tail → OMISSION
        agreeType("NoFieldValueIncludedInValueList($SKUS In \"A\")", listOf("C" to null, "D" to null))
        // No, values star with tail → OMISSION
        agreeType("NoFieldValueIncludedInValueList(ProductCode In $SKUS)", listOf("A" to null), PC to "X")
        // NotAll: an EMPTY values member → OMISSION (kernel TRUE_AF)
        agreeType("NotAllFieldValuesIncludedInValueList($COUNTS In $QTY, $SOH)", listOf("a" to "9"), QTY to "7")
        // NotAll: literals only → VALUE even with the un-instantiated fields tail (no fields-tail flag)
        agreeType("NotAllFieldValuesIncludedInValueList($SKUS In \"A\")", listOf("C" to null))
        // AtLeastOne fired → VALUE (an empty cell elsewhere is irrelevant)
        agreeType("AtLeastOneFieldValueIncludedInValueList($SKUS In \"A\")", listOf("A" to null, null to "5"))
    }
}
