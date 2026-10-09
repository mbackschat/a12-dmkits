package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a constructed Date's components carry non-relevance and poison into the construction, its validity and its
 * dependents (a12-kernel-lean SPEC-2026-10-06-04 and SPEC-2026-10-06-05). A partial date whose year is unknown is
 * cause-free non-relevance; read through a shift and an extractor into a Date component it leaves the construction
 * non-relevant, so the target clears cleanly, an enclosing `Min` is absorbed, and both validity guards are not
 * true. A malformed component reached AFTER that non-relevant one still poisons. Four forms separate the carriers:
 * the non-relevant day before selected month and year, a year extracted from a shifted partial date beside
 * constants, three selected numbers, and three extractors over a shifted selection.
 *
 * Own domain (an insurance policy whose events are keyed by code).
 */
class DateComponentRelevanceDiffTest {

    private val dateTargets = listOf("Nonrel", "NonrelMin", "Unknown", "UnknownMin", "Numbers", "NumbersMin",
        "Recursive", "RecursiveMin")
    private val numberTargets = listOf("NonrelAfter", "UnknownAfter", "NumbersAfter", "RecursiveAfter",
        "NonrelReal", "NonrelCommon", "NumbersReal", "NumbersCommon")

    private val model: String = run {
        val draft = DocumentModelDraft.named("policy-date-components").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Key") { }
                p.dateField("Noted") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
                p.group("Events") { g -> g.repeatable(3).indexField("Code")
                    .stringField("Code") { }
                    .numberField("Day") { n -> n.fractionDigits(0).min("0").max("31") }
                    .numberField("Month") { n -> n.fractionDigits(0).min("0").max("12") }
                    .numberField("Year") { n -> n.fractionDigits(0).min("0").max("9999") }
                    .dateField("Occurred") { it.format("yyyy-MM-dd") } }
                for (name in dateTargets) p.dateField(name) { it.format("yyyy-MM-dd") }
                for (name in numberTargets) p.numberField(name) { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Policy")
        val events = session.group("/Policy/Events")
        val key = p.field("Key")
        val day = events.num("Day").forIndex(key)
        val month = events.num("Month").forIndex(key)
        val year = events.num("Year").forIndex(key)
        val shiftedOccurred = Dsl.addDays(events.date("Occurred").forIndex(key), 1L)
        val shiftedNoted = Dsl.addDays(Dsl.valueAsDateFirstDay(p.date("Noted").field()), 1L)
        val forms: Map<String, () -> DateExpr> = linkedMapOf(
            "Nonrel" to { Dsl.date(Dsl.dayFromDate(shiftedNoted), month, year) },
            "Unknown" to {
                Dsl.date(Dsl.temporalComponent("1"), Dsl.temporalComponent("2"), Dsl.yearFromDate(shiftedNoted))
            },
            "Numbers" to { Dsl.date(day, month, year) },
            "Recursive" to {
                Dsl.date(Dsl.dayFromDate(shiftedOccurred), Dsl.monthFromDate(shiftedOccurred),
                    Dsl.yearFromDate(shiftedOccurred))
            },
        )
        var json = session.toJson()
        fun comp(target: FieldRef, name: String, body: (Computation.Builder) -> Computation.Builder) {
            json = TypedRules.inject(json, p.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the event date.")).build()
            })
        }
        for ((form, construct) in forms) {
            comp(p.date(form).field(), form) { it.calc(construct().expression()) }
            comp(p.date(form + "Min").field(), form + "Min") {
                it.calc(Dsl.min(construct(), Dsl.dateValue("15.02.2024")).expression())
            }
            comp(p.num(form + "After").field(), form + "After") {
                it.commonPrecondition(p.date(form).notFilled()).calc(Dsl.number(1).expression())
            }
        }
        // Validity needs an immediate entity in some slot, which only these two forms have (KF402).
        for (form in listOf("Nonrel", "Numbers")) {
            val construct = forms.getValue(form)
            comp(p.num(form + "Real").field(), form + "Real") {
                it.`when`(Dsl.valid(construct())).calc(Dsl.number(1).expression())
                    .`when`(Dsl.invalid(construct())).calc(Dsl.number(2).expression())
            }
            comp(p.num(form + "Common").field(), form + "Common") {
                it.commonPrecondition(Dsl.valid(construct())).calc(Dsl.number(3).expression())
            }
            json = TypedRules.inject(json, p.rule {
                it.name(form + "Invalid").code(form + "Invalid").errorField(key)
                    .`when`(Dsl.invalid(construct())).message(Dsl.en("Check the event date.")).build()
            })
        }
        json
    }

    private val seeds = dateTargets.associateWith { "\"2001-12-25\"" } + numberTargets.associateWith { "9" }

    private fun event(code: String, day: String, month: String, year: String, occurred: String) =
        """{"Code":"$code","Day":"$day","Month":"$month","Year":"$year","Occurred":"$occurred"}"""

    private val first = event("A", "1", "1", "2023", "2023-01-01")
    private val leapDay = event("B", "29", "2", "2024", "2024-02-28")

    private fun policy(key: String, noted: String, vararg events: String) =
        """{"Policy":{"Key":"$key","Noted":"$noted","Events":[${events.joinToString(",")}],""" +
            seeds.entries.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}}"

    private val documents = linkedMapOf(
        "known" to policy("B", "2024-00-00", first, leapDay),
        "unknown year" to policy("B", "0000-00-00", first, leapDay),
        "unknown year, then a malformed month" to policy("B", "0000-00-00", first, event("B", "29", "bad", "2024", "2024-02-28")),
        "a malformed month alone" to policy("B", "2024-00-00", first, event("B", "29", "bad", "2024", "2024-02-28")),
        "empty selected components" to policy("B", "2024-00-00", first, event("B", "", "", "", "")),
        "no event carries the key" to policy("C", "2024-00-00", first, leapDay),
        "a duplicated key" to policy("B", "2024-00-00", event("B", "1", "1", "2023", "2023-01-01"), leapDay),
        "an unreal selected date" to policy("B", "2024-00-00", first, event("B", "31", "2", "2024", "2024-02-28")),
        "an empty partial date" to policy("B", "", first, leapDay),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously: the
     * unknown year clears cleanly and absorbs `Min`, its validity guards both fall through, and a malformed month
     * read after it still poisons: the target clears either way, and the guard reading it tells the two apart.
     */
    @Test
    fun nonRelevanceClearsCleanlyAndALaterPoisonStillWins() {
        fun outcomes(doc: String) = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
        val unknown = outcomes("unknown year")
        val poisoned = outcomes("unknown year, then a malformed month")
        val actual = linkedMapOf(
            "the non-relevant day clears its target" to unknown["Nonrel"],
            "cleanly, so its guard reads it as empty" to unknown["NonrelAfter"],
            "and absorbs Min" to unknown["NonrelMin"],
            "the unknown extracted year clears too" to unknown["Unknown"],
            "both validity alternatives fall through" to unknown["NonrelReal"],
            "and the validity common guard clears" to unknown["NonrelCommon"],
            "a malformed month after the non-relevant day clears the target too" to poisoned["Nonrel"],
            "but poisons the guard that reads it" to poisoned["NonrelAfter"],
        )
        assertEquals(linkedMapOf(
            "the non-relevant day clears its target" to "CLEARED",
            "cleanly, so its guard reads it as empty" to "VALUE|1",
            "and absorbs Min" to "CLEARED",
            "the unknown extracted year clears too" to "CLEARED",
            "both validity alternatives fall through" to "CLEARED",
            "and the validity common guard clears" to "CLEARED",
            "a malformed month after the non-relevant day clears the target too" to "CLEARED",
            "but poisons the guard that reads it" to "CLEARED",
        ), actual)
    }

    /**
     * An EMPTY selected component is a missing one, exactly like an empty field: `Invalid` over it is an omission
     * that asks for the selected components, and `Min` over three extractors from a shifted empty selection falls
     * back to its other operand. Reading the empty selection as zero instead would make the first a VALUE error.
     */
    @Test
    fun anEmptySelectionIsAMissingComponent() {
        val observed = DocumentDifferential.kernel(model, documents.getValue("empty selected components"), dynamic = true)
        val actual = linkedMapOf(
            "Invalid over three empty selected numbers" to
                observed.messages.filter { it.code == "NumbersInvalid" }.map { it.brief },
            "Min over extractors from a shifted empty selection" to observed.outcomes["RecursiveMin"],
        )
        assertEquals(linkedMapOf(
            "Invalid over three empty selected numbers" to listOf("NumbersInvalid|OMISSION_ERROR|/Policy[1]/Key"),
            "Min over extractors from a shifted empty selection" to "VALUE|2024-02-15",
        ), actual)
    }
}
