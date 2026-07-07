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
 * Differential — a **semantic index as a PRESENCE operand** (the WI-7 sweep's `Expr$SemanticIndex` crash class — IF59):
 * `FieldFilled([Count For "SKU-1"])`, `FieldNotFilled(...)`, a semantic index among a fill quantifier's
 * operands, and `FieldValueAsString(...)` over an indexed cell. Kernel semantics (source-confirmed, locked
 * here against the live kernel): `FieldFilled`/`FieldNotFilled` compile as one-operand `AllFieldsFilled`/
 * `NoFieldFilled`, and the indexed ref resolves to AT MOST ONE cell whose presence is `isWertAngegeben`:
 *
 *  - a matched, check-relevant filled cell → TRUE (filled);
 *  - a matched row with an EMPTY cell, an ABSENT row (no row carries the key), or an empty/no-match KEY
 *    field → FALSE ("not filled" — an absent row is judged like an empty cell, NOT skipped);
 *  - a malformed matched cell → UNKNOWN (present but not check-relevant), so neither polarity fires.
 *
 * `FieldValueAsString` over an indexed cell reads the matched cell's stored value; an absent/empty cell reads
 * as `""` (which suppresses a string comparison — the kernel's `vergleicheSTRING` empty rule).
 */
class SemanticIndexPresenceDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private val CN = "/Order/CustomerName"
    private val PC = "/Order/ProductCode"
    private val SKU = "/Order/Items/Sku"
    private val COUNT = "/Order/Items/Count"

    /** [rows] = per Items row (sku, count | null = empty count cell); [fv] extra plain fields. */
    private fun docs(model: String, rows: List<Pair<String, String?>>, vararg fv: Pair<String, String>): Pair<RuntimeLaws.Doc, Pair<DmInterpreter, Document>> {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field(CN, "Acme", 1, 1)
        for ((f, v) in fv) { kDoc.field(f, v, 1, 1); iDoc.field(f, v, 1, 1) }
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            if (count != null) { kDoc.field(COUNT, count, 1, r, 1); iDoc.field(COUNT, count, 1, r, 1) }
        }
        return kDoc to (DmInterpreter(def) to iDoc)
    }

    // The error field must be referenced in the condition (MVK_ERROR_FIELD_NOT_REFERENCED) — the standard
    // always-true guard (CustomerName is always "Acme" in the docs).
    private fun guarded(cond: String) = "FieldFilled(CustomerName) And ($cond)"

    private fun agree(cond0: String, rows: List<Pair<String, String?>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "SIP", "../CustomerName", "SIP", cond)
        val (kDoc, interp) = docs(model, rows, *fv)
        val kernel = RuntimeLaws.firedCodes(model, kDoc)
        val outcome = interp.first.validate(interp.second)
        assertTrue(outcome.unsupported.isEmpty()) { "degraded, not evaluated: ${outcome.unsupported}" }
        assertEquals(kernel, outcome.messages.map { it.code }.toSet(), "cond=`$cond` rows=$rows fv=${fv.toList()}")
    }

    private fun agreeType(cond0: String, rows: List<Pair<String, String?>>, vararg fv: Pair<String, String>) {
        val cond = guarded(cond0)
        val model = RuntimeLaws.withRule(order, "/Order", "SIP", "../CustomerName", "SIP", cond)
        val (kDoc, interp) = docs(model, rows, *fv)
        val kernel = RuntimeLaws.typeOf(model, "SIP", kDoc).map { it.name }.orElse(null)
        val msg = interp.first.validate(interp.second).messages.firstOrNull { it.code == "SIP" }
        val interpType = msg?.let { if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interpType, "cond=`$cond` rows=$rows")
    }

    private val present = listOf("SKU-1" to "5", "SKU-2" to "3")
    private val emptyCell = listOf("SKU-1" to null, "SKU-2" to "3")
    private val absentRow = listOf("SKU-2" to "3")
    private val malformed = listOf("SKU-1" to "abc", "SKU-2" to "3")

    // ── FieldFilled over a literal-keyed semantic index ─────────────────────────────────────────────
    @Test fun fieldFilledOverIndexedCell() {
        agree("FieldFilled($COUNT For \"SKU-1\")", present)     // matched + filled → fires
        agree("FieldFilled($COUNT For \"SKU-1\")", emptyCell)   // matched row, empty cell → no
        agree("FieldFilled($COUNT For \"SKU-1\")", absentRow)   // no matching row → no
        agree("FieldFilled($COUNT For \"SKU-1\")", malformed)   // malformed cell → UNKNOWN → no
    }

    // ── FieldNotFilled: the absent-row case is the load-bearing one (absent ≡ empty, NOT a skip) ─────
    @Test fun fieldNotFilledOverIndexedCell() {
        agree("FieldNotFilled($COUNT For \"SKU-1\")", emptyCell)   // empty matched cell → fires
        agree("FieldNotFilled($COUNT For \"SKU-1\")", absentRow)   // ABSENT row → "not filled" → fires
        agree("FieldNotFilled($COUNT For \"SKU-1\")", present)     // filled → no
        agree("FieldNotFilled($COUNT For \"SKU-1\")", malformed)   // UNKNOWN → no
    }

    // ── a semantic index among a fill quantifier's operands ─────────────────────────────────────────
    @Test fun quantifierWithIndexedOperand() {
        agree("AllFieldsFilled($COUNT For \"SKU-1\", CustomerName)", present)     // both → fires
        agree("AllFieldsFilled($COUNT For \"SKU-1\", CustomerName)", absentRow)   // sem FALSE → no
        agree("AtLeastOneFieldFilled($COUNT For \"SKU-1\", ProductCode)", absentRow)             // neither → no
        agree("AtLeastOneFieldFilled($COUNT For \"SKU-1\", ProductCode)", present)               // sem → fires
        agree("NoFieldFilled($COUNT For \"SKU-1\", ProductCode)", absentRow)                     // none → fires
    }

    // ── the field-keyed form `[Count For ProductCode]`: the key is the key field's current value ─────
    @Test fun fieldKeyedPresence() {
        agree("FieldFilled($COUNT For ProductCode)", present, PC to "SKU-1")      // key matches → fires
        agree("FieldFilled($COUNT For ProductCode)", present, PC to "SKU-9")      // no row for key → no
        agree("FieldFilled($COUNT For ProductCode)", present)                      // EMPTY key → absent-row → no
        agree("FieldNotFilled($COUNT For ProductCode)", present)                   // EMPTY key → fires
    }

    // ── FieldValueAsString over an indexed NUMBER cell ───────────────────────────────────────────────
    @Test fun fieldValueAsStringOverIndexedCell() {
        agree("FieldValueAsString($COUNT For \"SKU-1\") == \"5\"", present)       // "5" == "5" → fires
        agree("FieldValueAsString($COUNT For \"SKU-1\") == \"9\"", present)       // ≠ → no
        agree("FieldValueAsString($COUNT For \"SKU-1\") == \"5\"", emptyCell)     // "" → suppressed → no
        agree("FieldValueAsString($COUNT For \"SKU-1\") == \"5\"", absentRow)     // "" → suppressed → no
    }

    // ── fired polarity ───────────────────────────────────────────────────────────────────────────────
    @Test fun firedPolarity() {
        agreeType("FieldFilled($COUNT For \"SKU-1\")", present)      // a present value fired → VALUE
        agreeType("FieldNotFilled($COUNT For \"SKU-1\")", emptyCell) // fillable → OMISSION
        agreeType("FieldNotFilled($COUNT For \"SKU-1\")", absentRow)
    }
}
