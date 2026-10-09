package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * What a key-selected cell yields where a temporal operation reads one field (`validate.laws.KeyedTemporalOperandLawsTest`
 * measures the admission): a partially known date resolved by `ValueAsDate`, and a text day in a `Date`
 * construction, each selected by a field key and by a literal one. All three engines agree on every document; which
 * rules fire is pinned on the kernel.
 *
 * Own domain (an insurance claims register).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedTemporalOperandDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("claims-register").locale("en_US")
            .rootGroup("Register") { r ->
                r.stringField("Holder") { }
                r.stringField("Key") { }
                r.dateField("Limit") { it.format("yyyy-MM-dd") }
                r.dateField("ReportedUntil") { it.format("yyyy-MM-dd") }
                r.group("Claims") { g -> g.repeatable(5).indexField("Code")
                    .stringField("Code") { }
                    .dateField("ReportedAbout") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL) }
                    .stringField("DayText") { it.maxLength(2).pattern("[0-9]+", "Digits only.") } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val register = session.group("/Register")
        val claims = session.group("/Register/Claims")
        val key = register.field("Key")
        val limit = register.date("Limit")
        val reported = claims.date("ReportedAbout")
        val day = claims.str("DayText")
        val year = Dsl.temporalComponent("2024")
        val january = Dsl.temporalComponent("01")
        val rules: Map<String, Condition> = linkedMapOf(
            "FirstAfterLimit" to Dsl.valueAsDateFirstDay(reported.semanticIndex(key)).after(limit),
            "LastAfterLimit" to Dsl.valueAsDateLastDay(reported.semanticIndex(key)).after(limit),
            "C2LastAfterLimit" to Dsl.valueAsDateLastDay(reported.semanticIndex("C2")).after(limit),
            "DayAfterLimit" to Dsl.date(day.semanticIndex(key), january, year).after(limit),
            "C2DayAfterLimit" to Dsl.date(day.semanticIndex("C2"), january, year).after(limit),
        )
        val holder = register.str("Holder")
        var json = TypedRules.inject(session.toJson(), register.computation(register.date("ReportedUntil").field()) {
            it.name("ReportedUntilComp").calc(Dsl.valueAsDateLastDay(reported.semanticIndex(key)).expression())
                .message(Dsl.en("Check the claim.")).build()
        })
        for ((name, comparison) in rules) {
            json = TypedRules.inject(json, register.rule {
                it.name(name).code(name).errorField(holder.field())
                    .`when`(holder.filled().and(comparison)).message(Dsl.en("Check the claim.")).build()
            })
        }
        json
    }

    private fun register(key: String?, vararg claims: Triple<String, String?, String?>) =
        """{"Register":{"Holder":"H","Limit":"2024-01-10","ReportedUntil":"2000-01-01",""" + (key?.let { "\"Key\":\"$it\"," } ?: "") +
            """"Claims":[""" + claims.joinToString(",") { (code, reported, day) ->
                listOfNotNull("\"Code\":\"$code\"", reported?.let { "\"ReportedAbout\":\"$it\"" },
                    day?.let { "\"DayText\":\"$it\"" }).joinToString(",", "{", "}")
            } + "]}}"

    private val documents = linkedMapOf(
        "key selects the first claim" to
            register("C1", Triple("C1", "2024-01-00", "05"), Triple("C2", "2024-02-00", "20")),
        "key selects the second claim" to
            register("C2", Triple("C1", "2024-01-00", "05"), Triple("C2", "2024-02-00", "20")),
        "key selects nothing" to register("C9", Triple("C1", "2024-01-00", "05"), Triple("C2", "2024-02-00", "20")),
        "key is empty" to register(null, Triple("C1", "2024-01-00", "05"), Triple("C2", "2024-02-00", "20")),
        "selected cells are empty" to register("C2", Triple("C1", "2024-01-00", "05"), Triple("C2", null, null)),
        "month unknown" to register("C1", Triple("C1", "2024-00-00", "05"), Triple("C2", "2023-00-00", "20")),
        "selected cell malformed" to register("C1", Triple("C1", "2024-13-00", "05"), Triple("C2", "2024-02-00", "20")),
        "other cell malformed" to register("C2", Triple("C1", "2024-13-00", "05"), Triple("C2", "2024-02-00", "20")),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The rules each document fires on the kernel's dynamic strategy, with the computed last day. Limit is
     * 2024-01-10: a key-selected partial date is read as the boundary its row's value names, an unknown month
     * included; a key that selects no row, an empty key and an empty cell give no value, which clears the target;
     * a malformed selected cell gives none either, while a malformed cell in another row leaves the read alone.
     */
    @Test
    fun aKeyedCellResolvesAsTheRowItSelects() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).let { o ->
                o.messages.map { it.code }.sorted().joinToString() + " | " + o.outcomes["ReportedUntil"]
            }
        }
        assertEquals(linkedMapOf(
            "key selects the first claim" to
                "C2DayAfterLimit, C2LastAfterLimit, LastAfterLimit, ReportedUntilComp | VALUE|2024-01-31",
            "key selects the second claim" to "C2DayAfterLimit, C2LastAfterLimit, DayAfterLimit, FirstAfterLimit, " +
                "LastAfterLimit, ReportedUntilComp | VALUE|2024-02-29",
            "key selects nothing" to "C2DayAfterLimit, C2LastAfterLimit | CLEARED",
            "key is empty" to "C2DayAfterLimit, C2LastAfterLimit | CLEARED",
            "selected cells are empty" to " | CLEARED",
            "month unknown" to "C2DayAfterLimit, LastAfterLimit, ReportedUntilComp | VALUE|2024-12-31",
            "selected cell malformed" to "C2DayAfterLimit, C2LastAfterLimit, datumFormatFalsch | CLEARED",
            "other cell malformed" to "C2DayAfterLimit, C2LastAfterLimit, DayAfterLimit, FirstAfterLimit, " +
                "LastAfterLimit, ReportedUntilComp, datumFormatFalsch | VALUE|2024-02-29",
        ), observed)
    }

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }
}
