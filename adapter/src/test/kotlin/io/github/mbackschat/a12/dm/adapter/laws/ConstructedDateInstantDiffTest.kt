package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — SPEC-2026-07-29-01's exact-instant matrix. Direct Date equality is the observer: it reads the
 * underlying instant and introduces no second calendar mutation that could share and mask the resolver under
 * test. All three shapes land on the SAME civil label, `1916-10-01`, whose midnight is the one ambiguous
 * midnight in Berlin's pinned history — so the label cannot separate them and the instant must.
 *
 *  - **fresh construction** resolves midnight the parse way: the LATER (CET) copy;
 *  - the **day** mutation carries the source instant, so a step from a CEST September source retains the
 *    EARLIER (CEST) copy → `AddDays(Date(30,9,1916), 1) == Date(1,10,1916)` is **false**;
 *  - **month** and **year** mutation resolve the mutated civil fields at compute time and take the later copy
 *    → both `== Date(1,10,1916)` are **true**.
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

    private fun render(c: Condition): String = DslRenderer().render(c)
    private fun guarded(c: Condition): Condition = m.field(NAME).filled().and(c)

    private val dayShift = Dsl.addDays(Dsl.date(num("30"), num("9"), num("1916")), 1L)
    private val monthShift = Dsl.addMonths(Dsl.date(num("1"), num("9"), num("1916")), 1L)
    private val yearShift = Dsl.addYears(Dsl.date(num("1"), num("10"), num("1915")), 1L)

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

    @Test fun aDayShiftRetainsTheEarlierOverlapCopy() {
        triCheck(dayShift.eq(octoberFirst()), false,
            "AddDays keeps its CEST source season, fresh construction is CET → NOT equal")
        triCheck(dayShift.ne(octoberFirst()), true, "and its != inverse fires")
    }

    @Test fun aMonthShiftSelectsTheLaterOverlapCopy() {
        triCheck(monthShift.eq(octoberFirst()), true,
            "AddMonths resolves the mutated fields at compute time → the later copy, equal to fresh")
        triCheck(monthShift.ne(octoberFirst()), false, "and its != inverse does not fire")
    }

    @Test fun aYearShiftSelectsTheLaterOverlapCopy() {
        triCheck(yearShift.eq(octoberFirst()), true,
            "AddYears uses the same compute-time resolution → the later copy, equal to fresh")
        triCheck(yearShift.ne(octoberFirst()), false, "and its != inverse does not fire")
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
