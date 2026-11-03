package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-01: distinguish grammar decisions from whole-text fault priority on typed Library models.
 * Message text is intentionally raw: its tokens, including invalid ones, are the measured input.
 */
class MessageGroupEventAgreementTest {
    private enum class Reads { ROOT, OWN, KEYWORD_READ, KEYWORD_UNREAD, KEYWORD_NESTED }
    private enum class Keyword { RULE, ROOT, LINE, USB;
        fun spelling(language: ConditionLanguage): String = when (this) {
            RULE -> if (language == ConditionLanguage.EN) "RuleGroup" else "RegelKontext"
            ROOT -> if (language == ConditionLanguage.EN) "RootGroup" else "LfdNr"
            LINE -> "Zeile"
            USB -> "Usb"
        }
    }
    private data class Input(val id: String, val language: ConditionLanguage, val reads: Reads,
        val argument: String?, val code: String?)
    data class Row(val id: String, val model: String, val classes: List<String>)

    private fun model(input: Input): String {
        val rootWord = Keyword.ROOT.spelling(input.language)
        val draft = DocumentModelDraft.named("library-message-events").locale("en_US")
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .group("Loans") { it.stringField("Book") { } }
                .group("Returns") { it.stringField("Borrower") { } }
                .group(rootWord) { it.group("Archive") { archive -> archive.stringField("Title") { } } }
            }.build().toJson()
        val base = EditSession.read(draft).setConditionLanguage(input.language.code).toJson()
        val edit = EditSession.read(base)
        val library = edit.group("/Library")
        val loans = edit.group(library.group("Loans").path().value())
        val archive = edit.group(library.group("$rootWord/Archive").path().value())
        val branch = library.str("Branch")
        val book = loans.str("Book")
        val title = archive.str("Title")
        val declaring = when (input.reads) {
            Reads.ROOT, Reads.KEYWORD_READ, Reads.KEYWORD_UNREAD -> library
            Reads.OWN -> loans
            Reads.KEYWORD_NESTED -> archive
        }
        val errorField = when (input.reads) {
            Reads.ROOT, Reads.KEYWORD_READ, Reads.KEYWORD_UNREAD -> branch
            Reads.OWN -> book
            Reads.KEYWORD_NESTED -> title
        }
        val condition = when (input.reads) {
            Reads.ROOT -> branch.filled().and(book.filled())
            Reads.OWN -> book.filled()
            Reads.KEYWORD_READ -> branch.filled().and(title.filled())
            Reads.KEYWORD_UNREAD -> branch.filled()
            Reads.KEYWORD_NESTED -> title.filled()
        }
        return ModelWriter.addRule(base, declaring.rule {
            it.name("CheckMessageArgument").code("MESSAGE_ARGUMENT").errorField(errorField.field()).`when`(condition)
                .message(Dsl.en(input.argument?.let { argument -> "Check \$#$argument\$." } ?: "Check.")).build()
        })
    }

    private fun inputs(): List<Input> = buildList {
        for (language in ConditionLanguage.entries) {
            fun row(id: String, argument: String?, code: String? = null, reads: Reads = Reads.ROOT) {
                add(Input("${language.name.lowercase()}-$id", language, reads, argument, code))
            }
            for (reads in Reads.entries) row("plain-${reads.name.lowercase()}", null, reads = reads)
            for (reads in listOf(Reads.ROOT, Reads.OWN)) for (keyword in Keyword.entries) {
                val word = keyword.spelling(language)
                for ((tailId, tail) in listOf("end" to "", "slash" to "/Loans", "minus-name" to "-x",
                    "space-name" to " x", "plus-number" to "+1", "minus-number" to "-1", "lexer" to "%")) {
                    val code = when {
                        tail == "%" -> LEXER
                        keyword == Keyword.RULE -> if (reads == Reads.ROOT) ROOT_RULE else if (tail.isEmpty()) null else UNEXPECTED
                        tail == "/Loans" -> GROUP
                        keyword == Keyword.LINE && tail in listOf("", "+1", "-1") -> RETIRED
                        tail.isEmpty() -> if (keyword == Keyword.USB) RETIRED else null
                        else -> UNEXPECTED
                    }
                    row("keyword-${reads.name.lowercase()}-${keyword.name.lowercase()}-$tailId", word + tail, code, reads)
                }
            }
            for ((signId, sign) in listOf("plus" to "+", "minus" to "-")) {
                row("offset-$signId-missing", "Zeile$sign", EXPECTED)
                row("offset-$signId-name", "Zeile${sign}1x", UNEXPECTED)
                row("offset-$signId-trailing", "Zeile${sign}1 x", RETIRED)
                row("offset-$signId-lexer", "Zeile${sign}1%", LEXER)
                row("offset-$signId-quoted", "'Zeile'${sign}1", GROUP)
            }
            val rootWord = Keyword.ROOT.spelling(language)
            for (reads in listOf(Reads.KEYWORD_READ, Reads.KEYWORD_UNREAD)) {
                for ((form, argument) in listOf("bare" to rootWord, "quoted" to "'$rootWord'",
                    "path" to "$rootWord/Archive", "quoted-path" to "'$rootWord'/Archive",
                    "absolute" to "/Library/$rootWord/Archive")) {
                    row("named-group-${reads.name.lowercase()}-$form", argument,
                        if (reads == Reads.KEYWORD_UNREAD && form != "bare") GROUP else null, reads)
                }
            }
            row("named-group-turning-point", "..$rootWord", reads = Reads.KEYWORD_NESTED)

            // The ten words are the complete localized message LexerTerminals bundle in kernel 30.8.1;
            // the quoting-exempt set is GrammarOnly_ErrorTextParameterParser.nameWithoutDot. Kernel 31.1.1 dropped
            // `Vordruckname` from both (KERNEL-FINDINGS KF657); as a child segment it answers as any name does.
            val reserved = when (language) {
                ConditionLanguage.EN -> listOf("RuleGroup", "For", "BaseYear")
                ConditionLanguage.DE -> listOf("RegelKontext", "Fuer", "Referenzjahr")
            }
            val exempt = listOf(rootWord, "Zeile", "Usb", "index", "Vordruckname", "Vordruckzeile",
                if (language == ConditionLanguage.EN) "value" else "Wert")
            for (word in reserved + exempt) {
                row("word-$word-bare", "Loans/$word", if (word in reserved) UNEXPECTED else GROUP)
                row("word-$word-quoted", "Loans/'$word'", GROUP)
            }
            for (word in reserved.drop(1)) row("word-$word-standalone", word, UNEXPECTED)
            row("word-rule-absolute", "/Library/${reserved.first()}", UNEXPECTED)
            row("word-rule-turning-point", "..${reserved.first()}", UNEXPECTED, Reads.OWN)

            for (name in listOf("Loans", "Returns", "Missing")) {
                val afterPath = if (name == "Loans") UNEXPECTED else GROUP
                row("fault-$name-plain-control", name, if (name == "Loans") null else GROUP)
                row("fault-$name-minus-control", "$name-x", afterPath)
                row("fault-$name-space-control", "$name x", afterPath)
                for ((id, tail, code) in listOf(Triple("immediate", "%", LEXER), Triple("separator", "/%", LEXER),
                    Triple("double-separator", "//%", UNEXPECTED), Triple("trailing-percent", " x%", afterPath),
                    Triple("trailing-bracket", " x[", afterPath), Triple("trailing-quote", " x'Bad", afterPath))) {
                    row("fault-$name-$id", name + tail, code)
                }
            }
            row("fault-double-separator-bracket", "Loans//[", UNEXPECTED)
            row("fault-double-separator-quote", "Loans//'Bad", UNEXPECTED)
            row("numeric-bare", "123", GROUP)
            row("numeric-child", "Loans/123", GROUP)
            for ((id, argument, code) in listOf(Triple("label-incomplete", "..Wrong/", "INVALID_ENTITY"),
                Triple("label-separator", "..Wrong//%", "INVALID_ENTITY"), Triple("correct-label-lexer", "..Library/%", LEXER),
                Triple("wrong-label-lexer", "..Wrong/%", "INVALID_ENTITY"), Triple("above-root-separator", "../../Loans//%", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"))) {
                row("parent-$id", argument, code, Reads.OWN)
            }
        }
    }

    internal fun boundaryModel(language: ConditionLanguage, depth: Int, argument: String): String =
        model(Input("boundary", language, when (depth) {
            1 -> Reads.ROOT
            2 -> Reads.OWN
            3 -> Reads.KEYWORD_NESTED
            else -> error("Unmeasured declaration depth: $depth")
        }, argument, null))

    fun rows(): List<Row> = inputs().map { Row(it.id, model(it), listOfNotNull(it.code)) }

    @Test
    fun groupArgumentsFollowGrammarDecisionsAndReachedFaults() {
        val rows = rows()
        assertEquals(284, rows.size, "frozen witness families")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        val oracle = KernelConsistencyOracle()
        val observed = rows.map { row -> row.id to oracle.checkConsistency(row.model, ReferenceResolvers.selfContained())
            .filter { it.severity() == Severity.ERROR }.map {
                Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1) ?: error(it.message())
            } }
        assertEquals(rows.map { it.id to it.classes }, observed, "kernel predictions including plain-message controls")
        val stored = HarvestedModels.expand(ObjectMapper().readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-group-events.json")).readText()))
        assertEquals(rows, stored.map { Row(it["id"].asText(), it["model"].asText(), it["classes"].map { code -> code.asText() }) },
            "common hosts replay the exact typed model bytes in order")
        val portable = rows.map { it.id to rejectingCodes(it.model) }
        assertEquals(observed, portable, "portable event order")
    }

    private companion object {
        const val LEXER = "MVK_LEXER_STANDARD_ERROR"
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"
        const val EXPECTED = "MVK_EXPECTED_TOKEN_NOT_FOUND"
        const val GROUP = "INVALID_GROUP"
        const val RETIRED = "PARAM_INVALID_IN_NEW_WORLD"
        const val ROOT_RULE = "RULEGROUP_IMMEDIATELY_BELOW_ROOT"
    }
}
