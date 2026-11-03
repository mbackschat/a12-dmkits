package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for SPEC-2026-07-29-01's exact-instant matrix. A constructed Date and a
 * Date-valued shift each retain the instant their OWN resolution selected, and direct Date equality observes
 * it. All three shapes below land on the civil label `1916-10-01`, the one ambiguous midnight in Berlin's
 * pinned history, so the label cannot separate them:
 *
 *  - fresh construction takes the **earlier** (CEST) copy (kernel 31.1.1, KERNEL-FINDINGS KF665);
 *  - a **day**, **month** or **year** mutation keeps its SOURCE's offset where the overlap admits it, so a CEST
 *    source meets the fresh copy and a CET source (`1915-10-01`, `1916-04-01`) takes the later copy and does not.
 *
 * Each row carries its `!=` inverse, so no row can pass by the comparison going UNKNOWN. Kernel-verified on
 * both strategies in `adapter.laws.ConstructedDateInstantDiffTest`, which is also mutation-checked: disabling
 * day retention makes the kernel and the interpreter disagree on the first row.
 */
class ConstructedDateInstantTest {

    private fun fires(condition: String, zone: ModelTimeZone = ModelTimeZone.EUROPE_BERLIN): Boolean {
        val def = ModelDef(
            fields = listOf(FieldDef(NAME, Kind.STRING)),
            rules = listOf(RuleDef("/Order/R", "/Order", condition, "CDI", "../CustomerName")),
            timeZone = zone,
        )
        val doc = def.newDocument().group("/Order", 1).field(NAME, "anyone", 1, 1)
        return DmInterpreter(def).validateFull(doc).any { it.code == "CDI" }
    }

    private fun guarded(op: String) = "FieldFilled($NAME) And $op"
    private val octoberFirst = "Date(\"1\", \"10\", \"1916\")"

    @Test fun aDayShiftFromASummerSourceMeetsTheFreshCopy() {
        assertEquals(true, fires(guarded("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == $octoberFirst")),
            "the day step keeps its CEST source offset, as fresh construction takes it")
        assertEquals(false, fires(guarded("AddDays(Date(\"30\", \"9\", \"1916\"), 1) != $octoberFirst")))
    }

    @Test fun aMonthShiftFromASummerSourceMeetsTheFreshCopy() {
        assertEquals(true, fires(guarded("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) == $octoberFirst")),
            "the month step keeps its CEST source offset")
        assertEquals(false, fires(guarded("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) != $octoberFirst")))
    }

    @Test fun aYearShiftFromAWinterSourceTakesTheLaterCopy() {
        assertEquals(false, fires(guarded("AddYears(Date(\"1\", \"10\", \"1915\"), 1) == $octoberFirst")),
            "the year step keeps its CET source offset, so it misses the fresh copy")
        assertEquals(true, fires(guarded("AddYears(Date(\"1\", \"10\", \"1915\"), 1) != $octoberFirst")))
    }

    /** Separates source-offset preference from a per-operation rule: day and month steps from a CET source miss too. */
    @Test fun dayAndMonthShiftsFromAWinterSourceTakeTheLaterCopy() {
        assertEquals(false, fires(guarded("AddDays(Date(\"1\", \"4\", \"1916\"), 183) == $octoberFirst")))
        assertEquals(false, fires(guarded("AddMonths(Date(\"1\", \"4\", \"1916\"), 6) == $octoberFirst")))
    }

    /** On an unambiguous midnight every mutation agrees with fresh construction — the discrimination above is
     *  specific to the one ambiguous midnight, not a blanket change to date equality. */
    @Test fun anUnambiguousMidnightKeepsEveryMutationEqual() {
        val june = "Date(\"2\", \"6\", \"1917\")"
        assertEquals(true, fires(guarded("AddDays(Date(\"1\", \"6\", \"1917\"), 1) == $june")))
        assertEquals(true, fires(guarded("AddMonths(Date(\"2\", \"5\", \"1917\"), 1) == $june")))
        assertEquals(true, fires(guarded("AddYears(Date(\"2\", \"6\", \"1916\"), 1) == $june")))
    }

    /** Under UTC there are no offset seasons, so every mutation and fresh construction coincide. */
    @Test fun underUtcEveryMutationAgrees() {
        assertEquals(true, fires(guarded("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == $octoberFirst"),
            ModelTimeZone.UTC), "no overlap exists under UTC")
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
    }
}
