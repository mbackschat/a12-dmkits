package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `DateFragmentType` (WI-9 / IF57). A date fragment is stored in its `formatOfFragment`
 * (one of `yyyy-MM`, `yyyy`, `MM`, `MM-dd`) and the kernel collapses it to a plain `Date` by completing the
 * MISSING components to the earliest concrete date (its `feldWertAlsDatum` INTERVAL_START rule): missing
 * MONTH → `01`, missing DAY → `01`, missing YEAR → the model's base year (default 2000). Both kernel
 * strategies read a fragment through that one completion, so the interpreter matches both.
 *
 * The comparability partition (`MVK_INVALID_COMPARE_TO_DATE`) and the `Add*`/extract granularity gating
 * (`MVK_WRONG_DATE_FORMAT_FOR_OP`) are **authoring-time gates** — a valid model carries only legal combos,
 * so the interpreter only ever evaluates the legal ones (it does not re-enforce the gates). Every scenario
 * below is a combo the kernel *accepts*; the completion is reverse-engineered by comparing against a full
 * DATE field of a known value. Dogfood: the SAME injected-rule model JSON loads into BOTH engines (kernel via
 * {@link RuntimeLaws}, interpreter via {@link ModelLoader}), so this locks the loader case + the eval together.
 */
class DateFragmentComparisonDiffTest {

    private val base = RuntimeLaws.model("models/membership.dm.json")
    private val baseYear = RuntimeLaws.model("models/membership-baseyear.dm.json")

    private val CE = "/Card/CardExpiry"; private val RE = "/Card/RenewExpiry"
    private val WY = "/Card/WarrantyYear"; private val RY = "/Card/RefYear"
    private val BM = "/Card/BillMonth"; private val PM = "/Card/PeakMonth"
    private val SW = "/Card/SaleWindow"; private val PW = "/Card/PromoWindow"
    private val OD = "/Card/OrderDate"

    /** Assert the interpreter's fired-code SET equals the kernel's for a rule `cond` (error field [errRel])
     *  over the given field values, loading [model] into both engines. */
    private fun agree(model: String, errRel: String, cond: String, vararg fv: Pair<String, String>) {
        val withRule = RuntimeLaws.withRule(model, "/Card", "R", errRel, "RR", cond)
        val kDoc = RuntimeLaws.doc().group("/Card", 1)
        for ((f, v) in fv) kDoc.field(f, v, 1, 1)
        val kernel = RuntimeLaws.firedCodes(withRule, kDoc)

        val def = ModelLoader.load(withRule)
        val iDoc = def.newDocument().group("/Card", 1)
        for ((f, v) in fv) iDoc.field(f, v, 1, 1)
        val interp = DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()

        assertEquals(kernel, interp, "cond=`$cond` fv=${fv.toList()}")
    }

    // ── yyyy-MM ────────────────────────────────────────────────────────────────────────────────────
    @Test fun ymEqualsYm() {
        agree(base, "../RenewExpiry", "[$CE] == [$RE]", CE to "2024-06", RE to "2024-06")
        agree(base, "../RenewExpiry", "[$CE] == [$RE]", CE to "2024-06", RE to "2024-07")
    }
    @Test fun ymOrdering() = agree(base, "../RenewExpiry", "[$CE] < [$RE]", CE to "2024-06", RE to "2024-07")

    // yyyy-MM completes the DAY to 01 — legal vs a full date (both carry a year).
    @Test fun ymVsFullDayCompletedTo01() {
        agree(base, "../CardExpiry", "[$CE] == [$OD]", CE to "2024-06", OD to "2024-06-01")   // fires
        agree(base, "../CardExpiry", "[$CE] == [$OD]", CE to "2024-06", OD to "2024-06-15")   // day differs → no
        agree(base, "../CardExpiry", "[$CE] < [$OD]", CE to "2024-06", OD to "2024-06-15")    // 06-01 < 06-15 → fires
    }

    // ── yyyy ─────────────────────────────────────────────────────────────────────────────────────
    @Test fun yearFragmentCompletesMonthAndDayTo01() {
        agree(base, "../WarrantyYear", "[$WY] == [$OD]", WY to "2024", OD to "2024-01-01")   // fires
        agree(base, "../WarrantyYear", "[$WY] == [$OD]", WY to "2024", OD to "2024-06-15")   // no
        agree(base, "../WarrantyYear", "[$WY] < [$OD]", WY to "2024", OD to "2024-06-15")    // fires
    }
    @Test fun yearVsYear() {
        agree(base, "../WarrantyYear", "[$WY] < [$RY]", WY to "2024", RY to "2025")
        agree(base, "../WarrantyYear", "[$WY] == [$RY]", WY to "2024", RY to "2024")
    }

    // ── MM (year-unknown; completes year → base year 2000 here) ─────────────────────────────────────
    @Test fun monthFragmentComparesToMonthFragment() {
        agree(base, "../BillMonth", "[$BM] == [$PM]", BM to "06", PM to "06")
        agree(base, "../BillMonth", "[$BM] == [$PM]", BM to "06", PM to "08")
        agree(base, "../BillMonth", "[$BM] < [$PM]", BM to "06", PM to "08")
    }

    // ── MM-dd ──────────────────────────────────────────────────────────────────────────────────────
    @Test fun monthDayFragment() {
        agree(base, "../SaleWindow", "[$SW] < [$PW]", SW to "06-01", PW to "06-15")
        agree(base, "../SaleWindow", "[$SW] == [$PW]", SW to "06-15", PW to "06-15")
    }
    // MM-dd vs MM (both year-less, legal): MM completes day → 01.
    @Test fun monthDayVsMonth() {
        agree(base, "../SaleWindow", "[$SW] == [$BM]", SW to "06-15", BM to "06")   // 06-15 != 06-01 → no
        agree(base, "../SaleWindow", "[$SW] == [$BM]", SW to "06-01", BM to "06")   // 06-01 == 06-01 → fires
    }

    // ── Add* (result completes then shifts; legal per the format's granularity) ─────────────────────
    @Test fun addMonthsOnYearMonth() {
        agree(base, "../RenewExpiry", "AddMonths($CE, 1) == [$RE]", CE to "2024-06", RE to "2024-07")
        agree(base, "../RenewExpiry", "AddMonths($CE, 1) == [$RE]", CE to "2024-06", RE to "2024-08")
    }
    @Test fun addYearsOnYear() = agree(base, "../WarrantyYear", "AddYears($WY, 1) == [$RY]", WY to "2024", RY to "2025")
    @Test fun addMonthsOnMonth() = agree(base, "../BillMonth", "AddMonths($BM, 1) == [$PM]", BM to "06", PM to "07")
    @Test fun addDaysOnMonthDay() = agree(base, "../SaleWindow", "AddDays($SW, 1) == [$PW]", SW to "06-01", PW to "06-02")

    // ── extraction (legal component only) ────────────────────────────────────────────────────────
    @Test fun monthAndYearFromYearMonth() {
        agree(base, "../CardExpiry", "MonthFromDate($CE) == 6", CE to "2024-06")
        agree(base, "../CardExpiry", "YearFromDate($CE) == 2024", CE to "2024-06")
    }
    @Test fun monthFromMonthFragment() = agree(base, "../BillMonth", "MonthFromDate($BM) == 6", BM to "06")
    @Test fun yearFromYearFragment() = agree(base, "../WarrantyYear", "YearFromDate($WY) == 2024", WY to "2024")

    // ── formal errors: a malformed fragment value suppresses the rule + emits datumFormatFalsch ──────
    @Test fun malformedFragmentSuppresses() {
        agree(base, "../BillMonth", "[$BM] == [$PM]", BM to "13", PM to "06")        // month 13 → datumFormatFalsch
        agree(base, "../WarrantyYear", "[$WY] == [$RY]", WY to "abcd", RY to "2024") // non-numeric year
        agree(base, "../CardExpiry", "[$CE] == [$RE]", CE to "2024-13", RE to "2024-06")
    }

    // ── FieldFilled over a fragment ──────────────────────────────────────────────────────────────
    @Test fun filledOverFragment() {
        agree(base, "../CardExpiry", "FieldFilled($CE)", CE to "2024-06")   // filled → error condition holds → fires
        agree(base, "../CardExpiry", "FieldFilled($CE)")                    // empty → does not fire
    }

    // ── base-year model: a year-less fragment completes its YEAR to the declared base year (2024), so it
    //    becomes comparable to a year-bearing full date (the partition's base-year relaxation). ────────
    @Test fun monthFragmentCompletesToDeclaredBaseYear() {
        agree(baseYear, "../BillMonth", "[$BM] == [$OD]", BM to "06", OD to "2024-06-01")   // 2024-06-01 → fires
        agree(baseYear, "../BillMonth", "[$BM] == [$OD]", BM to "06", OD to "2023-06-01")   // base year is 2024 → no
        agree(baseYear, "../SaleWindow", "[$SW] == [$OD]", SW to "06-15", OD to "2024-06-15")
    }

    // ── codegen parity: the completion lives in the SHARED runtime `feldWertAlsDatum` that BOTH kernel
    //    strategies call (the codegen's FieldIdOperation emits `controller.feldWertAlsDatum(...)` for a date
    //    field), so the static-JAVA codegen must read a fragment identically. Confirmed tri-way (interp ==
    //    groovy-dynamic == java-static) on a representative subset — the runtime differential above only
    //    exercises the groovy-dynamic leg. Kept small (one in-memory javac compile per distinct model).
    @Test fun codegenReadsFragmentsIdentically() {
        agreeTri(base, "../CardExpiry", "[$CE] == [$OD]", CE to "2024-06", OD to "2024-06-01")   // ym completion
        agreeTri(base, "../BillMonth", "[$BM] < [$PM]", BM to "06", PM to "08")                  // MM (year-less)
        agreeTri(base, "../RenewExpiry", "AddMonths($CE, 1) == [$RE]", CE to "2024-06", RE to "2024-07")   // Add*
        agreeTri(base, "../CardExpiry", "MonthFromDate($CE) == 6", CE to "2024-06")              // extraction
        agreeTri(baseYear, "../BillMonth", "[$BM] == [$OD]", BM to "06", OD to "2024-06-01")     // base-year completion
    }

    /** As [agree], but also asserts the static-JAVA codegen leg agrees (interp == groovy-dynamic == java-static). */
    private fun agreeTri(model: String, errRel: String, cond: String, vararg fv: Pair<String, String>) {
        val withRule = RuntimeLaws.withRule(model, "/Card", "R", errRel, "RR", cond)
        val kDoc = RuntimeLaws.doc().group("/Card", 1)
        for ((f, v) in fv) kDoc.field(f, v, 1, 1)
        val groovy = RuntimeLaws.firedCodes(withRule, kDoc)

        val engine = RuntimeLaws.kernelJavaEngine(withRule)
        val jDoc = RuntimeLaws.doc().group("/Card", 1)
        for ((f, v) in fv) jDoc.field(f, v, 1, 1)
        val javaStatic = engine.firedCodesSorted(engine.instance(jDoc)).toSet()

        val def = ModelLoader.load(withRule)
        val iDoc = def.newDocument().group("/Card", 1)
        for ((f, v) in fv) iDoc.field(f, v, 1, 1)
        val interp = DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()

        assertEquals(groovy, interp, "interp≠groovy | cond=`$cond` fv=${fv.toList()}")
        assertEquals(groovy, javaStatic, "java-static≠groovy | cond=`$cond` fv=${fv.toList()}")
    }
}
