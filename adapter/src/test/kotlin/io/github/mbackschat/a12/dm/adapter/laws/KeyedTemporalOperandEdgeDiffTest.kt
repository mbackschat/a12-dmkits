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
 * The edges of a key-selected `ValueAsDate` that `KeyedTemporalOperandDiffTest` leaves alone: a defective index
 * column (a duplicated key, an empty or malformed key cell elsewhere, a key naming the malformed cell), a year the
 * selected value leaves unknown, read keyed at the root and unkeyed in each row by a computation, and the keyed
 * resolution as the date of `AddDays` and `DifferenceInDays`. All three engines agree on every document; what the
 * kernel fires and computes is pinned.
 *
 * Own domain (an insurance claims ledger).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyedTemporalOperandEdgeDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("claims-ledger").locale("en_US")
            .rootGroup("Ledger") { r ->
                r.stringField("Holder") { }
                r.stringField("Key") { }
                r.dateField("Limit") { it.format("yyyy-MM-dd") }
                r.dateField("SeenFrom") { it.format("yyyy-MM-dd") }
                r.group("Claims") { g -> g.repeatable(5).indexField("Code")
                    .stringField("Code") { it.maxLength(2).pattern("[A-Z][0-9]", "A letter and a digit.") }
                    .dateField("SeenAbout") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
                    .dateField("SeenFromRow") { it.format("yyyy-MM-dd") } }
            }.build().toJson()
        val session = EditSession.read(draft)
        val ledger = session.group("/Ledger")
        val claims = session.group("/Ledger/Claims")
        val limit = ledger.date("Limit")
        val keyed = claims.date("SeenAbout").semanticIndex(ledger.field("Key"))
        val holder = ledger.str("Holder")
        val rules: Map<String, Condition> = linkedMapOf(
            "LastAfterLimit" to Dsl.valueAsDateLastDay(keyed).after(limit),
            "ShiftedAfterLimit" to Dsl.addDays(Dsl.valueAsDateFirstDay(keyed), 30).after(limit),
            "SpanOverFive" to Dsl.differenceInDays(Dsl.valueAsDateFirstDay(keyed), limit).gt(5),
        )
        var json = TypedRules.inject(session.toJson(), ledger.computation(ledger.date("SeenFrom").field()) {
            it.name("SeenFromComp").calc(Dsl.valueAsDateFirstDay(keyed).expression())
                .message(Dsl.en("Check the claim.")).build()
        })
        json = TypedRules.inject(json, claims.computation(claims.date("SeenFromRow").field()) {
            it.name("SeenFromRowComp").calc(Dsl.valueAsDateFirstDay(claims.field("SeenAbout")).expression())
                .message(Dsl.en("Check the claim.")).build()
        })
        for ((name, comparison) in rules) {
            json = TypedRules.inject(json, ledger.rule {
                it.name(name).code(name).errorField(holder.field())
                    .`when`(holder.filled().and(comparison)).message(Dsl.en("Check the claim.")).build()
            })
        }
        json
    }

    /** A claim row: its code (null leaves the cell empty) and its partially known date. */
    private data class Claim(val code: String?, val seen: String)

    private fun ledger(key: String, vararg claims: Claim) =
        """{"Ledger":{"Holder":"H","Key":"$key","Limit":"2024-01-10","SeenFrom":"2000-01-01","Claims":[""" +
            claims.joinToString(",") { c ->
                listOfNotNull(c.code?.let { "\"Code\":\"$it\"" }, "\"SeenAbout\":\"${c.seen}\"",
                    "\"SeenFromRow\":\"2000-01-01\"").joinToString(",", "{", "}")
            } + "]}}"

    private val documents = linkedMapOf(
        "a clean column" to ledger("C1", Claim("C1", "2024-00-00"), Claim("C2", "2023-05-00")),
        "a duplicated key" to ledger("C1", Claim("C1", "2024-00-00"), Claim("C1", "2023-05-00")),
        "an empty key cell elsewhere" to ledger("C2", Claim(null, "2024-00-00"), Claim("C2", "2023-05-00")),
        "a malformed key cell elsewhere" to ledger("C2", Claim("c1", "2024-00-00"), Claim("C2", "2023-05-00")),
        "the key names a malformed cell" to ledger("c1", Claim("c1", "2024-00-00"), Claim("C2", "2023-05-00")),
        "the selected year is unknown" to ledger("C1", Claim("C1", "0000-00-00"), Claim("C2", "2023-05-00")),
    )

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * The rules each document fires on the kernel's dynamic strategy, with every computed outcome. `Limit` is
     * 2024-01-10. A clean match resolves whatever the other key cells carry, so with `C2` (2023-05-00) selected only
     * the 254-day difference exceeds its bound, while the computation over that column clears; a duplicated key and
     * a key naming a malformed cell resolve nothing. An unknown year clears both the keyed and the unkeyed target.
     */
    @Test
    fun theKernelResolvesTheEdges() {
        val observed = documents.mapValues { (_, doc) ->
            DocumentDifferential.kernel(model, doc, dynamic = true).let { o ->
                o.messages.map { it.code }.sorted().joinToString() + " | " + o.outcomes.toSortedMap()
            }
        }
        val rows = "Claims/SeenFromRow=VALUE|2024-01-01, Claims[2]/SeenFromRow=VALUE|2023-05-01"
        assertEquals(linkedMapOf(
            "a clean column" to "LastAfterLimit, SeenFromComp, SeenFromRowComp, SeenFromRowComp, ShiftedAfterLimit, " +
                "SpanOverFive | {$rows, SeenFrom=VALUE|2024-01-01}",
            "a duplicated key" to "SeenFromRowComp, SeenFromRowComp, uniqueIndex, uniqueIndex | {$rows, SeenFrom=CLEARED}",
            "an empty key cell elsewhere" to "SeenFromComp, SeenFromRowComp, SeenFromRowComp, SpanOverFive, " +
                "mandatoryField | {$rows, SeenFrom=CLEARED}",
            "a malformed key cell elsewhere" to "SeenFromComp, SeenFromRowComp, SeenFromRowComp, SpanOverFive, " +
                "stringFalschesMuster | {$rows, SeenFrom=CLEARED}",
            "the key names a malformed cell" to
                "SeenFromRowComp, SeenFromRowComp, stringFalschesMuster | {$rows, SeenFrom=CLEARED}",
            "the selected year is unknown" to "SeenFromRowComp | {Claims/SeenFromRow=CLEARED, " +
                "Claims[2]/SeenFromRow=VALUE|2023-05-01, SeenFrom=CLEARED}",
        ), observed)
    }

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }
}
