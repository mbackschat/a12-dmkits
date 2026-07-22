package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-platform (JVM + JS) lock for an enum CATEGORY projection ({@code [field -> Cat]}) as a member of the
 * field-list value-list quantifiers — the many-to-one category mapping and, crucially, the per-cell
 * three-way classification: an EMPTY enum via a category hop is an EMPTY cell (fired `No` → OMISSION, NOT a
 * present Unknown → VALUE), matching BOTH kernel strategies (adapter `EnumValueListUnionDiffTest`
 * tri-checks the kernel; this pins the same interpreter behavior on Node too). Priority's SpeedClass maps
 * STANDARD/EXPRESS/OVERNIGHT → NORMAL/FAST/FAST. (SPEC-2026-07-22-05.)
 */
class EnumCategoryValueListEvalTest {

    private val fields = listOf(
        FieldDef("/Order/Priority", Kind.STRING,
            enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"),
            enumCategories = mapOf("SpeedClass" to listOf("NORMAL", "FAST", "FAST"))),
    )

    /** Firing + polarity of [cond] under Priority = [priority] (null = empty), as "no-fire" / "VALUE" / "OMISSION". */
    private fun outcome(cond: String, priority: String?): String {
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "VL", "../Priority")))
        val doc = def.newDocument().group("/Order", 1)
        if (priority != null) doc.field("/Order/Priority", priority, 1, 1)
        return DmInterpreter(def).validate(doc).messages.firstOrNull { it.code == "VL" }
            ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" } ?: "no-fire"
    }

    private val no = "NoFieldValueIncludedInValueList(Priority -> SpeedClass In \"FAST\")"
    private val atLeastOne = "AtLeastOneFieldValueIncludedInValueList(Priority -> SpeedClass In \"FAST\")"
    private val notAll = "NotAllFieldValuesIncludedInValueList(Priority -> SpeedClass In \"FAST\")"

    @Test fun noEmptyCategoryCellFiresOmission() = assertEquals("OMISSION", outcome(no, null))
    @Test fun noPresentNonMatchingFiresValue() = assertEquals("VALUE", outcome(no, "STANDARD"))
    @Test fun noPresentMatchingDoesNotFire() = assertEquals("no-fire", outcome(no, "EXPRESS"))

    @Test fun atLeastOneManyToOneFires() = assertEquals("VALUE", outcome(atLeastOne, "EXPRESS"))
    @Test fun atLeastOneManyToOneOtherValueFires() = assertEquals("VALUE", outcome(atLeastOne, "OVERNIGHT"))
    @Test fun atLeastOneNoFireWhenMapsElsewhere() = assertEquals("no-fire", outcome(atLeastOne, "STANDARD"))

    @Test fun notAllPresentNotInFiresValue() = assertEquals("VALUE", outcome(notAll, "STANDARD"))
    @Test fun notAllEmptyDoesNotFire() = assertEquals("no-fire", outcome(notAll, null))
}
