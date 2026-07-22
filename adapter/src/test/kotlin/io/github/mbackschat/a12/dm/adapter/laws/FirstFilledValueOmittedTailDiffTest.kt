package io.github.mbackschat.a12.dm.adapter.laws

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
 * Differential — the multi-operand `FirstFilledValue` "omitted tail" question (SPEC-2026-07-22-06). The
 * source-level prediction is that a star contributing only an OMITTED TAIL (declared capacity, no concrete
 * cell) should not taint a value a later operand supplies — `FirstValueCombiner.getValue` folds `omittedTail`
 * into `notGiven` only when `value == null`.
 *
 * <p>Verified against the real kernel (dynamic Groovy), that prediction does NOT manifest at runtime: for a
 * multi-operand `FirstFilledValue`, a first star that visits no concrete cell (an uninstantiated group, or an
 * instantiated-but-empty prefix, flat OR nested) reads as a NOT-GIVEN prefix before the fallback — the kernel's
 * `combineFeldListe` reaches the star's repetitions and `addValue`s a not-given cell, setting
 * `notGivenFieldBeforeFirstGiven`, so the later fallback is typed **OMISSION**, not VALUE. The interpreter's
 * `firstFilledScan` marks the same no-row star fillable, so both engines agree. This test LOCKS that agreement
 * (and guards against a change that would type such a firing VALUE and diverge from the kernel). A star that
 * actually selects a value is a VALUE control.
 */
class FirstFilledValueOmittedTailDiffTest {

    private val shipment = RuntimeLaws.model("models/first-filled-tail.dm.json")
    private val fields = listOf(
        FieldDef(TAG, Kind.STRING),
        FieldDef(WEIGHT, Kind.NUMBER, scale = 0),
        FieldDef(SEG, Kind.STRING),
        FieldDef(LOAD, Kind.NUMBER, scale = 0),
        FieldDef(DEF_WEIGHT, Kind.NUMBER, scale = 0),
    )
    private val m = BoundModel.ofJson(shipment)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Author the `/Shipment`-level rule, build the kernel + interpreter documents in lockstep via [build], and
     *  assert both engines agree on firing AND polarity (null = no fire). */
    private fun agree(cond: String, errorRel: String, build: (RuntimeLaws.Doc, Document) -> Unit, msg: String) {
        val kModel = RuntimeLaws.withRule(shipment, "/Shipment", "FF", errorRel, "FF", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Shipment/FF", "/Shipment", cond, "FF", errorRel)),
            repeatableGroups = setOf("/Shipment/Parcels", "/Shipment/Parcels/Segments"),
            declaredReps = mapOf("/Shipment/Parcels" to 1000, "/Shipment/Parcels/Segments" to 100),
            indexFields = setOf(TAG, SEG))
        val kDoc = RuntimeLaws.doc().group("/Shipment", 1)
        val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val kernel = RuntimeLaws.typeOf(kModel, "FF", kDoc).map { it.name }.orElse(null)
        val interp = DmInterpreter(def).validateFull(iDoc).firstOrNull { it.code == "FF" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val fb = m.field("/Shipment/DefaultWeight")
    private val flatGt10 = render(Dsl.firstFilledNumber(m.flatten("/Shipment/Parcels*/Weight"), fb).gt(10))
    private val nestedGt10 = render(Dsl.firstFilledNumber(m.flatten("/Shipment/Parcels*/Segments*/Load"), fb).gt(10))

    // A first star that visits no concrete cell reads as a not-given prefix → the fallback fires OMISSION.
    @Test fun flatNoRowStarBeforeFallbackTaintsToOmission() = agree(flatGt10, "../DefaultWeight",
        { k, i -> k.field(DEF_WEIGHT, "30", 1, 1); i.field(DEF_WEIGHT, "30", 1, 1) },
        "flat no-row star then fallback 30 → OMISSION (kernel reads the star as a not-given prefix)")

    @Test fun nestedNoRowStarBeforeFallbackTaintsToOmission() = agree(nestedGt10, "../DefaultWeight",
        { k, i ->
            k.group("/Shipment/Parcels", 1, 1).field(TAG, "T1", 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
            i.field(TAG, "T1", 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
        },
        "nested star (parcel row, no segments) then fallback 30 → OMISSION")

    @Test fun instantiatedEmptyPrefixBeforeFallbackIsOmission() = agree(flatGt10, "../DefaultWeight",
        { k, i ->
            k.group("/Shipment/Parcels", 1, 1).field(TAG, "T1", 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
            i.field(TAG, "T1", 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
        },
        "one instantiated empty Weight row then fallback 30 → OMISSION")

    // A star that actually selects a value is fixed → VALUE (the fallback is never read).
    @Test fun starSelectsValueIsValue() = agree(nestedGt10, "../DefaultWeight",
        { k, i ->
            k.group("/Shipment/Parcels", 1, 1).field(TAG, "T1", 1, 1, 1)
                .group("/Shipment/Parcels/Segments", 1, 1, 1).field(SEG, "S1", 1, 1, 1, 1)
                .field(LOAD, "20", 1, 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
            i.field(SEG, "S1", 1, 1, 1, 1).field(LOAD, "20", 1, 1, 1, 1).field(DEF_WEIGHT, "30", 1, 1)
        },
        "nested star selects Load 20 (> 10) → VALUE")

    private companion object {
        const val TAG = "/Shipment/Parcels/Tag"
        const val WEIGHT = "/Shipment/Parcels/Weight"
        const val SEG = "/Shipment/Parcels/Segments/SegId"
        const val LOAD = "/Shipment/Parcels/Segments/Load"
        const val DEF_WEIGHT = "/Shipment/DefaultWeight"
    }
}
