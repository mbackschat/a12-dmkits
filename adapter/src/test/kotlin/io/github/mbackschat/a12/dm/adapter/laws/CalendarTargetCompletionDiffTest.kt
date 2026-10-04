package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-04-01: calendar arithmetic keeps an intermediate the stored-Date checks would refuse, and a computed
 * Date target is classified only after its value is rendered into the target's format. A five-digit year therefore
 * survives the shift and reaches the target as an errored attempt, rendered in that target's own components, and
 * its dependent is poisoned. Formal completion of a component-omitting format appends the Base Year only when the
 * format names a day and a month but no year, so a month-only value escapes the floor that a month-day value and a
 * year value meet under the same Base Year.
 *
 * Two models, because the Base Year is model-wide: the policy model has none and carries the overflow and ordering
 * rows; the billing model fixes Base Year 1583, the floor's own year. The rules order a five-digit result against
 * its source in both directions, so a label comparison that sorts `10000` before `9999` fires the wrong one.
 *
 * Own domain (insurance policy renewal and billing).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalendarTargetCompletionDiffTest {
    private val policySeeds = mapOf("Renewal" to "2000-01-01", "RenewalYearNo" to "1", "RenewalMonth" to "2000-01",
        "RenewalYear" to "2000")
    private val billingSeeds = mapOf("NextBilling" to "06", "NextAnniversary" to "12-31",
        "FoundingDay" to "2000-01-01", "FoundingNext" to "2000-01-01")
    private val planSeeds = mapOf("Cycle" to "02-28", "CycleMonth" to "9", "PlanYear" to "2023", "Fee" to "9",
        "Seats" to "9")

    private val policy: String = run {
        val draft = DocumentModelDraft.named("policy-renewal-calendar").locale("en_US")
            .rootGroup("Policy") { p ->
                p.dateField("Start") { it.format("yyyy-MM-dd") }
                p.numberField("Years") { it.fractionDigits(0) }
                p.dateField("StartMonth") { it.format("yyyy-MM") }
                p.dateField("StartYear") { it.format("yyyy") }
                p.dateField("Renewal") { it.format("yyyy-MM-dd") }
                p.numberField("RenewalYearNo") { it.fractionDigits(0) }
                p.dateField("RenewalMonth") { it.format("yyyy-MM") }
                p.dateField("RenewalYear") { it.format("yyyy") }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Policy")
        val start = p.date("Start")
        val years = p.num("Years").asNumber()
        var json = session.toJson()
        fun comp(target: FieldRef, name: String, body: (Computation.Builder) -> Computation.Builder) {
            json = TypedRules.inject(json, p.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the renewal.")).build()
            })
        }
        comp(p.date("Renewal").field(), "Renewal") { it.calc(Dsl.addYears(start, years).expression()) }
        comp(p.num("RenewalYearNo").field(), "RenewalYearNo") {
            it.calc(Dsl.yearFromDate(p.date("Renewal")).expression())
        }
        comp(p.date("RenewalMonth").field(), "RenewalMonth") {
            it.calc(Dsl.addMonths(p.date("StartMonth"), 1).expression())
        }
        comp(p.date("RenewalYear").field(), "RenewalYear") { it.calc(Dsl.addYears(p.date("StartYear"), 1).expression()) }
        for ((code, condition) in listOf(
            "RenewalAfterStart" to Dsl.addYears(start, years).after(start),
            "RenewalBeforeStart" to Dsl.addYears(start, years).before(start),
        )) {
            json = TypedRules.inject(json, p.rule {
                it.name(code).code(code).errorField(start.field()).`when`(condition)
                    .message(Dsl.en("Check the policy dates.")).build()
            })
        }
        json
    }

    private val billing: String = run {
        val draft = DocumentModelDraft.named("policy-billing-calendar").locale("en_US").baseYear(1583)
            .rootGroup("Billing") { b ->
                b.dateField("BillingMonth") { it.format("MM") }
                b.dateField("Anniversary") { it.format("MM-dd") }
                b.dateField("Founded") { it.format("yyyy") }
                b.dateField("NextBilling") { it.format("MM") }
                b.dateField("NextAnniversary") { it.format("MM-dd") }
                b.dateField("FoundingDay") { it.format("yyyy-MM-dd") }
                b.dateField("FoundingNext") { it.format("yyyy-MM-dd") }
            }.build().toJson()
        val session = EditSession.read(draft)
        val b = session.group("/Billing")
        val founding = Dsl.date(Dsl.temporalComponent("15"), Dsl.temporalComponent("10"))
        var json = session.toJson()
        for ((target, source, name) in listOf(
            Triple("NextBilling", Dsl.addMonths(b.date("BillingMonth"), 1), "NextBilling"),
            Triple("NextAnniversary", Dsl.addDays(b.date("Anniversary"), 1), "NextAnniversary"),
            // Two components: the year is the Base Year (IG163), so the construction itself sits below the floor.
            Triple("FoundingDay", founding, "FoundingDay"),
            Triple("FoundingNext", Dsl.addDays(founding, 1), "FoundingNext"),
        )) {
            json = TypedRules.inject(json, b.computation(b.date(target).field()) {
                it.name(name + "Comp").calc(source.expression()).message(Dsl.en("Check the billing date.")).build()
            })
        }
        json
    }

    /**
     * Built twice, differing only in the Base Year, so the leap rows are a within-model control. A month-day target
     * re-completes its rendered `02-29` in the Base Year; the year-only target is read back by two guards, one
     * through a `Date(...)` year component and one through presence, which must both see the stored `2024` rather
     * than the evaluation's completed date.
     */
    private fun plan(baseYear: Int): String {
        val draft = DocumentModelDraft.named("policy-plan-calendar-$baseYear").locale("en_US").baseYear(baseYear)
            .rootGroup("Plan") { p ->
                p.dateField("Cycle") { it.format("MM-dd") }
                p.numberField("CycleMonth") { it.fractionDigits(0) }
                p.dateField("PlanYear") { it.format("yyyy") }
                p.numberField("Fee") { it.fractionDigits(0) }
                p.numberField("Seats") { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Plan")
        val planYear = p.date("PlanYear")
        var json = session.toJson()
        fun comp(target: FieldRef, name: String, body: (Computation.Builder) -> Computation.Builder) {
            json = TypedRules.inject(json, p.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the plan.")).build()
            })
        }
        comp(p.date("Cycle").field(), "Cycle") { it.calc(Dsl.dateValue("29.02.2024").expression()) }
        comp(p.num("CycleMonth").field(), "CycleMonth") { it.calc(Dsl.monthFromDate(p.date("Cycle")).expression()) }
        comp(planYear.field(), "PlanYear") { it.calc(Dsl.dateValue("01.01.2024").expression()) }
        comp(p.num("Fee").field(), "Fee") {
            it.commonPrecondition(Dsl.date(Dsl.temporalComponent("01"), Dsl.temporalComponent("01"), planYear.field())
                .eq(Dsl.dateValue("01.01.2024"))).calc(Dsl.number(7).expression())
        }
        comp(p.num("Seats").field(), "Seats") {
            it.commonPrecondition(planYear.filled()).calc(Dsl.number(3).expression())
        }
        return json
    }

    private val plan2023 = plan(2023)
    private val plan2024 = plan(2024)

    private val planDocument = """{"Plan":{"Cycle":"02-28","CycleMonth":9,"PlanYear":"2023","Fee":9,"Seats":9}}"""

    private fun policyDocument(start: String, years: Int, month: String, year: String) =
        """{"Policy":{"Start":"$start","Years":$years,"StartMonth":"$month","StartYear":"$year",""" +
            policySeeds.entries.joinToString(",") { (k, v) -> if (k == "RenewalYearNo") "\"$k\":$v" else "\"$k\":\"$v\"" } +
            "}}"

    private fun billingDocument(month: String, anniversary: String, founded: String) =
        """{"Billing":{"BillingMonth":"$month","Anniversary":"$anniversary","Founded":"$founded",""" +
            billingSeeds.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } + "}}"

    private val documents = linkedMapOf(
        "policy-ordinary" to (policy to policyDocument("2023-06-15", 1, "2023-06", "2023")),
        "policy-overflow" to (policy to policyDocument("9999-12-15", 1, "9999-12", "9999")),
        "billing-floor-year" to (billing to billingDocument("10", "10-15", "1583")),
        "billing-after-floor" to (billing to billingDocument("12", "12-31", "1584")),
        "plan-base-2023" to (plan2023 to planDocument),
        "plan-base-2024" to (plan2024 to planDocument),
    )

    @Test
    fun bothModelsAreKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(policy))
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(billing))
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(plan2023))
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(plan2024))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for ((name, pair) in documents) DocumentDifferential.assertAgree(pair.first, mapOf(name to pair.second))
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * names the rival account it rules out: refusing a five-digit year during arithmetic, rendering an errored
     * fragment attempt as a full date, ordering dates by label, and completing every omitted year from the Base Year.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun run(doc: String) =
            documents.getValue(doc).let { (model, json) -> DocumentDifferential.kernel(model, json, dynamic = true) }
        fun fired(doc: String, code: String) = run(doc).messages.filter { it.code == code }.map { it.brief }
        val overflow = run("policy-overflow").outcomes
        val actual = linkedMapOf(
            "a five-digit year reaches the target as an errored attempt" to overflow["Renewal"],
            "its dependent is poisoned" to overflow["RenewalYearNo"],
            "a year-month target renders its attempt in its own components" to overflow["RenewalMonth"],
            "a year target renders its attempt in its own components" to overflow["RenewalYear"],
            "a five-digit result orders after its source" to fired("policy-overflow", "RenewalAfterStart"),
            "and not before it" to fired("policy-overflow", "RenewalBeforeStart"),
            "under Base Year 1583 only month-day and year values meet the floor" to
                fired("billing-floor-year", "datumFalsch"),
            "a month-only shift stays a value" to run("billing-floor-year").outcomes["NextBilling"],
            "a two-component Date takes the Base Year and meets the floor" to
                run("billing-floor-year").outcomes["FoundingDay"],
            "a shift returns it to the floor" to run("billing-floor-year").outcomes["FoundingNext"],
            "a leap attempt into month-day under a non-leap Base Year is errored" to
                run("plan-base-2023").outcomes["Cycle"],
            "the leap attempt's dependent is poisoned" to run("plan-base-2023").outcomes["CycleMonth"],
            "under a leap Base Year it stores" to run("plan-base-2024").outcomes["Cycle"],
            "a year-component guard reads the stored year-only target" to run("plan-base-2024").outcomes["Fee"],
            "a presence guard reads it too" to run("plan-base-2024").outcomes["Seats"],
        )
        assertEquals(linkedMapOf(
            "a five-digit year reaches the target as an errored attempt" to "ERRORED|10000-12-15",
            "its dependent is poisoned" to "CLEARED",
            "a year-month target renders its attempt in its own components" to "ERRORED|10000-01",
            "a year target renders its attempt in its own components" to "ERRORED|10000",
            "a five-digit result orders after its source" to listOf("RenewalAfterStart|VALUE_ERROR|/Policy[1]/Start"),
            "and not before it" to emptyList<String>(),
            "under Base Year 1583 only month-day and year values meet the floor" to
                listOf("datumFalsch|VALUE_ERROR|/Billing[1]/Anniversary", "datumFalsch|VALUE_ERROR|/Billing[1]/Founded"),
            "a month-only shift stays a value" to "VALUE|11",
            "a two-component Date takes the Base Year and meets the floor" to "ERRORED|1583-10-15",
            "a shift returns it to the floor" to "VALUE|1583-10-16",
            "a leap attempt into month-day under a non-leap Base Year is errored" to "ERRORED|02-29",
            "the leap attempt's dependent is poisoned" to "CLEARED",
            "under a leap Base Year it stores" to "VALUE|02-29",
            "a year-component guard reads the stored year-only target" to "VALUE|7",
            "a presence guard reads it too" to "VALUE|3",
        ), actual)
    }
}
