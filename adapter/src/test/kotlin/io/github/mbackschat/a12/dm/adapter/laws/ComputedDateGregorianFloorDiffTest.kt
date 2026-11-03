package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the **kernel's always-on
 * Gregorian-calendar date floor** applied to a COMPUTED date value — the ERRORED-classification compute atom (IF88)
 * (`kernel=ERRORED,interp=VALUE`), root-caused by the raw-outcome isolator ([RawAtomShrinkTest]) shrinking a
 * real composite model to its minimal interaction: a single unconditional `Date(day, month, century, decade)`
 * computing an ancient year.
 *
 * The kernel flags EVERY date value — stored OR computed — that resolves to a real calendar date **before
 * `Constants.DATE_FROM = 1583-10-16`** ("the begin of the gregorian calendar") as the formal error `datumFalsch`
 * (FEHLER_DATUM). This is `FormatDefinitionDatum.dateInvalid` = `date.before(1583-10-16)`, ALWAYS on (distinct
 * from the opt-in "younger than 1900" additional check, which the boundary here excludes: 1899 is valid). Both
 * kernel strategies apply it (source: the runtime format-definition + the codegen `CodeGenDateTypeImpl` that
 * wraps it), so a computed date below the floor is bucketed **with-errors** (`getComputedFieldInstancesWithErrors`
 * → reported ERRORED). Before IF88 the interpreter's date `formalError` accepted any real calendar date, so
 * `computedOutcome` returned VALUE. IF88 adds the floor to `formalError` (via `beforeGregorianStart`), so it now
 * fires for both the compute path (→ ERRORED) and the validate path (→ `datumFalsch`).
 *
 * Boundary pinned exactly by the differential: 1583-10-15 errored, 1583-10-16 valid (strict `Date.before`).
 * Own-domain re-expression (insurance policy dates); nothing from the corpus is reproduced.
 */
class ComputedDateGregorianFloorDiffTest {

    private companion object {
        const val DAY = "/Policy/StartDay"
        const val MONTH = "/Policy/StartMonth"
        const val YEAR = "/Policy/StartYear"
        const val START = "/Policy/CoverageStart"
        const val AD = "/Order/ApproxDate"
    }

    private val json = DocumentModelDraft.named("policy").locale("en_US")
        .rootGroup("Policy", Consumer { g: GroupBuilder ->
            g.numberField("StartDay", Consumer { it.min("1").max("31") })
                .numberField("StartMonth", Consumer { it.min("1").max("12") })
                .numberField("StartYear", Consumer { it.min("1").max("9999") })
                .dateField("CoverageStart", Consumer { it.format("yyyy-MM-dd") })
        })
        .build().toJson()

    private val m = BoundModel.ofJson(json)
    // Date(StartDay, StartMonth, StartYear) — a comparable date constructed from the three numeric components.
    private val op = RuntimeLaws.render("/Policy", Dsl.date(m.num(DAY).asNumber(), m.num(MONTH).asNumber(), m.num(YEAR).asNumber()).expression())
    private val kModel = RuntimeLaws.withComputation(json, "/Policy", "StartComp", "../CoverageStart", op)
    private val def = ModelDef(
        listOf(
            FieldDef(DAY, Kind.NUMBER, minValue = "1", maxValue = "31"),
            FieldDef(MONTH, Kind.NUMBER, minValue = "1", maxValue = "12"),
            FieldDef(YEAR, Kind.NUMBER, minValue = "1", maxValue = "9999"),
            FieldDef(START, Kind.DATE, format = "yyyy-MM-dd"),
        ),
        emptyList(),
        computations = listOf(ComputationDef(START, "/Policy", op)),
    )

    private fun kernelSig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private fun kOutcome(day: String, month: String, year: String): String {
        val doc = RuntimeLaws.doc().group("/Policy", 1)
            .field(DAY, day, 1, 1).field(MONTH, month, 1, 1).field(YEAR, year, 1, 1)
        return kernelSig(RuntimeLaws.compute(kModel, doc).firstOrNull { it.path() == START })
    }

    private fun jOutcome(day: String, month: String, year: String): String {
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val doc = RuntimeLaws.doc().group("/Policy", 1)
            .field(DAY, day, 1, 1).field(MONTH, month, 1, 1).field(YEAR, year, 1, 1)
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$START|") }
            ?.substringAfter("$START|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun iOutcome(day: String, month: String, year: String): String {
        val doc = def.newDocument()
            .group("/Policy", 1).field(DAY, day, 1, 1).field(MONTH, month, 1, 1).field(YEAR, year, 1, 1)
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == START }
        return when (c?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    private fun tri(day: String, month: String, year: String, expect: String, msg: String) {
        val groovy = kOutcome(day, month, year)
        val javaStatic = jOutcome(day, month, year)
        val interp = iOutcome(day, month, year)
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
        assertEquals(expect, interp, "$msg — expected outcome")
    }

    @Test fun modernDateComputes() =
        tri("15", "6", "2024", "VALUE|2024-06-15", "Date(15,6,2024) → a modern date computes")

    @Test fun ancientYearErrors() =
        tri("31", "1", "1000", "ERRORED", "Date(31,1,1000)=1000-01-31 (< Gregorian floor) → ERRORED, not a value")

    @Test fun dayBeforeGregorianFloorErrors() =
        tri("15", "10", "1583", "ERRORED", "Date(15,10,1583)=1583-10-15 (one day before the floor) → ERRORED")

    @Test fun gregorianFloorDayComputes() =
        tri("16", "10", "1583", "VALUE|1583-10-16", "Date(16,10,1583)=1583-10-16 (the floor itself) → a value")

    // ── The stored-value floor across a PARTIAL precision (DAY_OPTIONAL). The kernel completes an omitted
    //    day to 01 BEFORE the floor check (`PartiallyKnownDatesUtil.replaceUnknownDateParts` runs ahead of
    //    `dateInvalid`), so a day-omitted October 1583 is `datumFalsch` even though days 16–31 of that month
    //    are valid — pinning the completion↔floor ORDER that the interpreter's `partialDateParts` day-0→01
    //    mapping reproduces (IF88). ────────────────────────────────────────────────────────────────────────

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val adDef = ModelDef(
        listOf(FieldDef(AD, Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL)),
        emptyList(),
    )

    private fun storedAgree(value: String, expect: String?, msg: String) {
        val k = RuntimeLaws.firedCodes(order, setOf("/Order"), mapOf(AD to value)).firstOrNull()
        val doc = adDef.newDocument()
            .group("/Order", 1).field(AD, value, 1, 1)
        val i = DmInterpreter(adDef).validate(doc).messages.firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
        assertEquals(k, i, "$msg (kernel=$k interp=$i)")
        assertEquals(expect, i, "$msg — expected code")
    }

    @Test fun dayOptionalOmittedDayInFloorMonthErrors() =
        storedAgree("00.10.1583", "datumFalsch", "00.10.1583 completes to 01.10.1583 < the floor")

    @Test fun dayOptionalOmittedDayNextMonthValid() =
        storedAgree("00.11.1583", null, "00.11.1583 completes to 01.11.1583 ≥ the floor")
}
