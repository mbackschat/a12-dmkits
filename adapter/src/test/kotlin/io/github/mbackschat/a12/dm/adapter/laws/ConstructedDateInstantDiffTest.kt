package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — SPEC-2026-07-29-01's exact-instant matrix. Direct Date equality is the observer: it reads the
 * underlying instant and introduces no second calendar mutation that could share and mask the resolver under
 * test. All three shapes land on the SAME civil label, `1916-10-01`, whose midnight is the one ambiguous
 * midnight in Berlin's pinned history — so the label cannot separate them and the instant must.
 *
 *  - **fresh construction** resolves midnight the parse way, which on kernel 31.1.1 is the EARLIER (CEST) copy
 *    (KERNEL-FINDINGS KF665; 30.8.1 took the later, CET copy);
 *  - a **day**, **month** or **year** mutation lands on the copy whose offset its SOURCE carried: from a CEST
 *    source (`Date(30,9,1916)`, `Date(1,9,1916)`) it meets the fresh copy, from a CET source (`Date(1,10,1915)`,
 *    `Date(1,4,1916)`) it takes the later copy and does not. The CET-source day and month rows separate this from a
 *    per-operation account, under which the year shift alone would differ (30.8.1 split by operation instead).
 *
 * Every row carries its `!=` inverse control, so a row cannot pass by the comparison silently going UNKNOWN.
 * Tri-checked: dynamic-Groovy (normative, IF136), static-Java, interpreter. The JVM/Node half is
 * `interpreter ConstructedDateInstantTest`; joint kernel authorability of the shape is
 * `rulekit.validate.laws.DateComponentConstantLawsTest`.
 */
class ConstructedDateInstantDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")   // declares Europe/Berlin
    private val m = BoundModel.ofJson(order)

    private fun num(v: String) = Dsl.number(v)
    private fun octoberFirst(): DateExpr = Dsl.date(num("1"), num("10"), num("1916"))

    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private fun guarded(c: Condition): Condition = m.field(NAME).filled().and(c)

    private val dayShift = Dsl.addDays(Dsl.date(num("30"), num("9"), num("1916")), 1L)
    private val monthShift = Dsl.addMonths(Dsl.date(num("1"), num("9"), num("1916")), 1L)
    private val yearShift = Dsl.addYears(Dsl.date(num("1"), num("10"), num("1915")), 1L)
    private val winterDayShift = Dsl.addDays(Dsl.date(num("1"), num("4"), num("1916")), 183L)
    private val winterMonthShift = Dsl.addMonths(Dsl.date(num("1"), num("4"), num("1916")), 6L)

    /** groovy-dynamic == java-static == interpreter, with the kernel outcome itself pinned by [fires]. */
    private fun triCheck(condition: Condition, fires: Boolean, msg: String) {
        val cond = render(guarded(condition))
        val model = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", CODE, cond)
        val doc = RuntimeLaws.doc().group("/Order", 1).field(NAME, "anyone", 1, 1)
        val groovy = RuntimeLaws.messages(model, doc).filter { it.code() == CODE }.map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(doc)).filter { it.code() == CODE }
            .map { it.code() }.sorted()
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field(NAME, "anyone", 1, 1)
        val interp = DmInterpreter(def).validateFull(iDoc).filter { it.code == CODE }.map { it.code }.sorted()
        assertEquals(fires, groovy.isNotEmpty(), "$msg — the kernel outcome moved (groovy=$groovy)\n  $cond")
        assertEquals(groovy, javaStatic, "$msg (java-static≠groovy)\n  groovy=$groovy java=$javaStatic\n  $cond")
        assertEquals(groovy, interp, "$msg (interp≠groovy)\n  groovy=$groovy interp=$interp\n  $cond")
    }

    @Test fun aDayShiftFromASummerSourceMeetsTheFreshCopy() {
        triCheck(dayShift.eq(octoberFirst()), true, "AddDays keeps its CEST source offset, as fresh construction does")
        triCheck(dayShift.ne(octoberFirst()), false, "and its != inverse does not fire")
    }

    @Test fun aMonthShiftFromASummerSourceMeetsTheFreshCopy() {
        triCheck(monthShift.eq(octoberFirst()), true, "AddMonths keeps its CEST source offset → equal to fresh")
        triCheck(monthShift.ne(octoberFirst()), false, "and its != inverse does not fire")
    }

    @Test fun aYearShiftFromAWinterSourceTakesTheLaterCopy() {
        triCheck(yearShift.eq(octoberFirst()), false, "AddYears keeps its CET source offset → NOT the fresh copy")
        triCheck(yearShift.ne(octoberFirst()), true, "and its != inverse fires")
    }

    /** The discriminator against a per-operation account: day and month shifts from a CET source miss the fresh copy too. */
    @Test fun dayAndMonthShiftsFromAWinterSourceTakeTheLaterCopy() {
        triCheck(winterDayShift.eq(octoberFirst()), false, "AddDays from a CET source → NOT the fresh copy")
        triCheck(winterDayShift.ne(octoberFirst()), true, "and its != inverse fires")
        triCheck(winterMonthShift.eq(octoberFirst()), false, "AddMonths from a CET source → NOT the fresh copy")
        triCheck(winterMonthShift.ne(octoberFirst()), true, "and its != inverse fires")
    }

    /** Control: on an ORDINARY date the three mutations and fresh construction all agree, so the instant
     *  discrimination above is specific to the ambiguous midnight and not a blanket change of date equality. */
    @Test fun anUnambiguousMidnightKeepsEveryMutationEqual() {
        val june = Dsl.date(num("2"), num("6"), num("1917"))
        triCheck(Dsl.addDays(Dsl.date(num("1"), num("6"), num("1917")), 1L).eq(june), true, "ordinary day step")
        triCheck(Dsl.addMonths(Dsl.date(num("2"), num("5"), num("1917")), 1L).eq(june), true, "ordinary month step")
        triCheck(Dsl.addYears(Dsl.date(num("2"), num("6"), num("1916")), 1L).eq(june), true, "ordinary year step")
    }

    private companion object {
        const val CODE = "CDI"
        const val NAME = "/Order/CustomerName"
    }
}
