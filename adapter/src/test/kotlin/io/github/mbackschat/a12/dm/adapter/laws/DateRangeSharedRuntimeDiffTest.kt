package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * An interpreted DateRange is converted under its own declared format, then rendered, checked and completed under the
 * runtime format its class shares: the first declaration of the same year interpretation and additional-check flag
 * (KERNEL-FINDINGS KF540, a12-kernel-lean SPEC-2026-10-09-12; the order-only control is [DateRangeSharedFormatOrderDiffTest]).
 *
 * Every admitted format/separator pair is declared under FROM and under TO, plus a FROM/TO month pair with the pre-1900
 * check and a standard month control, and each source feeds its start year, end year and end day. Three declaration
 * orders lead with the dotted pair, the month pair, or a month TO before a dotted FROM; each runs without and with a
 * Base Year. Two interpreted targets, each declared like its sources but in the other interpretation, copy across the
 * classes: the kernel admits a copy only between fitting component classes.
 *
 * Own domain (an insurance policy's seasonal cover windows).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DateRangeSharedRuntimeDiffTest {
    private enum class Order { DOTTED_FIRST, MONTH_FIRST, MONTH_TO_BEFORE_DOTTED_FROM }

    private data class Profile(val name: String, val format: String, val separator: String,
                               val ordered: String, val wrapping: String)

    private data class Source(val name: String, val profile: Profile, val interpretation: RangeYearInterpretation?,
                              val flagged: Boolean = false)

    private companion object {
        const val ROOT = "/Cover"
        val DOT = Profile("Dot", "dd.MM", "-", "01.03-31.10", "01.11-28.02")
        val MON = Profile("Mon", "MM", "/", "03/10", "11/02")
        val PROFILES = listOf(
            DOT, MON,
            Profile("Cmp", "MM", "", "0310", "1102"),
            Profile("Full", "dd.MM.yyyy", "-", "01.03.2023-31.10.2024", "01.11.2023-28.02.2024"),
            Profile("Iso", "yyyy-MM-dd", "/", "2023-03-01/2024-10-31", "2023-11-01/2024-02-28"),
            Profile("Yr", "yyyy", "/", "2023/2024", "2023/2024"),
            Profile("Ym", "yyyy-MM", "/", "2023-03/2024-10", "2023-11/2024-02"),
            Profile("Md", "MM-dd", "/", "03-01/10-31", "11-01/02-28"),
        )
        val FROM = RangeYearInterpretation.FROM
        val TO = RangeYearInterpretation.TO
    }

    private fun sources(order: Order): List<Source> {
        val interpreted = PROFILES.flatMap { listOf(Source("${it.name}From", it, FROM), Source("${it.name}To", it, TO)) }
        val lead = when (order) {
            Order.DOTTED_FIRST -> listOf("DotFrom", "DotTo")
            Order.MONTH_FIRST -> listOf("MonFrom", "MonTo")
            Order.MONTH_TO_BEFORE_DOTTED_FROM -> listOf("MonTo", "DotFrom")
        }
        return interpreted.filter { it.name in lead }.sortedBy { lead.indexOf(it.name) } +
            interpreted.filter { it.name !in lead } +
            listOf(Source("MonFlagFrom", MON, FROM, flagged = true), Source("MonFlagTo", MON, TO, flagged = true),
                Source("PlainMon", MON, null))
    }

    private val models: Map<String, String> = Order.entries.flatMap { order ->
        listOf(null, 2024).map { baseYear -> "$order, base year ${baseYear ?: "none"}" to build(order, baseYear) }
    }.toMap()

    private fun build(order: Order, baseYear: Int?): String {
        val sources = sources(order)
        val authored = DocumentModelDraft.named("cover-windows-${order.name.lowercase()}-${baseYear ?: 0}")
            .locale("en_US")
            .rootGroup("Cover") { cover ->
                for (source in sources) cover.dateRangeField(source.name) { range ->
                    range.format(source.profile.format).separator(source.profile.separator)
                    source.interpretation?.let { range.interpretationOfYear(it) }
                    if (source.flagged) range.rejectBefore1900()
                }
                cover.dateRangeField("CopyIntoTo") { it.format("dd.MM").separator("-").interpretationOfYear(TO) }
                cover.dateRangeField("CopyIntoFrom") { it.format("MM").separator("/").interpretationOfYear(FROM) }
                for (name in readNames(sources))
                    for (suffix in listOf("StartYear", "EndYear", "EndDay"))
                        cover.numberField("$name$suffix") { it.fractionDigits(0) }
                cover.numberField("PlainMonStartMonth") { it.fractionDigits(0) }
            }.build().toJson()
        val document = ModelDocument.read(authored).setTimeZone("UTC")
        baseYear?.let { document.setBaseYear(it) }
        val base = document.toJson()
        val model = BoundModel.ofJson(base)
        fun range(name: String) = model.dateRange("$ROOT/$name")
        val computations = buildList {
            add(model.computation(range("CopyIntoTo")).name("CopyIntoToComp")
                .calc(Dsl.firstFilledDateRange(range("DotFrom").field().firstFilledOperand(), range("DotTo").field().firstFilledOperand()).expression())
                .message(Dsl.en("Copy the window.")).build())
            add(model.computation(range("CopyIntoFrom")).name("CopyIntoFromComp")
                .calc(Dsl.firstFilledDateRange(range("MonTo").field().firstFilledOperand(), range("MonFrom").field().firstFilledOperand()).expression())
                .message(Dsl.en("Copy the window.")).build())
            add(model.computation(model.num("$ROOT/PlainMonStartMonth")).name("PlainMonStartMonthComp")
                .calc(Dsl.monthFromDate(Dsl.startOfDateRange(range("PlainMon")))).message(Dsl.en("Read the start.")).build())
            for (name in readNames(sources)) {
                add(model.computation(model.num("$ROOT/${name}StartYear")).name("${name}StartYearComp")
                    .calc(Dsl.yearFromDate(Dsl.startOfDateRange(range(name)))).message(Dsl.en("Read the start.")).build())
                add(model.computation(model.num("$ROOT/${name}EndYear")).name("${name}EndYearComp")
                    .calc(Dsl.yearFromDate(Dsl.endOfDateRange(range(name)))).message(Dsl.en("Read the end.")).build())
                add(model.computation(model.num("$ROOT/${name}EndDay")).name("${name}EndDayComp")
                    .calc(Dsl.dayFromDate(Dsl.endOfDateRange(range(name)))).message(Dsl.en("Read the end.")).build())
            }
        }
        return computations.fold(base) { json, computation -> TypedRules.inject(json, computation) }
    }

    /** The ranges whose years and end day are read: every interpreted source and both targets. */
    private fun readNames(sources: List<Source>) =
        sources.filter { it.interpretation != null }.map { it.name } + listOf("CopyIntoTo", "CopyIntoFrom")

    /** Every source holds [value] of its own profile; the standard month control always holds `03/10`; numbers are seeded. */
    private fun document(value: (Profile) -> String?): String {
        val cells = sources(Order.DOTTED_FIRST).filter { it.interpretation != null }
            .mapNotNull { source -> value(source.profile)?.let { "\"${source.name}\":\"$it\"" } } +
            "\"PlainMon\":\"03/10\""
        val seeds = readNames(sources(Order.DOTTED_FIRST))
            .flatMap { name -> listOf("StartYear", "EndYear", "EndDay").map { "\"$name$it\":\"9\"" } } +
            "\"PlainMonStartMonth\":\"9\""
        return "{\"Cover\":{${(cells + seeds).joinToString(",")}}}"
    }

    private val documents = linkedMapOf(
        "ordered" to document { it.ordered },
        "wrapping" to document { it.wrapping },
        "empty" to document { null },
        "malformed" to document { "bad" },
    )

    @Test
    fun everyModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), models.values.flatMap { RuntimeLaws.consistencyMessages(it) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryModelAndDocument() {
        for ((name, model) in models) {
            try {
                DocumentDifferential.assertAgree(model, documents)
            } catch (failure: AssertionError) {
                throw AssertionError("$name: ${failure.message}", failure)
            }
        }
    }

    /** a12-kernel-lean's measured rows, re-observed on dynamic Groovy in this domain. */
    @Test
    fun theSharedFormatDecidesTheReadsTheMatrixPredicts() {
        fun outcome(model: String, document: String, cell: String) =
            DocumentDifferential.kernel(models.getValue(model), documents.getValue(document), dynamic = true)
                .outcomes.getValue(cell)
        fun formal(model: String, document: String) =
            DocumentDifferential.kernel(models.getValue(model), documents.getValue(document), dynamic = true)
                .messages.map { it.code + " " + it.pointer.substringAfterLast('/') }.filter { !it.startsWith("Copy") }.toSet()
        val dotted = "DOTTED_FIRST, base year none"
        val dotted2024 = "DOTTED_FIRST, base year 2024"
        val month = "MONTH_FIRST, base year none"
        val mixed = "MONTH_TO_BEFORE_DOTTED_FROM, base year none"
        val actual = linkedMapOf(
            "dotted-first: a month-only source ends on day 1" to outcome(dotted, "ordered", "MonFromEndDay"),
            "dotted-first: a full Date keeps day 31" to outcome(dotted, "ordered", "FullFromEndDay"),
            "dotted-first: a full Date loses its 2024 end year" to outcome(dotted, "ordered", "FullFromEndYear"),
            "dotted-first, 2024: the same end year is the Base Year" to outcome(dotted2024, "ordered", "FullFromEndYear"),
            "dotted-first, 2024: a FROM wrap ends the next year" to outcome(dotted2024, "wrapping", "DotFromEndYear"),
            "dotted-first, 2024: a TO wrap starts the year before" to outcome(dotted2024, "wrapping", "DotToStartYear"),
            "dotted-first: a year-only source ends on day 1" to outcome(dotted, "ordered", "YrFromEndDay"),
            "month-first: a dotted source ends on its month's last day" to outcome(month, "ordered", "DotFromEndDay"),
            "month-first: a year-only source ends on day 31" to outcome(month, "ordered", "YrFromEndDay"),
            "dotted-first: the flagged month class keeps its month format" to outcome(dotted, "ordered", "MonFlagFromEndDay"),
            "month TO first: a dotted copy into a dotted TO target renders by month" to outcome(mixed, "ordered", "CopyIntoTo"),
            "month TO first: a month copy into a month FROM target renders dotted" to outcome(mixed, "ordered", "CopyIntoFrom"),
            "dotted-first: the dotted copy keeps its dotted rendering" to outcome(dotted, "ordered", "CopyIntoTo"),
        )
        assertEquals(linkedMapOf(
            "dotted-first: a month-only source ends on day 1" to "VALUE|1",
            "dotted-first: a full Date keeps day 31" to "VALUE|31",
            "dotted-first: a full Date loses its 2024 end year" to "VALUE|2000",
            "dotted-first, 2024: the same end year is the Base Year" to "VALUE|2024",
            "dotted-first, 2024: a FROM wrap ends the next year" to "VALUE|2025",
            "dotted-first, 2024: a TO wrap starts the year before" to "VALUE|2023",
            "dotted-first: a year-only source ends on day 1" to "VALUE|1",
            "month-first: a dotted source ends on its month's last day" to "VALUE|31",
            "month-first: a year-only source ends on day 31" to "VALUE|31",
            "dotted-first: the flagged month class keeps its month format" to "VALUE|31",
            "month TO first: a dotted copy into a dotted TO target renders by month" to "VALUE|03/10",
            "month TO first: a month copy into a month FROM target renders dotted" to "VALUE|01.03-31.10",
            "dotted-first: the dotted copy keeps its dotted rendering" to "VALUE|01.03-31.10",
        ), actual)
        // A wrapping month pair is invalid under both directions once the shared format is month-only, and a malformed
        // value is refused through the shared separator, the compact declaration's empty one included.
        assertEquals(true, "datumBereichNichtGueltig DotFrom" in formal(month, "wrapping"))
        assertEquals(true, "datumBereichNichtGueltig DotTo" in formal(month, "wrapping"))
        assertEquals(true, "datumBereichTrennerFehlt CmpFrom" in formal(dotted, "malformed"))
    }
}
