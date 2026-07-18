package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the MULTI-OPERAND aggregate class (IG29–IG32), the interpreter twin of the render-only
 * `MultiFieldSumReadTest`. The `Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`/`NumberOfFilledFields`
 * families were modeled SINGLE-STARRED-OPERAND only (`starVals` did `f.args.single() as Expr.Star`,
 * `NumberOfFilledFields` did `pathOf(field)`), but rulekit + the kernel also accept the explicit MULTI-FIELD
 * list, a GROUP operand, and non-numeric (STRING/ENUM) element types. Four of the forms crashed uncaught
 * (aborting the whole run), one silently miscounted. This sweeps every form × {number, string} through BOTH
 * engines over the same document — authored via the typed DSL, rendered, run through the kernel and the
 * interpreter:
 *  - IG29 multi-field `Sum`/`MaxValue`/`MinValue` (≥2 plain number fields; empties ignored, not treated as 0)
 *  - IG30 `NumberOfDifferentValues` multi-field AND string operands (distinct-by-TYPED-value, not numeric)
 *  - IG31 `NumberOfFilledFields` over a starred repetition list (`Items* then Count`)
 *  - IG32 `NumberOfFilledFields` over a GROUP operand (counts the FILLED fields inside, recursively)
 * (IG33 — `SumOfProducts` over plain fields — is parity-by-construction: the kernel rejects it at codegen
 * with `MVK_REPEATABLE_LEVEL_REQUIRED`, so it never reaches the interpreter on a valid model.)
 */
class MultiFieldAggregateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition) = DslRenderer().render(c)
    private fun gate(c: Condition) = m.field(CN).filled().and(c)

    // ── the rendered rules, one per form ──
    private val sumMF = render(gate(Dsl.sum(m.field(QTY), m.field(BQ)).gt(100)))               // IG29
    private val maxMF = render(gate(Dsl.maxValue(m.field(QTY), m.field(BQ)).gt(50)))           // IG29
    private val minMF = render(gate(Dsl.minValue(m.field(QTY), m.field(BQ)).lt(10)))           // IG29
    private val ndvNum = render(gate(Dsl.numberOfDifferentValues(m.field(QTY), m.field(BQ)).eq(2)))  // IG30 multi-field number
    private val ndvStr = render(gate(Dsl.numberOfDifferentValues(m.flatten("/Order/Items*/Sku")).ge(2)))  // IG30 string
    private val nffStar = render(gate(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Count")).ge(2)))  // IG31
    private val nffGroup = render(gate(Dsl.numberOfFilledFields(m.group("/Order/ShippingAddress")).ge(1)))  // IG32

    private val fields = listOf(
        FieldDef(CN, Kind.STRING),
        FieldDef(QTY, Kind.NUMBER, scale = 0), FieldDef(BQ, Kind.NUMBER, scale = 0),
        FieldDef(STREET, Kind.STRING), FieldDef(CITY, Kind.STRING),
        FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER, scale = 0))

    /** All operand inputs; a null field is empty (absent), a null (sku|count) cell is present-empty in that row. */
    private data class Scene(
        val qty: String? = null, val bq: String? = null,
        val addrRow: Boolean = false, val street: String? = null, val city: String? = null,
        val items: List<Pair<String?, String?>> = emptyList())   // (sku, count) per Items row

    private fun kDoc(s: Scene): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        s.qty?.let { doc.field(QTY, it, 1, 1) }
        s.bq?.let { doc.field(BQ, it, 1, 1) }
        if (s.addrRow) {
            doc.group("/Order/ShippingAddress", 1, 1)
            s.street?.let { doc.field(STREET, it, 1, 1, 1) }
            s.city?.let { doc.field(CITY, it, 1, 1, 1) }
        }
        s.items.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            doc.group("/Order/Items", 1, r)
            sku?.let { doc.field(SKU, it, 1, r, 1) }
            count?.let { doc.field(COUNT, it, 1, r, 1) }
        }
        return doc
    }

    private val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", "true", "NF", "../CustomerName")),
        repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50))

    private fun iDoc(s: Scene): Document {
        val doc = def.newDocument()
        doc.field(CN, "Acme", 1, 1)
        s.qty?.let { doc.field(QTY, it, 1, 1) }
        s.bq?.let { doc.field(BQ, it, 1, 1) }
        if (s.addrRow) {
            doc.group("/Order/ShippingAddress", 1, 1)
            s.street?.let { doc.field(STREET, it, 1, 1, 1) }
            s.city?.let { doc.field(CITY, it, 1, 1, 1) }
        }
        s.items.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            doc.group("/Order/Items", 1, r)
            sku?.let { doc.field(SKU, it, 1, r, 1) }
            count?.let { doc.field(COUNT, it, 1, r, 1) }
        }
        return doc
    }

    private fun interp(cond: String) =
        DmInterpreter(def.copy(rules = listOf(RuleDef("/Order/R", "/Order", cond, "NF", "../CustomerName"))))

    private fun kFires(cond: String, s: Scene): Boolean =
        RuntimeLaws.messages(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "NF", cond), kDoc(s)).any { it.code() == "NF" }

    private fun iFires(cond: String, s: Scene): Boolean = interp(cond).validateFull(iDoc(s)).any { it.code == "NF" }

    private fun agree(cond: String, s: Scene) = assertEquals(kFires(cond, s), iFires(cond, s), "cond=$cond scene=$s")

    /** A FIRED aggregate comparison agrees with the kernel on message polarity (VALUE vs OMISSION). */
    private fun agreeFiredType(cond: String, s: Scene) {
        val kType = RuntimeLaws.typeOf(RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "NF", cond), "NF", kDoc(s)).orElseThrow()
        val iMsg = interp(cond).validate(iDoc(s)).messages.single { it.code == "NF" }
        assertEquals(kType == IMessage.MessageType.VALUE_ERROR, iMsg.type == MsgType.VALUE, "polarity cond=$cond scene=$s")
    }

    @Test fun multiFieldSum() {          // IG29
        agree(sumMF, Scene(qty = "60", bq = "50"))   // 110 > 100 → fires
        agree(sumMF, Scene(qty = "60", bq = "30"))   // 90 not
        agree(sumMF, Scene(qty = "60"))              // empty BQ ignored → 60 not
        agree(sumMF, Scene())                        // both empty → 0 not
    }

    @Test fun multiFieldMaxValue() {     // IG29
        agree(maxMF, Scene(qty = "60", bq = "30"))   // max 60 > 50 → fires
        agree(maxMF, Scene(qty = "40", bq = "30"))   // max 40 not
        agree(maxMF, Scene(qty = "60"))              // empty BQ ignored → max 60 fires
        agree(maxMF, Scene())                        // both empty → non-evaluable, not fire
    }

    @Test fun multiFieldMinValue() {     // IG29 — empty must be IGNORED, not treated as 0
        agree(minMF, Scene(qty = "5", bq = "30"))    // min 5 < 10 → fires
        agree(minMF, Scene(qty = "20", bq = "30"))   // min 20 not
        agree(minMF, Scene(qty = "20"))              // empty BQ ignored → min 20 not (0 would wrongly fire)
        agree(minMF, Scene())                        // both empty → non-evaluable, not fire
    }

    @Test fun numberOfDifferentValuesNumber() {   // IG30 multi-field number
        agree(ndvNum, Scene(qty = "5", bq = "30"))   // 2 distinct == 2 → fires
        agree(ndvNum, Scene(qty = "5", bq = "5"))    // 1 distinct not
        agree(ndvNum, Scene(qty = "5"))              // empty ignored → 1 distinct not
        agree(ndvNum, Scene())                       // 0 distinct not
    }

    @Test fun numberOfDifferentValuesString() {   // IG30 string operand (the numeric fold crashed)
        agree(ndvStr, Scene(items = listOf("A" to null, "B" to null)))   // {A,B} 2 distinct → fires
        agree(ndvStr, Scene(items = listOf("A" to null, "A" to null)))   // {A} 1 not
        agree(ndvStr, Scene(items = listOf("A" to null, null to null)))  // {A} 1 (empty dropped) not
        agree(ndvStr, Scene())                                           // 0 not
    }

    @Test fun numberOfFilledFieldsStar() {        // IG31 — a starred repetition list
        agree(nffStar, Scene(items = listOf("x" to "10", "x" to "20")))  // 2 filled >= 2 → fires
        agree(nffStar, Scene(items = listOf("x" to "10", "x" to null)))  // 1 filled not
        agree(nffStar, Scene(items = listOf("x" to "10", "x" to "20", "x" to "30")))  // 3 fires
        agree(nffStar, Scene())                                          // 0 not
        // a MALFORMED Count (decimal on a scale-0 field) makes the whole count non-evaluable → the rule is
        // SUPPRESSED, even though 2 cells are valid (NOT "count the valid ones" — fuzz-surfaced, IF51).
        agree(nffStar, Scene(items = listOf("x" to "10", "x" to "20", "x" to "5.5")))
    }

    @Test fun numberOfFilledFieldsGroup() {       // IG32 — a group operand (fields inside)
        agree(nffGroup, Scene(addrRow = true, street = "s"))             // 1 filled >= 1 → fires
        agree(nffGroup, Scene(addrRow = true, street = "s", city = "c")) // 2 fires
        agree(nffGroup, Scene(addrRow = true))                           // present-empty group → 0 not
        agree(nffGroup, Scene())                                         // absent group → 0 not
    }

    @Test fun firedPolarityMatchesKernel() {
        agreeFiredType(sumMF, Scene(qty = "60", bq = "50"))                  // Sum 110 > 100
        agreeFiredType(maxMF, Scene(qty = "60", bq = "30"))                  // Max 60 > 50
        agreeFiredType(minMF, Scene(qty = "5", bq = "30"))                   // Min 5 < 10
        agreeFiredType(ndvNum, Scene(qty = "5", bq = "30"))                  // 2 distinct == 2
        agreeFiredType(nffStar, Scene(items = listOf("x" to "10", "x" to "20")))  // 2 filled >= 2
        agreeFiredType(nffGroup, Scene(addrRow = true, street = "s"))        // 1 filled >= 1
    }

    private companion object {
        const val CN = "/Order/CustomerName"
        const val QTY = "/Order/Quantity"
        const val BQ = "/Order/BackorderQuantity"
        const val STREET = "/Order/ShippingAddress/Street"
        const val CITY = "/Order/ShippingAddress/City"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
