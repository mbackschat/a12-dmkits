package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-04-04: a computation precondition over date-range overlap reads its ranges in order and stops at the
 * deciding pair, and a reached malformed range poisons the computation where validation would skip it. A filtered
 * list reads one kept successor ahead and no further, a scalar-first form with an empty scalar reads no list at all,
 * and a group operand is read field by field in declaration order, every row of its first field before its second.
 *
 * Every guarded target `T` has a dependent `AfterT` that holds whenever it is computed at all, so the dependent
 * separates a poisoned target (CLEARED) from a cleanly cleared one (1).
 *
 * Own domain (e-commerce rental bookings).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverlapComputationGuardDiffTest {

    private val targets = listOf("AnySlots", "OneSlots", "AnyKeptSlots", "OneKeptSlots", "OnePeriods")
    private val seeds = targets.flatMap { listOf(it to "9", "After$it" to "9") }.toMap()

    private val model: String = run {
        val draft = DocumentModelDraft.named("rental-overlap").locale("en_US")
            .rootGroup("Fleet") { f ->
                f.dateRangeField("Booking") { it.format("yyyy-MM-dd").separator("/") }
                f.group("Slots") { s ->
                    s.repeatable(4)
                    s.dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
                    s.numberField("Gate") { it.fractionDigits(0) }
                }
                f.group("Periods") { p ->
                    p.repeatable(3)
                    p.dateRangeField("Primary") { it.format("yyyy-MM-dd").separator("/") }
                    p.dateRangeField("Backup") { it.format("yyyy-MM-dd").separator("/") }
                }
                for (name in seeds.keys) f.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val f = session.group("/Fleet")
        val booking = f.dateRange("Booking").field()
        val windows = f.flatten("Slots*/Window")
        val keptWindows = f.flatten("Slots*/Window").having(session.group("/Fleet/Slots").num("Gate").gt(0))
        val guards: List<Pair<String, Condition>> = listOf(
            "AnySlots" to Dsl.dateRangesOverlap(windows),
            "OneSlots" to Dsl.atLeastOneDateRangeOverlaps(booking, windows),
            "AnyKeptSlots" to Dsl.dateRangesOverlap(keptWindows),
            "OneKeptSlots" to Dsl.atLeastOneDateRangeOverlaps(booking, keptWindows),
            "OnePeriods" to Dsl.atLeastOneDateRangeOverlaps(booking, f.flattenGroup("Periods*")),
        )
        var json = session.toJson()
        for ((name, guard) in guards) {
            val target = f.num(name)
            json = TypedRules.inject(json, f.computation(target) {
                it.name("${name}Comp").`when`(guard).calc(NumberLiteral.of("1")).message(Dsl.en("Check the booking.")).build()
            })
            json = TypedRules.inject(json, f.computation(f.num("After$name")) {
                it.name("After${name}Comp").`when`(target.filled().or(target.notFilled())).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check the booking.")).build()
            })
        }
        json
    }

    private fun document(booking: String, slots: List<Pair<String, String>>, periods: List<Pair<String, String>>) =
        """{"Fleet":{"Booking":"$booking",""" + seeds.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } +
            ""","Slots":[""" + slots.joinToString(",") { (w, g) -> """{"Window":"$w","Gate":"$g"}""" } +
            """],"Periods":[""" + periods.joinToString(",") { (p, b) -> """{"Primary":"$p","Backup":"$b"}""" } + "]}}"

    private val january = "2024-01-01/2024-01-10"
    private val mid = "2024-01-05/2024-01-15"
    private val march = "2024-03-01/2024-03-10"

    private val documents = linkedMapOf(
        "overlapping" to document(january, listOf(january to "1", mid to "1"), listOf(january to march)),
        "disjoint" to document(january, listOf(march to "1"), listOf(march to march)),
        "malformed first row" to document(january, listOf("bad" to "1", january to "1", mid to "1"), emptyList()),
        "malformed row dropped by the filter" to
            document(january, listOf("bad" to "0", january to "1", mid to "1"), emptyList()),
        "third filter malformed after a decision" to
            document(january, listOf(january to "1", mid to "1", march to "bad"), emptyList()),
        "empty scalar beside a malformed row" to document("", listOf("bad" to "1"), emptyList()),
        "group read field by field" to document(january, emptyList(), listOf(march to january, "bad" to march)),
        "group decided in its first field" to document(january, emptyList(), listOf(january to march, "bad" to march)),
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
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * value is `target/dependent`: `CLEARED/CLEARED` is a poisoned target, `CLEARED/1` a clean clear. Each row names
     * the rival account it rules out.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun outcome(doc: String, target: String): String {
            val outcomes = DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true).outcomes
            fun of(name: String) = outcomes[name]?.removePrefix("VALUE|") ?: "9"
            return of(target) + "/" + of("After$target")
        }
        fun outcomes(doc: String, vararg names: String) = names.map { outcome(doc, it) }
        val actual = linkedMapOf(
            "a reached malformed range poisons rather than being skipped" to
                outcomes("malformed first row", "AnySlots", "OneSlots", "AnyKeptSlots", "OneKeptSlots"),
            "a row the filter drops is never read" to
                outcomes("malformed row dropped by the filter", "AnySlots", "AnyKeptSlots", "OneKeptSlots"),
            "a filtered list reads one kept successor ahead and no further" to
                outcomes("third filter malformed after a decision", "OneKeptSlots", "AnyKeptSlots"),
            "an empty scalar reads no list" to outcomes("empty scalar beside a malformed row", "OneSlots", "AnySlots"),
            "a group is read field by field, not row by row" to outcomes("group read field by field", "OnePeriods"),
            "and stops at a decision in its first field" to outcomes("group decided in its first field", "OnePeriods"),
        )
        assertEquals(linkedMapOf(
            "a reached malformed range poisons rather than being skipped" to
                listOf("CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED"),
            "a row the filter drops is never read" to listOf("CLEARED/CLEARED", "1/1", "1/1"),
            "a filtered list reads one kept successor ahead and no further" to listOf("1/1", "CLEARED/CLEARED"),
            "an empty scalar reads no list" to listOf("CLEARED/1", "CLEARED/CLEARED"),
            "a group is read field by field, not row by row" to listOf("CLEARED/CLEARED"),
            "and stops at a decision in its first field" to listOf("1/1"),
        ), actual)
    }
}
