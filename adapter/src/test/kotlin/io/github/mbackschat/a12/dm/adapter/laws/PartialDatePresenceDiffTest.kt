package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.fired
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — SPEC-2026-10-02-04 on our own carrier: a partially known Date is a FILLED value for
 * every presence reader, on both arms, and only an omission too deep for the declared precision leaves presence
 * unknown.
 *
 * `Inception` is `yyyy-MM-dd` at `DAY_OPTIONAL` and `Expiry` at `YEAR_OPTIONAL`, beside a component-omitting
 * `yyyy-MM` Date `Month` and a `yyyy-MM` DateFragment `Season`. The too-deep row is what keeps the account
 * honest: `2024-00-00` is the same text in both precise fields, a formal error under `DAY_OPTIONAL` and an admitted
 * partial value under `YEAR_OPTIONAL`, so presence follows the precision and not the spelling. The reader rows
 * move one partially known value through requiredness, group and root content and `FieldValuesNotUnique`, whose
 * `2024-06-00` beside `2024-06-01` separates a stored-text comparison from a completed-date one, on two named
 * fields and down a starred column alike.
 *
 * Own domain (an insurance cover and its insured party).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartialDatePresenceDiffTest {
    private val mapper = ObjectMapper()
    private val fields = listOf("Inception", "Expiry", "Month", "Season")

    private val coverBase = DocumentModelDraft.named("cover-partial-date").locale("en_US")
        .rootGroup("Cover") { cover ->
            cover.dateField("Inception") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                .dateField("Expiry") { it.format(ISO).datePrecision(DatePrecision.YEAR_OPTIONAL) }
                .dateField("Month") { it.format("yyyy-MM") }.dateFragmentField("Season") { it.fragmentFormat("yyyy-MM") }
                .numberField("Filled") { it.fractionDigits(0) }
            for (field in fields) {
                cover.numberField("Has$field") { it.fractionDigits(0) }.numberField("Lacks$field") { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val cover = BoundModel.ofJson(coverBase)
    private fun coverField(name: String): FieldRef = cover.field("/Cover/$name")
    private val count = Dsl.numberOfFilledFields(fields.map(::coverField))

    private val computing = admitted(fields.flatMap { field ->
        listOf("Has$field" to coverField(field).filled(), "Lacks$field" to coverField(field).notFilled())
    }.map { (target, guard) ->
        cover.computation(cover.num("/Cover/$target")).name("${target}Comp").`when`(guard).calc(Dsl.number(1L))
            .message(Dsl.en("$target recomputed.")).build()
    }.plus(cover.computation(cover.num("/Cover/Filled")).name("FilledComp").calc(count)
        .message(Dsl.en("Filled recomputed.")).build())
        .fold(coverBase) { json, comp -> TypedRules.inject(json, comp) })

    private val validating = admitted(fields.flatMap { field ->
        listOf(rule(cover, "/Cover", field, "HAS_${field.uppercase()}", coverField(field).filled()),
            rule(cover, "/Cover", field, "LACKS_${field.uppercase()}", coverField(field).notFilled()))
    }.plus(rule(cover, "/Cover", "Inception", "FOUR_FILLED", count.eq(4L)))
        .fold(coverBase) { json, r -> TypedRules.inject(json, r) })

    private fun rule(m: BoundModel, group: String, errorField: String, code: String, condition: Condition) =
        m.rule(m.group(group)).name("${code}Rule").errorField(m.field("$group/$errorField")).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private fun coverDocument(vararg values: String?): String = mapper.writeValueAsString(mapOf("Cover" to
        fields.zip(values).filter { it.second != null }.toMap() + fields.flatMap { listOf("Has$it" to 99, "Lacks$it" to 99) }
            + ("Filled" to 99)))

    private fun computed(filled: List<String>, notFilled: List<String>, count: Int?) =
        (fields.flatMap { field ->
            listOf("Has$field" to (field in filled), "Lacks$field" to (field in notFilled))
        }.map { (target, holds) -> "/Cover[1]/$target|" + if (holds) "VALUE|1" else "CLEARED" } +
            ("/Cover[1]/Filled|" + if (count == null) "CLEARED" else "VALUE|$count")).sorted()

    @Test
    fun aPartiallyKnownDateIsFilledOnTheComputationArm() {
        assertEquals(computed(fields, emptyList(), 4), outcomes(computing, coverDocument("2024-06-00", "0000-00-00", "2024-06", "2024-06")))
        assertEquals(computed(listOf("Inception", "Expiry"), listOf("Month", "Season"), 2),
            outcomes(computing, coverDocument("2024-06-15", "2024-06-15", null, null)))
        assertEquals(computed(emptyList(), fields, 0), outcomes(computing, coverDocument(null, null, null, null)))
        assertEquals(computed(listOf("Expiry"), listOf("Month", "Season"), null),
            outcomes(computing, coverDocument("2024-00-00", "2024-00-00", null, null)), "too deep for DAY_OPTIONAL only")
    }

    @Test
    fun aPartiallyKnownDateIsFilledOnTheValidationArm() {
        fun codes(filled: List<String>, notFilled: List<String>, four: Boolean) = (filled.map { "HAS_${it.uppercase()}" } +
            notFilled.map { "LACKS_${it.uppercase()}" } + if (four) listOf("FOUR_FILLED") else emptyList()).sorted()
        assertEquals(codes(fields, emptyList(), true), fired(validating, coverDocument("2024-06-00", "0000-00-00", "2024-06", "2024-06")))
        assertEquals(codes(listOf("Inception", "Expiry"), listOf("Month", "Season"), false),
            fired(validating, coverDocument("2024-06-15", "2024-06-15", null, null)))
        assertEquals(codes(emptyList(), fields, false), fired(validating, coverDocument(null, null, null, null)))
        assertEquals((codes(listOf("Expiry"), listOf("Month", "Season"), false) + "datumFalsch").sorted(),
            fired(validating, coverDocument("2024-00-00", "2024-00-00", null, null)), "too deep for DAY_OPTIONAL only")
    }

    private val insuredBase = DocumentModelDraft.named("insured-partial-date").locale("en_US")
        .rootGroup("Insured") { insured ->
            insured.dateField("First") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                .dateField("Second") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                .dateField("Birth") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL).required() }
                .dateField("Moved") { it.format(ISO).datePrecision(DatePrecision.MONTH_OPTIONAL) }
                .dateField("Moved2") { it.format(ISO).datePrecision(DatePrecision.MONTH_OPTIONAL) }
                .dateField("Origin") { it.format(ISO).datePrecision(DatePrecision.YEAR_OPTIONAL) }
                .dateField("Origin2") { it.format(ISO).datePrecision(DatePrecision.YEAR_OPTIONAL) }
                .stringField("Name") { it.required() }
                .group("Extra") { it.dateField("Noted") { f -> f.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) } }
                .group("Visits") { it.repeatable(3).dateField("On") { f -> f.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) } }
        }
        .rootGroup("Claimant") { claimant ->
            claimant.dateField("Since") { it.format(ISO).datePrecision(DatePrecision.DAY_OPTIONAL) }
                .numberField("Amount") { it.fractionDigits(0) }
                .group("Lines") { it.repeatable(3).numberField("Value") { f -> f.fractionDigits(0) } }
        }.build().toJson()

    private val insured = BoundModel.ofJson(insuredBase)
    private val extra: GroupRef = insured.group("/Insured/Extra")

    private val readers = admitted(listOf(
        rule(insured, "/Insured", "First", "NOT_UNIQUE",
            Dsl.fieldValuesNotUnique(insured.field("/Insured/First"), insured.field("/Insured/Second"))),
        rule(insured, "/Insured", "Moved", "MOVED_NOT_UNIQUE",
            Dsl.fieldValuesNotUnique(insured.field("/Insured/Moved"), insured.field("/Insured/Moved2"))),
        rule(insured, "/Insured", "Origin", "ORIGIN_NOT_UNIQUE",
            Dsl.fieldValuesNotUnique(insured.field("/Insured/Origin"), insured.field("/Insured/Origin2"))),
        rule(insured, "/Insured", "Visits/On", "VISITS_NOT_UNIQUE", Dsl.fieldValuesNotUnique(insured.flatten("/Insured/Visits*/On"))),
        rule(insured, "/Insured/Extra", "Noted", "EXTRA_FILLED", extra.filled()),
        insured.rule(insured.group("/Claimant")).name("ONCE_LOWRule").errorField(insured.num("/Claimant/Lines/Value"))
            .`when`(Dsl.minValue(insured.flatten("/Claimant/Lines*/Value")).lt(5L))
            .code("ONCE_LOW").severity(Severity.ERROR).message(Dsl.en("ONCE_LOW")).build(),
    ).fold(insuredBase) { json, r -> TypedRules.inject(json, r) })

    private fun insuredDocument(insuredCells: Map<String, Any>, claimantCells: Map<String, Any>, vararg visits: String) =
        mapper.writeValueAsString(mapOf("Insured" to insuredCells + ("Visits" to visits.map { mapOf("On" to it) }),
            "Claimant" to claimantCells))

    @Test
    fun aPartiallyKnownDateSatisfiesTheReadersAndIsComparedByItsStoredText() {
        val partial = fired(readers, insuredDocument(mapOf("First" to "2024-06-00", "Second" to "2024-06-00",
            "Birth" to "2024-06-00", "Moved" to "2024-00-00", "Moved2" to "2024-00-00", "Origin" to "0000-00-00",
            "Origin2" to "0000-00-00", "Extra" to mapOf("Noted" to "2024-06-00")), mapOf("Since" to "2024-06-00"), "2024-06-00", "2024-06-00"))
        assertEquals(listOf("EXTRA_FILLED", "MOVED_NOT_UNIQUE", "NOT_UNIQUE", "ONCE_LOW", "ORIGIN_NOT_UNIQUE",
            "VISITS_NOT_UNIQUE", "mandatoryField"), partial,
            "one required String is empty; the partially known Birth raises nothing")
        val distinct = fired(readers, insuredDocument(mapOf("First" to "2024-06-00", "Second" to "2024-07-00",
            "Moved" to "2024-00-00", "Moved2" to "2025-00-00", "Origin" to "0000-00-00", "Origin2" to "2024-00-00",
            "Name" to "Ada", "Extra" to emptyMap<String, Any>()), emptyMap(), "2024-06-00", "2024-07-00"))
        assertEquals(listOf("mandatoryField"), distinct, "an empty Birth raises its requiredness; nothing else fires")
        val againstFull = fired(readers, insuredDocument(mapOf("First" to "2024-06-00", "Second" to "2024-06-01",
            "Birth" to "2024-06-15", "Name" to "Ada", "Extra" to mapOf("Noted" to "2024-06-15")), mapOf("Amount" to 1), "2024-06-00", "2024-06-01"))
        assertEquals(listOf("EXTRA_FILLED", "ONCE_LOW"), againstFull, "both texts complete to June 1st, yet they are unique")
        val equalFull = fired(readers, insuredDocument(mapOf("First" to "2024-06-15", "Second" to "2024-06-15",
            "Birth" to "2024-06-15", "Name" to "Ada"), emptyMap(), "2024-06-15", "2024-06-15"))
        assertEquals(listOf("NOT_UNIQUE", "VISITS_NOT_UNIQUE"), equalFull, "equal fully known texts are not unique")
    }

    private companion object {
        const val ISO = "yyyy-MM-dd"
    }
}
