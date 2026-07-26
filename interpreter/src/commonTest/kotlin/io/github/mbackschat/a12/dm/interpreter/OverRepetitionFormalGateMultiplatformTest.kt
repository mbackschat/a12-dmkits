package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.OVER_REPETITION_CONTEXT
import io.github.mbackschat.a12.dm.interpreter.eval.OVER_REPETITION_ROW
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The over-repetition ADDRESS-FORMAL gate (SPEC-2026-07-23-13) on the PURE engine, JVM AND JS/Node — the
 * cross-platform twin of `adapter.laws.OverRepetitionFormalGateDiffTest` (whose expectations are the real
 * kernel's, dual-strategy). The gate runs before scalar/custom checking, so a coordinate beyond the declared
 * `repeatability`:
 *  - exposes over-repetition and does NOT invoke a registered custom validator on the over-limit cell;
 *  - makes an absent descendant read UNAVAILABLE (a per-row rule is suppressed, not fired on empty→0);
 *  - is OUTSIDE every star aggregate / count domain (`Sum`, `NumberOfFilledGroups(G*)` range over in-cap rows).
 * The physical over-limit row stays instantiated (it still emits `zuGrosseZeile`).
 */
class OverRepetitionFormalGateMultiplatformTest {

    /** A registered custom validator is invoked on the in-cap cell but NEVER on the over-limit cell; the
     *  over-limit cell exposes over-repetition, not the custom rejection. */
    @Test
    fun overLimitCustomCellExposesOverRepetitionAndSkipsCallback() {
        val def = ModelDef(
            listOf(FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("PlanCode"))),
            emptyList(),
            repeatableGroups = setOf(PLANS), declaredReps = mapOf(PLANS to 2),
        )
        val seen = mutableListOf<String>()
        val validator = CustomFieldType { value, _ ->
            seen += value
            if (value == "AAA") CustomTypeRejection("PLAN_CODE_INVALID") else null
        }
        val doc = def.newDocument().group(REG, 1)
            .group(PLANS, 1, 1).field(CODE, "AAA", 1, 1, 1)   // in cap → validated, rejected
            .group(PLANS, 1, 2)                               // in cap, empty → not validated
            .group(PLANS, 1, 3).field(CODE, "BBB", 1, 3, 1)   // over cap → over-repetition, NOT validated

        val out = DmInterpreter(def, customFieldTypes = mapOf("PlanCode" to validator)).validate(doc)

        assertEquals(listOf("AAA"), seen, "the validator is invoked only on the in-cap cell — 'BBB' is never checked")
        assertEquals(1, out.messages.count { it.code == "PLAN_CODE_INVALID" }, "only the in-cap cell is rejected")
        assertTrue(out.messages.any { it.code == OVER_REPETITION_CONTEXT }, "the over-limit cell exposes zuGrosseKontextnummer")
        assertTrue(out.messages.any { it.code == OVER_REPETITION_ROW }, "the over-limit row exposes zuGrosseZeile")
    }

    /** A per-row `[Qty] < 5` fires on the in-cap empty rows (empty reads 0), but is SUPPRESSED on the over-limit
     *  row: its absent descendant reads unavailable, not clean empty. */
    @Test
    fun absentOverLimitDescendantReadsUnavailableNotCleanEmpty() {
        val def = ModelDef(
            listOf(FieldDef(QTY, Kind.NUMBER)),
            listOf(RuleDef("/Ord/Items/QLow", ITEMS, "[Qty] < 5", "QLOW", "../Qty")),
            repeatableGroups = setOf(ITEMS), declaredReps = mapOf(ITEMS to 2),
        )
        val doc = def.newDocument().group(ORD, 1)
            .group(ITEMS, 1, 1).group(ITEMS, 1, 2).group(ITEMS, 1, 3)   // all empty Qty; row 3 over cap 2

        val out = DmInterpreter(def).validate(doc)

        assertEquals(2, out.messages.count { it.code == "QLOW" }, "fires on the two in-cap empty rows, suppressed on the over-limit row")
        assertTrue(out.messages.any { it.code == OVER_REPETITION_ROW }, "the over-limit row still emits zuGrosseZeile")
    }

    /** `Sum` and `NumberOfFilledGroups(G*)` range over the declared (in-cap) rows only: the over-limit row is
     *  outside the star domain, so the aggregates are clean and definite (Sum 30, count 2), never poisoned. */
    @Test
    fun starAggregatesExcludeTheOverLimitRow() {
        val def = ModelDef(
            listOf(FieldDef(QTY, Kind.NUMBER)),
            listOf(
                RuleDef("/Ord/SumRule", ORD, "Sum(Items*/Qty) == 30", "SUM30", "../Items"),
                RuleDef("/Ord/CountRule", ORD, "NumberOfFilledGroups(Items*) == 2", "CNT2", "../Items"),
            ),
            repeatableGroups = setOf(ITEMS), declaredReps = mapOf(ITEMS to 2),
        )
        val doc = def.newDocument().group(ORD, 1)
            .group(ITEMS, 1, 1).field(QTY, "10", 1, 1, 1)
            .group(ITEMS, 1, 2).field(QTY, "20", 1, 2, 1)
            .group(ITEMS, 1, 3).field(QTY, "99", 1, 3, 1)   // over cap 2 — excluded from Sum and the count

        val codes = DmInterpreter(def).validate(doc).messages.map { it.code }.toSet()

        assertTrue("SUM30" in codes, "Sum over Items* is 30 (10+20), not 129 — the over-limit 99 is excluded")
        assertTrue("CNT2" in codes, "NumberOfFilledGroups(Items*) is 2, not 3 — the over-limit row is excluded")
    }

    @Test
    fun nonRepeatableGroupCoordinateAboveOneEmitsFixedRepetitionErrors() {
        val def = ModelDef(
            fields = listOf(FieldDef(REFERENCE, Kind.STRING)),
            rules = emptyList(),
            groupPaths = setOf(APPLICATION),
        )
        val doc = def.newDocument()
            .group(APPLICATION, 2)
            .field(REFERENCE, "APP-2", 2, 1)

        val messages = DmInterpreter(def).validate(doc).messages

        assertEquals(
            listOf(
                "zuGrosseLfdNummer@/Application",
                "zuGrosseLfdNummer@/Application[2]/Reference",
            ),
            messages.map { "${it.code}@${it.errorPath}" }.sorted(),
        )
    }

    @Test
    fun crossRootAggregateExcludesRowsBeneathAnOverLimitNonRepeatableGroup() {
        val def = ModelDef(
            fields = listOf(
                FieldDef(DASHBOARD_FLAG, Kind.STRING),
                FieldDef(AMOUNT, Kind.NUMBER),
            ),
            rules = listOf(
                RuleDef(
                    "/Dashboard/SumTen",
                    DASHBOARD,
                    "FieldFilled(/Dashboard/Flag) And Sum(/Application/Lines*/Amount) == 10",
                    "SUM_TEN",
                    "../Flag",
                ),
            ),
            repeatableGroups = setOf(APPLICATION_LINES),
            declaredReps = mapOf(APPLICATION_LINES to 2),
            groupPaths = setOf(DASHBOARD, APPLICATION, APPLICATION_LINES),
        )
        val doc = def.newDocument()
            .group(DASHBOARD, 1)
            .field(DASHBOARD_FLAG, "ready", 1, 1)
            .group(APPLICATION, 1)
            .group(APPLICATION_LINES, 1, 1)
            .field(AMOUNT, "10", 1, 1, 1)
            .group(APPLICATION, 2)
            .group(APPLICATION_LINES, 2, 1)
            .field(AMOUNT, "90", 2, 1, 1)

        val codes = DmInterpreter(def).validate(doc).messages.map { it.code }

        assertTrue("SUM_TEN" in codes, "the over-limit Application[2] amount is outside the cross-root Sum")
    }

    private companion object {
        const val REG = "/Registry"
        const val PLANS = "/Registry/Plans"
        const val CODE = "/Registry/Plans/Code"
        const val ORD = "/Ord"
        const val ITEMS = "/Ord/Items"
        const val QTY = "/Ord/Items/Qty"
        const val APPLICATION = "/Application"
        const val REFERENCE = "/Application/Reference"
        const val APPLICATION_LINES = "/Application/Lines"
        const val AMOUNT = "/Application/Lines/Amount"
        const val DASHBOARD = "/Dashboard"
        const val DASHBOARD_FLAG = "/Dashboard/Flag"
    }
}
