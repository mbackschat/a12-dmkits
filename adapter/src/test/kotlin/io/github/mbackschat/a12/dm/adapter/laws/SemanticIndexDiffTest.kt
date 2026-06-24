package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `SemanticIndex` (`[Items/Count For "SKU-1"]`): the field's value in the repetition whose
 * INDEX field (`Sku`) equals the given value. Kernel-probed: a matched filled cell yields its value; an
 * absent row OR an empty matched cell reads as the kind's EMPTY value (a number → 0, fillable) — so a
 * comparison fires VALUE on a present value, OMISSION on the empty/absent case. Locks both firing and polarity.
 */
class SemanticIndexDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(CN, Kind.STRING), FieldDef(SKU, Kind.STRING), FieldDef(COUNT, Kind.NUMBER))

    /** [cond] over Items rows = [rows] (sku, count); compare kernel vs interpreter (firing + polarity). */
    private fun agree(cond: String, rows: List<Pair<String, String?>>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "SI", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "SI", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions).field(CN, "Acme", 1, 1)
        rows.forEachIndexed { i, (sku, count) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field(SKU, sku, 1, r, 1)
            if (count != null) { kDoc.field(COUNT, count, 1, r, 1); iDoc.field(COUNT, count, 1, r, 1) }
        }
        val k = RuntimeLaws.typeOf(kModel, "SI", kDoc).map { it.name }.orElse(null)
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "SI" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(k, i, "$msg  (kernel=$k interp=$i)")
    }

    private val present = listOf("SKU-1" to "5", "SKU-2" to "3")
    private val emptyCount = listOf("SKU-1" to null, "SKU-2" to "3")
    private val noKey = listOf("SKU-2" to "3")

    @Test fun matchedValueGreater() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] > 0", present, "SKU-1 count 5 > 0 → VALUE")
    @Test fun matchedValueEquals() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] == 5", present, "SKU-1 count == 5")
    @Test fun matchedValueNotEquals() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] == 9", present, "SKU-1 count != 9")
    @Test fun otherKey() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-2\"] == 3", present, "SKU-2 count == 3")

    // Absent row / empty matched cell → empty value (0), fillable → OMISSION on == 0, no fire on > 0 / == 5.
    @Test fun emptyCellEqualsZeroIsOmission() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] == 0", emptyCount, "empty SKU-1 count → 0 → OMISSION")
    @Test fun emptyCellGreaterZeroNoFire() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] > 0", emptyCount, "empty SKU-1 count → 0 not > 0")
    @Test fun missingKeyEqualsZeroIsOmission() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] == 0", noKey, "no SKU-1 row → 0 → OMISSION")
    @Test fun missingKeyEqualsValueNoFire() = agree("FieldFilled(CustomerName) And [Items/Count For \"SKU-1\"] == 5", noKey, "no SKU-1 row → 0 != 5")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
