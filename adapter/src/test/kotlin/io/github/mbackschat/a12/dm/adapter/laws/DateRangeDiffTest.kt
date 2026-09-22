package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **date-range endpoint extractors** `StartOfDateRange` / `EndOfDateRange` over a
 * `DateRangeType` field (`CoverageWindow`, format `dd.MM.yyyy`, separator `-`). The interpreter must parse
 * the rendered range value (`01.06.2021-30.06.2021`) into its two ISO endpoints (format-aware, so the date
 * extractors read them like any date) and agree with the kernel on firing. (`DateRangesOverlap` and the
 * starred/`IN` forms are a follow-up; the format foundation lands here.)
 */
class DateRangeDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(OD, Kind.DATE),
        FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    )

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** `<cond>` over OrderDate filled + CoverageWindow = `coverage` — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, coverage: String, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RR", "../OrderDate", "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RR", "/Order", cond, "RR", "../OrderDate")))

        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val iDoc = def.newDocument()
            .field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val cov = "01.06.2021-30.06.2021"   // 1 Jun 2021 .. 30 Jun 2021

    @Test fun startYearFires() = agree(render(m.date(OD).filled().and(Dsl.yearFromDate(Dsl.startOfDateRange(m.dateRange(CW))).gt(2020))), cov, "start year 2021 > 2020")
    @Test fun startYearDoesNotFire() = agree(render(m.date(OD).filled().and(Dsl.yearFromDate(Dsl.startOfDateRange(m.dateRange(CW))).gt(2021))), cov, "start year 2021 not > 2021")
    @Test fun startMonth() = agree(render(m.date(OD).filled().and(Dsl.monthFromDate(Dsl.startOfDateRange(m.dateRange(CW))).eq(6))), cov, "start month is 6")
    @Test fun endDayFires() = agree(render(m.date(OD).filled().and(Dsl.dayFromDate(Dsl.endOfDateRange(m.dateRange(CW))).gt(28))), cov, "end day 30 > 28")
    @Test fun endDayDoesNotFire() = agree(render(m.date(OD).filled().and(Dsl.dayFromDate(Dsl.endOfDateRange(m.dateRange(CW))).gt(30))), cov, "end day 30 not > 30")

    // ---- DateRangesOverlap(scalar, starred) — closed intervals, any-row overlap (KERNEL-FINDINGS §6) -----

    private val overlapFields = fields + FieldDef(IC, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-")
    private val items = "/Order/Items*/ItemCoverage"
    private val overlapCond = render(m.date(OD).filled().and(Dsl.dateRangesOverlap(m.field(CW), m.flatten(items))))
    // The `In`-syntax sibling shares these scalar-vs-list closed-interval controls, but not DateRangesOverlap's
    // any-pair shape or reached-filter polarity (KERNEL-FINDINGS §6). The token is distinct from value-list `In`.
    private val atLeastOneCond = render(m.date(OD).filled().and(Dsl.atLeastOneDateRangeOverlaps(m.field(CW), m.flatten(items))))

    /** [cond] (rule [code]) over CoverageWindow vs N ItemCoverage rows — kernel and interpreter agree on firing. */
    private fun agreeOverlap(cond: String, code: String, window: String, itemCoverages: List<String>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", code, "../OrderDate", code, cond)
        val def = ModelDef(overlapFields, listOf(RuleDef("/Order/$code", "/Order", cond, code, "../OrderDate")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(CW, window, 1, 1)
        val iDoc = def.newDocument()
            .field(OD, "2024-06-01", 1, 1).field(CW, window, 1, 1)
        itemCoverages.forEachIndexed { i, ic ->
            kDoc.group("/Order/Items", 1, i + 1).field(IC, ic, 1, i + 1, 1)
            iDoc.group("/Order/Items", 1, i + 1).field(IC, ic, 1, i + 1, 1)
        }
        val kernel = RuntimeLaws.fires(kModel, code, kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == code }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private fun overlapAgree(window: String, items: List<String>, msg: String) = agreeOverlap(overlapCond, "CC", window, items, msg)
    private fun atLeastOneAgree(window: String, items: List<String>, msg: String) = agreeOverlap(atLeastOneCond, "AO", window, items, msg)

    @Test fun touchingRangesOverlap() = overlapAgree("01.01.2024-31.01.2024", listOf("31.01.2024-28.02.2024"), "touching ranges overlap (closed)")
    @Test fun backToBackDoNot() = overlapAgree("01.01.2024-31.01.2024", listOf("01.02.2024-28.02.2024"), "adjacent-day ranges do not overlap")
    @Test fun containedOverlaps() = overlapAgree("01.01.2024-31.12.2024", listOf("01.06.2024-30.06.2024"), "a contained range overlaps")
    @Test fun anyRowOverlaps() = overlapAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "15.06.2024-15.07.2024"), "overlaps any row (2nd)")
    @Test fun noRowOverlaps() = overlapAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "01.02.2025-28.02.2025"), "no row overlaps")

    // AtLeastOneDateRangeOverlaps (IN syntax) — shared scalar-vs-list boundary controls only.
    @Test fun inTouchingOverlaps() = atLeastOneAgree("01.01.2024-31.01.2024", listOf("31.01.2024-28.02.2024"), "IN: touching ranges overlap")
    @Test fun inBackToBackDoNot() = atLeastOneAgree("01.01.2024-31.01.2024", listOf("01.02.2024-28.02.2024"), "IN: adjacent-day ranges do not overlap")
    @Test fun inContainedOverlaps() = atLeastOneAgree("01.01.2024-31.12.2024", listOf("01.06.2024-30.06.2024"), "IN: a contained range overlaps")
    @Test fun inAnyRowOverlaps() = atLeastOneAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "15.06.2024-15.07.2024"), "IN: overlaps any row (2nd)")
    @Test fun inNoRowOverlaps() = atLeastOneAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "01.02.2025-28.02.2025"), "IN: no row overlaps")

    private companion object {
        const val OD = "/Order/OrderDate"
        const val CW = "/Order/CoverageWindow"
        const val IC = "/Order/Items/ItemCoverage"
    }
}
