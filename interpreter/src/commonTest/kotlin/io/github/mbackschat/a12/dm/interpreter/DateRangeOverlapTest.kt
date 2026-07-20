package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.rangesOverlap
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Multiplatform (JVM + JS) lock for the `DateRangesOverlap` / `AtLeastOneDateRangeOverlaps` semantics (IF72) —
 * the PURE interpreter (parser + eval), so the parser accepting a `Having` on a boolean predicate's rep-list arg
 * and the any-pair / keep-law / reached-filter-polarity evaluation run on Kotlin/JS too. The direct resolved-range
 * control also keeps the defensive inversion guard distinct from stored-value formal checking. The values here
 * are the ones the kernel tri-check locks in `adapter.laws.DateRangeOverlapSemanticsDiffTest`
 * (groovy == java-static); this proves the kernel-free build reproduces them. Own-domain (an insurance/e-commerce
 * order with coverage windows). `dd.MM.yyyy` ranges separated by `-`.
 */
class DateRangeOverlapTest {

    private val cov = listOf("01.01.2026-31.01.2026", "15.01.2026-15.02.2026", "01.03.2026-31.03.2026")

    private fun model(group: String, errRel: String, cond: String) = ModelDef(
        fields = listOf(
            FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
            FieldDef(SKU, Kind.STRING),
            FieldDef(IC, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
            FieldDef(CNT, Kind.NUMBER, scale = 0),
        ),
        rules = listOf(RuleDef("$group/R", group, cond, "OV", errRel)),
        repeatableGroups = setOf(ITEMS),
        declaredReps = mapOf(ITEMS to 50),
        indexFields = setOf(SKU),
    )

    private fun doc(m: ModelDef, window: String?, coverages: List<String?>): Document = m.newDocument().also { d ->
        if (window != null) d.field(CW, window, 1, 1)
        coverages.forEachIndexed { i, c ->
            val r = i + 1
            d.field(SKU, "S$r", 1, r, 1)
            if (c != null) d.field(IC, c, 1, r, 1)
        }
    }

    private fun fires(group: String, errRel: String, cond: String, window: String?, coverages: List<String?>): Int {
        val m = model(group, errRel, cond)
        return DmInterpreter(m).validateFull(doc(m, window, coverages)).count { it.code == "OV" }
    }

    private fun polarity(group: String, errRel: String, cond: String, window: String?, coverages: List<String?>): String? =
        model(group, errRel, cond).let { m ->
            DmInterpreter(m).validateFull(doc(m, window, coverages)).firstOrNull { it.code == "OV" }
                ?.let { if (it.type == MsgType.OMISSION) "OMISSION" else "VALUE" }
        }

    // ---- any-pair among ALL --------------------------------------------------------------------------------

    @Test fun selfPairFiresEveryRow() = assertEquals(3,
        fires("/Order/Items", "../ItemCoverage", "DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage)", null, cov))

    @Test fun listInternalPairFires() = assertEquals(1,
        fires("/Order", "../CoverageWindow", "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)",
            "01.06.2026-30.06.2026", cov))

    @Test fun noPairNoFire() = assertEquals(0,
        fires("/Order", "../CoverageWindow", "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)",
            "01.06.2026-30.06.2026", listOf("01.01.2026-31.01.2026", "01.09.2026-30.09.2026", "01.12.2026-31.12.2026")))

    @Test fun invalidRangeNeverOverlaps() = assertEquals(0,
        fires("/Order", "../CoverageWindow", "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)",
            "30.06.2026-01.06.2026", listOf("01.05.2026-31.07.2026", "01.09.2026-30.09.2026", null)))

    /** The resolved interval relation retains its own inverted-range guard even when a caller bypasses the
     * stored field's earlier formal check. */
    @Test fun resolvedInvertedRangeNeverOverlaps() = assertFalse(
        rangesOverlap(
            Val.Range("2026-06-30", "2026-06-01"),
            Val.Range("2026-05-01", "2026-07-31"),
        ),
    )

    // ---- the Having keep-law (`$` unbound → trivially TRUE; malformed → drops) ------------------------------

    private fun having(f: String) = "DateRangesOverlap(ItemCoverage, ../Items*/ItemCoverage Having ($f))"

    @Test fun alwaysFalseFilterDropsAll() = assertEquals(0,
        fires("/Order/Items", "../ItemCoverage", having("CurrentRepetition(/Order/Items) != CurrentRepetition(/Order/Items)"), null, cov))

    @Test fun dollarSelfExclusionKeepsAll() = assertEquals(3,
        fires("/Order/Items", "../ItemCoverage", having("CurrentRepetition(/Order/Items) != CurrentRepetition(\$/Order/Items)"), null, cov))

    @Test fun dollarSelfInclusionKeepsAll() = assertEquals(3,
        fires("/Order/Items", "../ItemCoverage", having("CurrentRepetition(/Order/Items) == CurrentRepetition(\$/Order/Items)"), null, cov))

    @Test fun falseAndDollarDropsAll() = assertEquals(0,
        fires("/Order/Items", "../ItemCoverage",
            having("CurrentRepetition(/Order/Items) != CurrentRepetition(/Order/Items) " +
                "And CurrentRepetition(/Order/Items) == CurrentRepetition(\$/Order/Items)"), null, cov))

    /** A malformed filter operand (`abc` in a scale-0 Count) → the comparison is UNKNOWN → the row DROPS (unlike
     *  the `$`-unbound term, which keeps) → set is arg0 only per row → 0. */
    @Test fun malformedFilterDropsAll() {
        val m = model("/Order/Items", "../ItemCoverage", having("[Count] <= 2"))
        val d = doc(m, null, cov).also { for (r in 1..3) it.field(CNT, "abc", 1, r, 1) }
        assertEquals(0, DmInterpreter(m).validateFull(d).count { it.code == "OV" })
    }

    // ---- AtLeastOneDateRangeOverlaps — scalar-vs-list ------------------------------------------------------

    @Test fun atLeastOneScalarOverlapsFires() = assertEquals(1,
        fires("/Order", "../CoverageWindow", "AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)",
            "10.01.2026-20.01.2026", cov))

    @Test fun atLeastOneEmptyScalarNoFire() = assertEquals(0,
        fires("/Order", "../CoverageWindow", "AtLeastOneDateRangeOverlaps(CoverageWindow In Items*/ItemCoverage)",
            null, cov))

    // ---- filter-derived polarity ---------------------------------------------------------------------------

    @Test fun noFilterPolarityValue() = assertEquals("VALUE",
        polarity("/Order", "../CoverageWindow", "DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)",
            "10.01.2026-20.01.2026", cov))

    @Test fun filterBearingPolarityOmission() = assertEquals("OMISSION",
        polarity("/Order/Items", "../ItemCoverage", having("CurrentRepetition(/Order/Items) <= 3"), null, cov))

    private companion object {
        const val CW = "/Order/CoverageWindow"
        const val SKU = "/Order/Items/Sku"
        const val IC = "/Order/Items/ItemCoverage"
        const val CNT = "/Order/Items/Count"
        const val ITEMS = "/Order/Items"
    }
}
