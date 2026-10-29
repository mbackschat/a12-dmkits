package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity as KernelSeverity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Complete typed models separate a group's message footprint from a single explicitly read field. */
class MessageGroupFootprintAgreementTest {
    private val base = DocumentModelDraft.named("library-message-footprint").locale("en_US")
        .rootGroup("Library") { library -> library
            .stringField("Branch") { }
            .group("Loans") { loans -> loans.repeatable(10)
                .stringField("Book") { }.stringField("Borrower") { }
                .stringField("PrivateNote") { it.lineBreaks().noValueValidation() }
                .group("Details") { it.stringField("Note") { } } }
            .group("Returns") { returns -> returns.stringField("Book") { } }
        }.build().toJson()

    data class Row(val id: String, val model: String, val expected: List<String>)

    fun rows(): List<Row> = buildList {
        for (groupRead in listOf(false, true)) {
            for ((parameter, expected) in listOf(
                "Book" to emptyList(),
                "Borrower" to if (groupRead) emptyList() else listOf("INVALID_FIELD"),
                "Borrower.value" to if (groupRead) emptyList() else listOf("INVALID_FIELD"),
                "Details/Note" to if (groupRead) emptyList() else listOf("INVALID_FIELD"),
                "../Returns/Book" to listOf("INVALID_FIELD"),
            )) add(Row("field-$groupRead-$parameter", model(groupRead, false, parameter), expected))
        }
        for (parameter in listOf("Loans", "/Library/Loans", "Returns", "/Library/Returns")) {
            add(Row("group-$parameter", model(false, true, "#$parameter"),
                if ("Loans" in parameter) emptyList() else listOf("INVALID_GROUP")))
        }
        add(Row("group-read-value-validation", model(true, false, "PrivateNote.value"),
            listOf("INVALID_NO_VALUE_VALIDATION")))
        add(Row("group-read-category", model(true, false, "Borrower->Band"), listOf("FIELD_NO_ENUM")))
        for (groupRead in listOf(false, true)) {
            for (parameter in listOf("#Details", "#/Library/Loans/Details")) {
                add(Row("nested-$groupRead-$parameter", model(groupRead, false, parameter),
                    if (groupRead) emptyList() else listOf("INVALID_GROUP")))
            }
            for (parameter in listOf("Borrower", "Borrower.value")) {
                add(Row("wildcard-$groupRead-$parameter", model(groupRead, false, parameter, starred = true),
                    if (!groupRead && parameter.endsWith(".value")) listOf("PARAM_VALUE_WITH_STARREFERENCE") else emptyList()))
            }
            add(Row("computation-$groupRead", computation(groupRead, "../Returns/Book"),
                if (groupRead) emptyList() else listOf("INVALID_FIELD")))
            add(Row("computation-target-$groupRead", computation(groupRead, "Book"), emptyList()))
        }
        for (parameter in listOf("Borrower", "Borrower.value", "Details/Note", "../Returns/Book")) {
            add(Row("shorthand-$parameter", model(true, false, parameter, shorthand = true),
                if (parameter.startsWith("../")) listOf("INVALID_FIELD") else emptyList()))
        }
    }

    private fun computation(groupRead: Boolean, parameter: String?): String {
        val edit = EditSession.read(base)
        val library = edit.group("/Library")
        val loans = edit.group(library.group("Loans").path().value())
        val computation = loans.computation(loans.str("Book")) { builder ->
            builder.name("CopyBorrower").calc(loans.str("Borrower").field().value())
                .message(Dsl.en(if (parameter == null) "Check the copied value." else "Check \$$parameter\$."))
            if (groupRead) builder.commonPrecondition(library.group("Returns").filled())
            builder.build()
        }
        return ModelWriter.addComputation(base, computation)
    }

    private fun model(groupRead: Boolean, rootRule: Boolean, parameter: String?, starred: Boolean = false, shorthand: Boolean = false): String {
        val edit = EditSession.read(base)
        val library = edit.group("/Library")
        val loans = edit.group(library.group("Loans").path().value())
        val book = loans.str("Book")
        val groupCondition = if (shorthand) Dsl.ruleGroup().filled() else loans.ref().filled()
        var condition = if (groupRead) groupCondition.and(book.filled()) else book.filled()
        if (starred) condition = condition.and(Dsl.noFieldFilled(library.flatten("Loans*/Borrower")))
        val declaration = if (rootRule) library else loans
        val rule = declaration.rule {
            it.name("CheckLoan").code("LOAN").errorField(book.field()).`when`(condition)
                .severity(Severity.ERROR)
                .message(Dsl.en(if (parameter == null) "Check the loan." else "Check \$$parameter\$."))
                .build()
        }
        return ModelWriter.addRule(base, rule)
    }

    private fun kernelCodes(model: String): List<String> = KernelConsistencyOracle()
        .checkConsistency(model, ReferenceResolvers.selfContained())
        .filter { it.severity() == KernelSeverity.ERROR }
        .map { notification -> Regex("\\[([A-Z][A-Z0-9_]+)]").find(notification.message())
            ?.groupValues?.get(1) ?: error(notification.message()) }

    @Test
    fun messageReferencesFollowTheKernelGroupFootprint() {
        for (groupRead in listOf(false, true)) for (rootRule in listOf(false, true)) {
            assertEquals(emptyList<String>(), kernelCodes(model(groupRead, rootRule, null)), "plain control")
        }
        for (groupRead in listOf(false, true)) {
            assertEquals(emptyList<String>(), kernelCodes(computation(groupRead, null)), "plain computation control")
        }
        val rows = rows()
        assertEquals(32, rows.size)
        val kernel = rows.map { it.id to kernelCodes(it.model) }
        assertEquals(rows.map { it.id to it.expected }, kernel, "kernel witness grid")
        val stored = HarvestedModels.expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-group-footprint.json")).readText()))
        assertEquals(rows.map { Triple(it.id, it.model, it.expected) }, stored.map {
            Triple(it["id"].asText(), it["model"].asText(), it["classes"].map { code -> code.asText() })
        }, "portable replay keeps every exact authored model and expected result")
        val portable = rows.map { row -> row.id to rejectingCodes(row.model) }
        assertEquals(kernel, portable, "complete-model portable agreement")
    }
}
