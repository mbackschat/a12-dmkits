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
 *  - the **day** mutation carries the source instant → keeps the earlier (CEST) copy → **not equal** to fresh;
 *  - **month** and **year** mutation resolve the mutated fields at compute time → later (CET) copy → **equal**.
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

    @Test fun aDayShiftRetainsTheEarlierOverlapCopy() {
        assertEquals(false, fires(guarded("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == $octoberFirst")),
            "the day step keeps its CEST source season; fresh construction is CET")
        assertEquals(true, fires(guarded("AddDays(Date(\"30\", \"9\", \"1916\"), 1) != $octoberFirst")))
    }

    @Test fun aMonthShiftSelectsTheLaterOverlapCopy() {
        assertEquals(true, fires(guarded("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) == $octoberFirst")),
            "compute-time field resolution takes the later copy, equal to fresh construction")
        assertEquals(false, fires(guarded("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) != $octoberFirst")))
    }

    @Test fun aYearShiftSelectsTheLaterOverlapCopy() {
        assertEquals(true, fires(guarded("AddYears(Date(\"1\", \"10\", \"1915\"), 1) == $octoberFirst")),
            "the same compute-time resolution as the month arm")
        assertEquals(false, fires(guarded("AddYears(Date(\"1\", \"10\", \"1915\"), 1) != $octoberFirst")))
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
