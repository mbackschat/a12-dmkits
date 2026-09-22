package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
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

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines. `m.num(p).forIndex("key")` is the literal semantic index `[field For "key"]`; absolute paths render.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** [cond] over Items rows = [rows] (sku, count); compare kernel vs interpreter (firing + polarity). */
    private fun agree(cond: String, rows: List<Pair<String, String?>>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "SI", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "SI", "../CustomerName")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50), indexFields = setOf(SKU))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(CN, "Acme", 1, 1)
        val iDoc = def.newDocument().field(CN, "Acme", 1, 1)
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

    @Test fun matchedValueGreater() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").gt(0))), present, "SKU-1 count 5 > 0 → VALUE")
    @Test fun matchedValueEquals() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").eq(5))), present, "SKU-1 count == 5")
    @Test fun matchedValueNotEquals() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").eq(9))), present, "SKU-1 count != 9")
    @Test fun otherKey() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-2").eq(3))), present, "SKU-2 count == 3")

    // Absent row / empty matched cell → empty value (0), fillable → OMISSION on == 0, no fire on > 0 / == 5.
    @Test fun emptyCellEqualsZeroIsOmission() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").eq(0))), emptyCount, "empty SKU-1 count → 0 → OMISSION")
    @Test fun emptyCellGreaterZeroNoFire() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").gt(0))), emptyCount, "empty SKU-1 count → 0 not > 0")
    @Test fun missingKeyEqualsZeroIsOmission() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").eq(0))), noKey, "no SKU-1 row → 0 → OMISSION")
    @Test fun missingKeyEqualsValueNoFire() = agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("SKU-1").eq(5))), noKey, "no SKU-1 row → 0 != 5")

    // SPEC-2026-07-23-08 — the kind split: a STRING index field keeps EXACT stored-text identity, so the same
    // "5" / "5.00" pair that a Number index field normalizes together stays DISTINCT here. The stored key is
    // "5.00"; the exact spelling matches (count 5 → VALUE), the numerically-equal spelling "5" does NOT match
    // (no row → empty-as-0 → OMISSION). A fix that normalized every kind would wrongly match "5" and fail this.
    private val stringNumericKey = listOf("5.00" to "5", "SKU-2" to "3")
    @Test fun stringKeyExactSpellingMatchesTheStoredRow() =
        agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("5.00").eq(5))), stringNumericKey,
            "String index: requested \"5.00\" exactly matches stored \"5.00\" → count 5 → VALUE")
    @Test fun stringKeyDifferentNumericSpellingStaysDistinct() =
        agree(render(m.str(CN).filled().and(m.num(COUNT).forIndex("5").eq(0))), stringNumericKey,
            "String index: requested \"5\" does NOT match stored \"5.00\" → no row → empty-as-0 → OMISSION")

    private companion object {
        const val CN = "/Order/CustomerName"
        const val SKU = "/Order/Items/Sku"
        const val COUNT = "/Order/Items/Count"
    }
}
