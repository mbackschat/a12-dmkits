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
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the enum field-list value-list quantifiers with UNION admission and CATEGORY projections
 * (SPEC-2026-07-22-05). Firing AND exact polarity must match the kernel; the empty-enum-via-category `No`
 * case is the one the interpreter used to get wrong (VALUE instead of OMISSION), tri-checked against BOTH
 * kernel strategies. The order fixture's `Priority` (STANDARD/EXPRESS/OVERNIGHT, category SpeedClass =
 * NORMAL/FAST/FAST — a many-to-one map) and `RushLevel` (1/2/3) are two enums with DISJOINT stored domains.
 * Conditions are authored with the typed DSL and rendered to the canonical string fed to both engines.
 */
class EnumValueListUnionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(PRIORITY, Kind.STRING,
            enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"),
            enumCategories = mapOf("SpeedClass" to listOf("NORMAL", "FAST", "FAST"))),
        FieldDef(RUSH, Kind.STRING, enumValues = listOf("1", "2", "3")),
    )
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** Compare kernel (Groovy-dynamic) vs interpreter — firing AND polarity — for the rule under [priority]/[rush]. */
    private fun agree(cond: Condition, priority: String?, rush: String?, msg: String) {
        val text = render(cond)
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../Priority", "VL", text)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", text, "VL", "../Priority")))
        val iDoc = def.newDocument().group("/Order", 1)
        if (priority != null) { kDoc.field(PRIORITY, priority, 1, 1); iDoc.field(PRIORITY, priority, 1, 1) }
        if (rush != null) { kDoc.field(RUSH, rush, 1, 1); iDoc.field(RUSH, rush, 1, 1) }
        val k = RuntimeLaws.typeOf(kModel, "VL", kDoc).map { it.name }.orElse("no-fire")
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "VL" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" } ?: "no-fire"
        assertEquals(k, i, "$msg  (kernel=$k interp=$i; $text)")
    }

    /** Tri-check firing+polarity across Groovy-dynamic, generated-Java, AND the interpreter for [priority]. */
    private fun tri(cond: Condition, priority: String?, expected: String, msg: String) {
        val text = render(cond)
        val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../Priority", "VL", text)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        if (priority != null) kDoc.field(PRIORITY, priority, 1, 1)
        val groovy = RuntimeLaws.kernelEngine(kModel)
        val java = RuntimeLaws.kernelJavaEngine(kModel)
        val g = typeFromSigs(groovy.validationSignaturesSorted(groovy.instance(kDoc)))
        val j = typeFromSigs(java.validationSignaturesSorted(java.instance(kDoc)))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", text, "VL", "../Priority")))
        val iDoc = def.newDocument().group("/Order", 1)
        if (priority != null) iDoc.field(PRIORITY, priority, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.firstOrNull { it.code == "VL" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" } ?: "no-fire"
        assertEquals(expected, g, "$msg  groovy ($text)")
        assertEquals(g, j, "$msg  groovy vs generated-Java ($text)")
        assertEquals(g, i, "$msg  kernel vs interpreter ($text)")
    }

    private fun typeFromSigs(sigs: List<String>): String =
        sigs.firstOrNull { it.startsWith("VL|") }?.split("|")?.getOrNull(1) ?: "no-fire"

    private fun cat() = m.enumOf(PRIORITY).category("SpeedClass")

    // --- UNION over two disjoint direct enum domains ------------------------------------------------
    @Test fun unionFiresViaFirstField() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("STANDARD", "1"), m.enumOf(PRIORITY), m.enumOf(RUSH)),
            "STANDARD", null, "union: STANDARD hits Priority")
    @Test fun unionFiresViaSecondField() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("STANDARD", "1"), m.enumOf(PRIORITY), m.enumOf(RUSH)),
            null, "1", "union: 1 hits RushLevel")
    @Test fun unionNoFireWhenNeither() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("STANDARD", "1"), m.enumOf(PRIORITY), m.enumOf(RUSH)),
            "EXPRESS", "2", "union: neither present value is listed")

    // --- category many-to-one (fields side) --------------------------------------------------------
    @Test fun categoryManyToOneFires() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("FAST"), cat()), "EXPRESS", null, "EXPRESS->FAST fires")
    @Test fun categoryManyToOneOtherValueFires() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("FAST"), cat()), "OVERNIGHT", null, "OVERNIGHT->FAST fires")
    @Test fun categoryNoFireWhenMapsElsewhere() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("FAST"), cat()), "STANDARD", null, "STANDARD->NORMAL no fire")

    // --- No + category polarity (the regressed case), tri-checked -----------------------------------
    @Test fun noCategoryEmptyFiresOmission() =
        tri(Dsl.noFieldValueIncludedInValueList(listOf("FAST"), cat()), null, "OMISSION_ERROR",
            "No + empty enum via category → OMISSION (was VALUE)")
    @Test fun noCategoryPresentNoMatchFiresValue() =
        tri(Dsl.noFieldValueIncludedInValueList(listOf("FAST"), cat()), "STANDARD", "VALUE_ERROR",
            "No + STANDARD->NORMAL (not FAST) → fires VALUE")
    @Test fun noCategoryPresentMatchNoFire() =
        agree(Dsl.noFieldValueIncludedInValueList(listOf("FAST"), cat()), "EXPRESS", null, "No + EXPRESS->FAST → no fire")

    // --- NotAll + category -------------------------------------------------------------------------
    @Test fun notAllCategoryPresentNotInFires() =
        agree(Dsl.notAllFieldValuesIncludedInValueList(listOf("FAST"), cat()), "STANDARD", null, "NotAll + NORMAL not in {FAST} fires")
    @Test fun notAllCategoryEmptyNoFire() =
        agree(Dsl.notAllFieldValuesIncludedInValueList(listOf("FAST"), cat()), null, null, "NotAll + empty → no fire")

    // --- mixed direct + category members (fields side) ---------------------------------------------
    @Test fun mixedDirectAndCategoryFires() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf("STANDARD", "FAST"), m.enumOf(PRIORITY), cat()),
            "STANDARD", null, "mixed: STANDARD hits the direct Priority ref")

    // --- values side carries a category projection (entity-valued) — classification covered too -----
    @Test fun valuesSideCategoryNoFireDisjoint() =
        agree(Dsl.atLeastOneFieldValueIncludedInValueList(listOf<io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand>(cat().listOperand()),
            m.enumOf(RUSH).field()), "STANDARD", "1", "RushLevel In Priority->SpeedClass — disjoint, no fire")
    @Test fun valuesSideCategoryEmptyNo() =
        agree(Dsl.noFieldValueIncludedInValueList(listOf<io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand>(cat().listOperand()),
            m.enumOf(RUSH).field()), null, "1", "No RushLevel In (empty Priority->SpeedClass)")

    private companion object {
        const val PRIORITY = "/Order/Priority"
        const val RUSH = "/Order/RushLevel"
    }
}
