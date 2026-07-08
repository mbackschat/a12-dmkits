package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for a computation whose OPERAND is
 * formally INVALID — the IG44 root (IF76), found by delta-debugging a real composite `_CDM` model down to its
 * minimal interaction (`ModelShrinker`): the dominant compute CLEARED-vs-VALUE class was NOT tiered selection
 * (the minimal repro is an UNCONDITIONAL computation) but an operand-invalidity gap.
 *
 * The kernel treats a formally-invalid operand as **not check-relevant** → the operation is non-evaluable → the
 * computed field is **CLEARED** (computes nothing). Before IF76 the interpreter's `compute` ran with no
 * operand-invalidity gate (`invalid = { false }`), so it computed a bogus VALUE. IF76 first gated it, but on a
 * too-NARROW clear-set (5 value-domain codes); IF78 **generalized** it: the differential (below) proves the
 * kernel clears on **EVERY** number formal-error class — not just range/fraction, but the integer-digit cap,
 * zero-not-allowed, number value-string length, leading zeros, the decimal-separator-in-an-integer (the
 * maxFractionalDigits-0-default class), the >15-digit sanity check, signed-zero, and the plus-sign. So `compute`
 * now uses the SAME operand-invalidity gate `validate` uses (`computeFormalErrorAt` — any formal error clears);
 * an EMPTY operand still stays empty-as-0 (even required).
 *
 * The operand is a spliced constrained field of the order fixture; the target is `UnitPrice` (seeded to a
 * sentinel so a clear is a reported CHANGE, comparable to the kernel's change-only compute report).
 */
class ComputeInvalidOperandDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val QTY = "/Order/Quantity"
    private val PRICE = "/Order/UnitPrice"
    private val RATE = "/Order/Rate"

    /** Splice [fieldJson] into /Order's elements (kernel side); mirrors FieldConstraintFormalDiffTest. */
    private fun withField(fieldJson: String): String {
        val g = order.indexOf("\"id\" : \"/Order\"").let { if (it < 0) order.indexOf("\"id\": \"/Order\"") else it }
        val br = order.indexOf('[', order.indexOf("\"elements\"", g))
        return order.substring(0, br + 1) + "\n" + fieldJson.trimIndent().replace("\n", " ") + order.substring(br + 1)
    }

    private fun kOutcome(model: String, values: Map<String, String>): String {
        val doc = RuntimeLaws.doc().group("/Order", 1)
        doc.field(PRICE, "0.01", 1, 1)   // sentinel seed → a clear is a reported CHANGE
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        val c = RuntimeLaws.compute(model, doc).firstOrNull { it.path() == PRICE }
        return kernelSig(c)
    }

    private fun jOutcome(model: String, values: Map<String, String>): String {
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val doc = RuntimeLaws.doc().group("/Order", 1)
        doc.field(PRICE, "0.01", 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        // computeOutcomesSorted renders `normalizedPath|value` / `|CLEARED` / `|ERR`; extract UnitPrice.
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$PRICE|") }
            ?.substringAfter("$PRICE|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun kernelSig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private fun iOutcome(operand: FieldDef, op: String, values: Map<String, String>): String {
        val def = ModelDef(
            listOf(FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER, scale = 2), operand),
            emptyList(),
            computations = listOf(ComputationDef(PRICE, "/Order", listOf(ComputationAlternative(null, op)))),
        )
        val doc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats, def.scales)
            .group("/Order", 1)
        doc.field(PRICE, "0.01", 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == PRICE }
        return when (c?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    /** groovy-dynamic == java-static == interpreter on the UnitPrice compute outcome. */
    private fun tri(fieldJson: String, operand: FieldDef, op: String, values: Map<String, String>, msg: String) {
        val model = RuntimeLaws.withComputation(withField(fieldJson), "/Order", "C", "../UnitPrice", op)
        val groovy = kOutcome(model, values)
        val javaStatic = jOutcome(model, values)
        val interp = iOutcome(operand, op, values)
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ── fraction-digit-constrained operand (min=max=2): the exact IG44 minimal shape ────────────────────
    private val rateFrac = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "minFractionalDigits": 2, "maxFractionalDigits": 2 } } } },"""
    private val rateFracDef = FieldDef(RATE, Kind.NUMBER, scale = 2, minFractionalDigits = 2)
    private val fracOp = "RoundDown([Rate] * [Quantity], 2)"

    @Test fun validFractionComputes() =
        tri(rateFrac, rateFracDef, fracOp, mapOf(RATE to "2.50", QTY to "4"), "Rate=2.50 (2 frac) valid → VALUE")
    @Test fun tooFewFractionDigitsClears() =
        tri(rateFrac, rateFracDef, fracOp, mapOf(RATE to "2", QTY to "4"), "Rate=2 (0 frac < min 2) → kernel CLEARS")
    @Test fun oneFractionDigitClears() =
        tri(rateFrac, rateFracDef, fracOp, mapOf(RATE to "2.5", QTY to "4"), "Rate=2.5 (1 frac < min 2) → CLEARS")
    @Test fun tooManyFractionDigitsClears() =
        tri(rateFrac, rateFracDef, fracOp, mapOf(RATE to "2.555", QTY to "4"), "Rate=2.555 (3 frac > max 2) → CLEARS")

    // ── SECOND class (predicted before the fix): a min/max VALUE-constrained operand ─────────────────────
    private val rateRange = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "minValue": 1, "maxValue": 99 } } } },"""
    private val rateRangeDef = FieldDef(RATE, Kind.NUMBER, minValue = "1", maxValue = "99")
    // Wrap in RoundDown(…, 2) so the operation scale matches UnitPrice's (scale 2) — else the kernel codegen
    // rejects it (`operation-scale == target-frac-digits`, the IG44 ruled-out scale enforcement).
    private val rangeOp = "RoundDown([Rate] * [Quantity], 2)"

    @Test fun inRangeComputes() =
        tri(rateRange, rateRangeDef, rangeOp, mapOf(RATE to "5", QTY to "4"), "Rate=5 in [1,99] → VALUE")
    @Test fun belowMinValueClears() =
        tri(rateRange, rateRangeDef, rangeOp, mapOf(RATE to "0", QTY to "4"), "Rate=0 < min 1 → kernel CLEARS")
    @Test fun aboveMaxValueClears() =
        tri(rateRange, rateRangeDef, rangeOp, mapOf(RATE to "150", QTY to "4"), "Rate=150 > max 99 → kernel CLEARS")

    // ── EMPTY (not malformed) operand — must stay empty-as-0, NOT over-suppressed by the new invalid gate ──
    private val ratePlain = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2 } } } },"""
    private val ratePlainDef = FieldDef(RATE, Kind.NUMBER, scale = 2)

    @Test fun optionalEmptyNumberOperandComputesZero() =
        tri(ratePlain, ratePlainDef, fracOp, mapOf(QTY to "4"), "Rate empty + OPTIONAL → empty-as-0 → COMPUTES (not over-suppressed)")

    // A REQUIRED-empty operand: validation treats it as not-check-relevant (Unknown); this pins whether COMPUTE
    // agrees (the kernel is the oracle — the tri-check asserts all three engines match, whichever way).
    private val rateReq = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": {
        "requirednessConfig": { "mode": "absoluteOrRelativeToNextRepAncestor" }, "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 2 } } } },"""
    private val rateReqDef = FieldDef(RATE, Kind.NUMBER, scale = 2, required = Requiredness.ABSOLUTE)

    @Test fun requiredEmptyNumberOperand() =
        tri(rateReq, rateReqDef, fracOp, mapOf(QTY to "4"), "Rate empty + REQUIRED → kernel/interp must agree")

    // ── IF78: the codes IF76's narrow 5-code gate EXCLUDED — the differential proves the kernel CLEARS on them
    //    too, so they belong in the clear-set. Each operand trips exactly one format/constraint code. ──

    // A scale-0 (no maxFractionalDigits) integer field with a decimal value → zahlHatDezimalTrenner. This is the
    // maxFractionalDigits-0-default class: a NumberType that omits maxFractionalDigits is an integer.
    private val rateInt = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0 } } } },"""
    private val rateIntDef = FieldDef(RATE, Kind.NUMBER, scale = 0)
    @Test fun decimalInIntegerOperandClears() =
        tri(rateInt, rateIntDef, fracOp, mapOf(RATE to "2.5", QTY to "4"), "Rate=2.5 in scale-0 field (zahlHatDezimalTrenner) → CLEARS")
    @Test fun leadingZerosOperandClears() =
        tri(rateInt, rateIntDef, fracOp, mapOf(RATE to "007", QTY to "4"), "Rate=007 (zahlHatFuehrendeNullen) → CLEARS")
    @Test fun plusSignOperandClears() =
        tri(rateInt, rateIntDef, fracOp, mapOf(RATE to "+5", QTY to "4"), "Rate=+5 (zahlMitPlus) → CLEARS")
    @Test fun tooManyTotalDigitsOperandClears() =
        tri(rateInt, rateIntDef, fracOp, mapOf(RATE to "12345678901234567", QTY to "4"), "17 digits (stelligkeitZuLang) → CLEARS")

    // Integer-digit cap → zahlZuvieleVorkommastellen.
    private val rateCap = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "maxIntegerDigits": 3 } } } },"""
    private val rateCapDef = FieldDef(RATE, Kind.NUMBER, scale = 0, maxIntegerDigits = 3)
    @Test fun integerCapOperandClears() =
        tri(rateCap, rateCapDef, fracOp, mapOf(RATE to "1234", QTY to "4"), "Rate=1234 (>3 integer digits) → CLEARS")

    // zero-not-allowed → zahlIstNull.
    private val rateNonZero = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "zeroNotAllowed": true } } } },"""
    private val rateNonZeroDef = FieldDef(RATE, Kind.NUMBER, scale = 0, zeroNotAllowed = true)
    @Test fun zeroNotAllowedOperandClears() =
        tri(rateNonZero, rateNonZeroDef, fracOp, mapOf(RATE to "0", QTY to "4"), "Rate=0 (zahlIstNull) → CLEARS")

    // number value-string length → zahlZuLang.
    private val rateLen = """{ "type": "Field", "id": "/Order/Rate", "name": "Rate", "Field": { "fieldType": {
        "type": "NumberType", "NumberType": { "maxFractionalDigits": 0, "maxLength": 2 } } } },"""
    private val rateLenDef = FieldDef(RATE, Kind.NUMBER, scale = 0, numberMaxLength = 2)
    @Test fun numberTooLongOperandClears() =
        tri(rateLen, rateLenDef, fracOp, mapOf(RATE to "555", QTY to "4"), "Rate=555 (len 3 > maxLength 2, zahlZuLang) → CLEARS")
}
