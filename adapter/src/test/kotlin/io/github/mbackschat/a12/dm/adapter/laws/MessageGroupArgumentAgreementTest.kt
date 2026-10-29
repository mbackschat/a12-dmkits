package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Fixed-group witnesses for SPEC-2026-09-30-01. The condition is typed; message arguments stay raw because
 * their concrete syntax, including malformed and competing faults, is the subject of the measurement.
 */
class MessageGroupArgumentAgreementTest {
    private enum class Reads { ROOT_SHALLOW, ROOT_DEEP, OWN, DEEP, SIBLING, NESTED }
    private data class Input(val id: String, val reads: Reads, val parameter: String?, val code: String? = null,
        val twin: Boolean = false)
    data class Row(val id: String, val model: String, val classes: List<String>, val portableClasses: List<String> = classes)

    private fun base(twin: Boolean) = DocumentModelDraft.named("library-group-arguments").locale("en_US")
        .rootGroup("Library") { library -> library.stringField("Branch") { }
            .group("Loans") { loans -> loans.stringField("Book") { }
                .group("Details") { it.stringField("Note") { } } }
            .group("Returns") { returns -> returns.stringField("Borrower") { }
                if (twin) returns.stringField("Book") { } }
        }.build().toJson()

    private fun model(input: Input): String {
        val base = base(input.twin)
        val edit = EditSession.read(base)
        val library = edit.group("/Library")
        val loans = edit.group(library.group("Loans").path().value())
        val details = edit.group(loans.group("Details").path().value())
        val branch = library.str("Branch")
        val book = loans.str("Book")
        val note = details.str("Note")
        val declaring = when (input.reads) {
            Reads.ROOT_SHALLOW, Reads.ROOT_DEEP -> library
            Reads.OWN, Reads.DEEP, Reads.SIBLING -> loans
            Reads.NESTED -> details
        }
        val errorField = when (input.reads) {
            Reads.ROOT_SHALLOW, Reads.ROOT_DEEP -> branch
            Reads.OWN, Reads.DEEP, Reads.SIBLING -> book
            Reads.NESTED -> note
        }
        val condition = when (input.reads) {
            Reads.ROOT_SHALLOW -> branch.filled().and(book.filled())
            Reads.ROOT_DEEP -> branch.filled().and(note.filled())
            Reads.OWN -> book.filled()
            Reads.DEEP -> book.filled().and(note.filled())
            Reads.SIBLING -> book.filled().and(library.str("Returns/Borrower").filled())
            Reads.NESTED -> note.filled()
        }
        return ModelWriter.addRule(base, declaring.rule {
            it.name("CheckGroupArgument").code("GROUP_ARGUMENT").errorField(errorField.field()).`when`(condition)
                .message(Dsl.en(input.parameter?.let { parameter -> "Check \$$parameter\$." } ?: "Check."))
                .build()
        })
    }

    private fun inputs(): List<Input> = buildList {
        fun row(id: String, reads: Reads, argument: String, code: String? = null, twin: Boolean = false) {
            add(Input(id, reads, "#$argument", code, twin))
        }
        row("shallow-relative", Reads.ROOT_SHALLOW, "Loans")
        row("shallow-absolute", Reads.ROOT_SHALLOW, "/Library/Loans")
        row("unread-sibling-relative", Reads.ROOT_SHALLOW, "Returns", "INVALID_GROUP")
        row("unread-sibling-absolute", Reads.ROOT_SHALLOW, "/Library/Returns", "INVALID_GROUP")
        row("unread-descendant-relative", Reads.ROOT_SHALLOW, "Loans/Details", "INVALID_GROUP")
        row("unread-descendant-absolute", Reads.ROOT_SHALLOW, "/Library/Loans/Details", "INVALID_GROUP")
        row("root-absolute", Reads.ROOT_SHALLOW, "/Library")
        row("intermediate-group", Reads.ROOT_DEEP, "Loans")
        row("deep-relative", Reads.ROOT_DEEP, "Loans/Details")
        row("deep-absolute", Reads.ROOT_DEEP, "/Library/Loans/Details")
        row("no-group-short-name", Reads.ROOT_DEEP, "Details", "INVALID_GROUP")
        row("own-bare-is-child", Reads.OWN, "Loans", "INVALID_GROUP")
        row("own-parent-and-child", Reads.OWN, "../Loans")
        row("own-parent", Reads.OWN, "..")
        row("unread-sibling-from-loans", Reads.OWN, "../Returns", "INVALID_GROUP")
        row("unread-child", Reads.OWN, "Details", "INVALID_GROUP")
        row("read-child", Reads.DEEP, "Details")
        row("read-sibling-relative", Reads.SIBLING, "../Returns")
        row("read-sibling-absolute", Reads.SIBLING, "/Library/Returns")
        row("read-sibling-turning-point", Reads.SIBLING, "..Library/Returns")
        row("wrong-turning-point", Reads.SIBLING, "..Wrong/Returns", "INVALID_ENTITY")
        row("root-above-root", Reads.ROOT_SHALLOW, "../Loans", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("nested-above-root", Reads.OWN, "../../Library", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("missing-child", Reads.ROOT_SHALLOW, "Loans/Missing", "INVALID_GROUP")
        row("missing-bare", Reads.ROOT_SHALLOW, "Missing", "INVALID_GROUP")
        row("field-not-group", Reads.ROOT_SHALLOW, "Loans/Book", "INVALID_GROUP")
        row("quoted-group", Reads.ROOT_SHALLOW, "'Loans'")
        row("exact-root-walk", Reads.NESTED, "../..")
        row("past-root-walk", Reads.NESTED, "../../..", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        for ((id, argument) in listOf("known" to "Loans/", "missing" to "Missing/",
            "missing-root" to "/Missing/", "unavailable" to "Returns/", "empty" to "")) {
            row("incomplete-$id", Reads.ROOT_SHALLOW, argument, "MVK_INCOMPLETE_INPUT")
        }
        for ((id, argument) in listOf("known" to "Loans//Details", "missing" to "Missing//Details")) {
            row("separator-$id", Reads.ROOT_DEEP, argument, "MVK_UNEXPECTED_TOKEN")
        }
        for ((id, argument) in listOf("index" to "Loans[1]", "star" to "Loans*", "percent" to "Loans%",
            "quoted-digit" to "'1Loans'", "missing-index" to "Missing[1]", "absolute-index" to "/Library/Loans[1]",
            "open-quote" to "'Loans")) {
            row("lexer-$id", Reads.ROOT_SHALLOW, argument, "MVK_LEXER_STANDARD_ERROR")
        }
        row("lookup-before-minus-relative", Reads.ROOT_SHALLOW, "Lo-ans", "INVALID_GROUP")
        row("lookup-before-minus-absolute", Reads.ROOT_SHALLOW, "/Library/Lo-ans", "INVALID_GROUP")
        for ((id, argument) in listOf("minus-relative" to "Loans-x", "minus-absolute" to "/Library/Loans-x",
            "closing-parenthesis" to "Loans)", "name" to "Loans x")) {
            row("trailing-$id", Reads.ROOT_SHALLOW, argument, "MVK_UNEXPECTED_TOKEN")
        }
        row("nonleading-up", Reads.OWN, "Loans/..", "MVK_UNEXPECTED_TOKEN")
        row("third-dot", Reads.OWN, "...", "MVK_UNEXPECTED_TOKEN")
        row("ambiguous-field-fallback", Reads.ROOT_SHALLOW, "Book", "ERROR_TEXT_FIELDNAME_NOT_UNIQUE", twin = true)
        row("unique-field-fallback", Reads.ROOT_SHALLOW, "Borrower", "INVALID_GROUP", twin = true)
        add(Input("name-position-fallback-control", Reads.ROOT_SHALLOW, "Book", "ERROR_TEXT_FIELDNAME_NOT_UNIQUE", twin = true))
        for ((id, suffix) in listOf("comma" to ",", "open-brace" to "{", "close-brace" to "}",
            "at" to "@", "equals" to "=", "power" to "^")) {
            row("message-lexer-known-$id", Reads.ROOT_SHALLOW, "Loans$suffix", "MVK_LEXER_STANDARD_ERROR")
            row("message-lexer-missing-$id", Reads.ROOT_SHALLOW, "Missing$suffix", "MVK_LEXER_STANDARD_ERROR")
        }
        row("declined-rulegroup-root-slash", Reads.ROOT_SHALLOW, "RuleGroup/Loans", "RULEGROUP_IMMEDIATELY_BELOW_ROOT")
        row("declined-rulegroup-nested-slash", Reads.SIBLING, "RuleGroup/Details", "MVK_UNEXPECTED_TOKEN")
        row("declined-rulegroup-root-minus", Reads.ROOT_SHALLOW, "RuleGroup-x", "RULEGROUP_IMMEDIATELY_BELOW_ROOT")
        row("declined-rulegroup-nested-minus", Reads.SIBLING, "RuleGroup-x", "MVK_UNEXPECTED_TOKEN")
        row("declined-rootgroup-slash", Reads.ROOT_SHALLOW, "RootGroup/Loans", "INVALID_GROUP")
        row("declined-rootgroup-minus", Reads.ROOT_SHALLOW, "RootGroup-x", "MVK_UNEXPECTED_TOKEN")
        row("wrong-turning-point-missing-child", Reads.SIBLING, "..Wrong/Missing", "INVALID_ENTITY")
        row("correct-turning-point-missing-child", Reads.SIBLING, "..Library/Missing", "INVALID_GROUP")
        row("turning-point-before-up", Reads.SIBLING, "..Library/..", "MVK_UNEXPECTED_TOKEN")
        row("absolute-up", Reads.SIBLING, "/..", "MVK_UNEXPECTED_TOKEN")
        row("absolute-up-child", Reads.SIBLING, "/../Loans", "MVK_UNEXPECTED_TOKEN")
    }

    fun rows(): List<Row> = inputs().map {
        val classes = listOfNotNull(it.code)
        Row(it.id, model(it), classes, classes)
    }

    private fun kernelCodes(model: String): List<String> = KernelConsistencyOracle()
        .checkConsistency(model, ReferenceResolvers.selfContained()).filter { it.severity() == Severity.ERROR }
        .map { notification -> Regex("\\[([A-Z][A-Z0-9_]+)]").find(notification.message())
            ?.groupValues?.get(1) ?: error(notification.message()) }

    @Test
    fun groupArgumentsPreserveMeasuredClasses() {
        for ((reads, twin) in inputs().map { it.reads to it.twin }.distinct()) {
            assertEquals(emptyList<String>(), kernelCodes(model(Input("plain-control", reads, null, twin = twin))),
                "plain message: $reads, twin=$twin")
        }
        val rows = rows()
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "each observation has its own identity")
        assertEquals(77, rows.size, "fixed witness denominator")
        val stored = HarvestedModels.expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-group-arguments.json")).readText()))
        assertEquals(rows, stored.map {
            Row(it["id"].asText(), it["model"].asText(), it["classes"].map { code -> code.asText() },
                it["portableClasses"].map { code -> code.asText() })
        }, "portable replay preserves the exact typed model bytes and every observation")
        val kernel = rows.map { it.id to kernelCodes(it.model) }
        assertEquals(rows.map { it.id to it.classes }, kernel, "kernel witness grid")
        val portable = rows.map { row -> row.id to rejectingCodes(row.model) }
        assertEquals(rows.map { it.id to it.portableClasses }, portable, "portable witness grid")
    }
}
