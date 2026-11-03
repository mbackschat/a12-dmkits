package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The seven field-fill consumers share pass selection, but stop and restart at different witnesses (SPEC-2026-10-10-02). */
class SparseFilteredFillDiffTest {
    private enum class Quantifier(val build: (List<FieldOperand>) -> Condition) {
        All(Dsl::allFieldsFilled), No(Dsl::noFieldFilled), Any(Dsl::atLeastOneFieldFilled),
        Multiple(Dsl::moreThanOneFieldFilled), NotAll(Dsl::notAllFieldsFilled),
        NotOne(Dsl::notExactlyOneFieldFilled), Mixed(Dsl::fieldsNotCollectivelyFilled),
    }
    private val model = underUtc {
        val draft = DocumentModelDraft.named("dispatch-filtered-fill").locale("en_US")
            .rootGroup("Dispatch") { root ->
                root.numberField("Anchor") { it.fractionDigits(0) }
                for (q in Quantifier.entries) root.numberField(q.name) { it.fractionDigits(0) }
                root.group("Runs") { runs -> runs.repeatable(2).group("Parcels") { parcels ->
                    parcels.repeatable(51).numberField("Permit") { it.fractionDigits(0) }
                        .numberField("Charge") { it.fractionDigits(0) }
                        .dateTimeField("RecordedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                } }
            }.build().toJson()
        val edit = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = edit.group("/Dispatch")
        val parcels = edit.group("/Dispatch/Runs/Parcels")
        val selected = root.flatten("Runs*/Parcels*/Charge").having(parcels.num("Permit").filled()
            .or(parcels.dateTime("RecordedAt").eq(Dsl.dateTimeValue("29.02.1500T10:00:00"))))
        var json = edit.toJson()
        for (q in Quantifier.entries) {
            val condition = q.build(listOf(selected))
            json = TypedRules.inject(json, root.rule {
                it.name(q.name + "Rule").code(q.name).errorField(root.num("Anchor"))
                    .`when`(root.num("Anchor").filled().and(condition)).message(Dsl.en("Check dispatch charges.")).build()
            })
            json = TypedRules.inject(json, root.computation(root.num(q.name)) {
                it.name(q.name + "Comp").commonPrecondition(condition).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check dispatch charges.")).build()
            })
        }
        json
    }

    private fun document(width: Int, early: Boolean = false, blank: Boolean = false): String {
        val first = List(width) { "{\"Permit\":1${if (early) ",\"Charge\":5" else ""}}" }.joinToString(",")
        val seeds = Quantifier.entries.joinToString(",") { "\"${it.name}\":99" }
        return """{"Dispatch":{"Anchor":1,$seeds,"Runs":[{"Parcels":[$first]},
            {"Parcels":[{"Permit":1,"Charge":5}${if (blank) ",{}" else ""}]}]}}"""
    }

    @Test
    fun allSevenConsumersPreserveSparsePresenceDenseAbsenceAndDecidingPrefixes() = underUtc {
        val cases = listOf(
            Triple("small", document(1), "PFTFPFP"),
            Triple("dense-late", document(50), "FPPPTPP"),
            Triple("sparse-late", document(51), "FPTPTPT"),
            Triple("dense-early", document(50, early = true), "PFTTPTP"),
            Triple("sparse-blank", document(51, blank = true), "FPPPTPP"),
        )
        val differences = mutableListOf<String>()
        for ((id, json, signature) in cases) {
            val kernel = DocumentDifferential.kernel(model, json, dynamic = true)
            for ((index, q) in Quantifier.entries.withIndex()) {
                val expected = signature[index]
                assertEquals(if (expected == 'T') "VALUE|1" else "CLEARED", kernel.outcomes[q.name], "$id/${q.name} computation")
                assertEquals(when (expected) { 'T' -> listOf("OMISSION_ERROR"); 'P' -> listOf("VALUE_ERROR"); else -> emptyList() },
                    kernel.messages.filter { it.code == q.name }.map { it.type }, "$id/${q.name} authored rule")
                assertEquals(expected == 'P', kernel.operandErrors.any {
                    it.code == "berechnungsWertFehler" && it.pointer.endsWith("/" + q.name)
                }, "$id/${q.name} computation failure")
            }
            assertEquals(kernel, DocumentDifferential.kernel(model, json, dynamic = false), id)
            val actual = DocumentDifferential.interpreter(model, json)
            if (kernel != actual) differences += "$id: expected=$kernel actual=$actual"
        }
        assertEquals(emptyList<String>(), differences)
    }
}
