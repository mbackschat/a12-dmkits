package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-02-02 on our own carrier: which spelling of the `index(…)` terminal's value suffix a message
 * admits, crossed over the model's condition language and the message's locale.
 *
 * The two axes are moved independently on one model shape, so a row that admits `.value` under a German
 * MESSAGE but an English CONDITION language separates "the condition language picks it" from "the message
 * locale picks it". A raw English condition on the German model is the control that the language took effect.
 */
class MessageIndexSuffixLanguageAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private fun base(language: ConditionLanguage, germanLocale: Boolean): String {
        val draft = DocumentModelDraft.named("library-index-suffix").locale("en_US")
            .let { if (germanLocale) it.locale("de_DE") else it }
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .group("Loans") { loans -> loans.repeatable(20).indexField("Ticket")
                    .stringField("Ticket") { }.stringField("Book") { } }
            }.build().toJson()
        return EditSession.read(draft).setConditionLanguage(language.code).toJson()
    }

    /** A German message travels beside a parameter-free English one, which the declared `en_US` requires. */
    private fun model(language: ConditionLanguage, message: LocalizedText): String {
        val german = message.locale() == "de_DE"
        val messages = if (german) arrayOf(Dsl.en("Row."), message) else arrayOf(message)
        return rule(base(language, german), LOANS, "Book", *messages) { edit ->
            val loans = edit.group(LOANS)
            loans.str("Ticket").filled().and(loans.str("Book").notFilled())
        }
    }

    fun rows(): List<Row> = buildList {
        fun row(id: String, language: ConditionLanguage, message: LocalizedText, vararg classes: String) =
            add(Row(id, model(language, message), classes.toList()))
        for (language in ConditionLanguage.entries) for ((locale, text) in listOf("en" to Dsl::en, "de" to Dsl::de)) {
            val id = "${language.name.lowercase()}-condition-$locale-message"
            val (admitted, refused) = if (language == ConditionLanguage.DE) "Wert" to "value" else "value" to "Wert"
            row("$id-control", language, text("Row."))
            row("$id-$admitted", language, text("Row \$index(Ticket).$admitted\$."))
            row("$id-$refused", language, text("Row \$index(Ticket).$refused\$."), UNEXPECTED)
        }
        row("de-condition-en-message-capitalised", ConditionLanguage.DE, Dsl.en("Row \$Index(Ticket).Wert\$."), UNEXPECTED)
        row("de-condition-en-message-no-suffix", ConditionLanguage.DE, Dsl.en("Row \$index(Ticket)\$."),
            "INVALID_INDEX_FIELD_PARAMETER")
        add(Row("de-condition-english-spelling-control", RuntimeLaws.withRule(base(ConditionLanguage.DE, false), LOANS,
            "EnglishSpelling", "../Book", "ENGLISH_SPELLING", "FieldFilled(Ticket)"), listOf(UNEXPECTED)))
    }

    @Test
    fun theConditionLanguageAlonePicksTheSuffix() {
        val rows = rows()
        MessageParameterProbes.replay("index-language", rows.map { Triple(it.id, it.model, it.classes) })
        assertEquals(15, rows.size, "frozen witness rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable classes")
    }

    private companion object {
        const val LOANS = "/Library/Loans"
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
    }
}
