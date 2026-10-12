package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.rule
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-01-06 on our own carrier: a rule message's parameter is refused at its parser's first event.
 *
 * The 99 rows are a12-kernel-lean's decoder grid (`message-decoder-matrix-2026-10-01`, ids kept so each row
 * can be matched to theirs) re-expressed on a Library whose repeatable `Reviews` holds a Number `Stars` and an
 * Enumeration `Verdict` with one category `Tone`. Each row's classes are the ones their grid observed; the
 * kernel run here is what confirms or refutes them. The rule sits in `Reviews` and reads both fields, so no
 * condition reads a field under a key — which is why every keyed name-position row meets the index gate.
 */
class MessageParameterDecoderAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private val base = EditSession.read(DocumentModelDraft.named("library-message-decoder").locale("en_US")
        .rootGroup("Library") { library -> library.stringField("Branch") { }
            .group("Reviews") { reviews -> reviews.repeatable(10)
                .numberField("Stars") { it.fractionDigits(0) }
                .enumField("Verdict") { it.value("A").value("B").category("Tone", "Hi", "Lo") } }
        }.build().toJson()).setFieldRefByShortNameAllowed(true).toJson()

    private fun model(parameter: String): String = rule(base, REVIEWS, "Stars", Dsl.en("Check $parameter.")) { edit ->
        val reviews = edit.group(REVIEWS)
        reviews.num("Stars").filled().and(reviews.enumOf("Verdict").filled())
    }

    fun rows(): List<Row> = buildList {
        fun row(id: String, parameter: String, vararg classes: String) = add(Row(id, model(parameter), classes.toList()))
        // matrix.spec
        row("W01", "\$Verdict->Tone\$")
        row("W02", "\$Verdict -> Tone\$")
        row("W03", "\$Verdict-> Tone\$")
        row("W04", "\$Verdict->Tone \$")
        row("W05", "\$Verdict ->Tone\$")
        row("W06", "\$ Verdict->Tone\$")
        row("W07", "\$Verdict-> \$", "NO_CATEGORY")
        row("W08", "\$Verdict->\$", "NO_CATEGORY")
        row("W09", "\$Stars-> \$", "NO_CATEGORY")
        row("W10", "\$Verdict->Tone x\$", "MVK_UNEXPECTED_TOKEN")
        row("W11", "\$Verdict->Tone.value\$", "MVK_UNEXPECTED_TOKEN")
        row("W12", "\$Verdict->Tone/Foo\$", "MVK_UNEXPECTED_TOKEN")
        row("W13", "\$Verdict->Nope x\$", "INVALID_CATEGORY")
        row("W14", "\$Stars->Tone x\$", "FIELD_NO_ENUM")
        row("W15", "\$ Stars\$")
        row("W16", "\$Stars \$")
        row("W17", "\$Stars .value\$")
        row("W18", "\$Stars. value\$")
        row("W20", "\$Stars For  \"k\"\$", "MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        row("W21", "\$Stars For\"k\"\$", "MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        row("W22", "\$Stars  For \"k\"\$", "MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        row("G01", "\$#Reviews For \"k\"\$", "INVALID_GROUP")
        row("G02", "\$#Reviews For Stars\$", "INVALID_GROUP")
        row("G03", "\$#Reviews For Stars For \"k\"\$", "INVALID_GROUP")
        row("G04", "\$#Reviews For Nope\$", "INVALID_GROUP")
        row("G05", "\$#Reviews\$", "INVALID_GROUP")
        row("L01", "\$Stars For \"a For b\"\$", "MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        row("L02", "\$Stars For Verdict For \"a For b\"\$", "ILLEGAL_SEMANTIC_INDEX")
        row("R01", "\$RuleGroup For Stars For \"k\"\$", "ILLEGAL_SEMANTIC_INDEX")
        row("R02", "\$Stars For RuleGroup\$", "MVK_INTERNAL_ERROR")
        row("P01", "\$Nope\$ and \$Stars\$", "INVALID_ENTITY")
        row("P02", "\$Nope\$ and \$Verdicté\$", "INVALID_ENTITY", "MVK_LEXER_STANDARD_ERROR")
        row("P03", "\$Nope\$ and \$Nope2\$", "INVALID_ENTITY", "INVALID_ENTITY")
        row("S01", "\$Nope For Stars.x\$", "INVALID_ENTITY")
        row("S02", "\$Nope For \"k\" x\$", "INVALID_ENTITY")
        row("C01", "\$Stars\$")
        // group-key.spec
        row("H00", "\$#/Library/Reviews\$")
        row("H01", "\$#/Library/Reviews For \"k\"\$", "INVALID_GROUP")
        row("H02", "\$#/Library/Reviews For Stars\$", "INVALID_GROUP")
        row("H03", "\$#/Library/Reviews For Stars For \"k\"\$", "ILLEGAL_SEMANTIC_INDEX")
        row("H04", "\$#/Library/Reviews For Nope\$", "INVALID_GROUP")
        row("H05", "\$#/Library/Reviews For Verdict\$", "INVALID_GROUP")
        row("H06", "\$#/Library For \"k\"\$", "INVALID_GROUP")
        // prediction.spec
        row("N01", "\$Nope x\$", "MVK_UNEXPECTED_TOKEN")
        row("N02", "\$Nope/\$", "MVK_INCOMPLETE_INPUT")
        row("N03", "\$Nope//Stars\$", "MVK_UNEXPECTED_TOKEN")
        row("N04", "\$Stars x\$", "MVK_UNEXPECTED_TOKEN")
        row("N05", "\$Stars/\$", "MVK_INCOMPLETE_INPUT")
        row("N06", "\$Nope.Vordruckzeile x\$", "MVK_UNEXPECTED_TOKEN")
        row("N07", "\$Nope.value x\$", "MVK_UNEXPECTED_TOKEN")
        row("N08", "\$Nope For\$", "INVALID_ENTITY")
        row("N09", "\$Stars For\$", "MVK_INCOMPLETE_INPUT")
        row("N10", "\$Verdict->Nope For\$", "MVK_INCOMPLETE_INPUT")
        row("N12", "\$BaseYear x\$", "NO_BASE_YEAR")
        row("N13", "\$BaseYear+\$", "MVK_EXPECTED_TOKEN_NOT_FOUND")
        row("N15", "\$RuleGroup\$", "INVALID_PARAMETER")
        row("N16", "\$RuleGroup->Tone\$", "INVALID_PARAMETER")
        row("N17", "\$RuleGroup x\$", "MVK_UNEXPECTED_TOKEN")
        // keyword.spec
        row("K01", "\$#RootGroup For \"k\"\$", "INVALID_GROUP")
        row("K02", "\$#RuleGroup For \"k\"\$", "INVALID_GROUP")
        row("K03", "\$#Zeile For \"k\"\$", "INVALID_GROUP")
        row("K04", "\$#RuleGroup For\$", "MVK_INCOMPLETE_INPUT")
        row("K05", "\$#/Library/Reviews For \"k\" x\$", "INVALID_GROUP")
        row("K06", "\$#/Library/Reviews For Stars/\$", "MVK_INCOMPLETE_INPUT")
        row("K07", "\$#/Library/Reviews For Nope/x\$", "INVALID_GROUP")
        row("U01", "\$..\$", "INVALID_FIELD")
        row("U02", "\$..Library\$", "INVALID_FIELD")
        row("U03", "\$..Nope\$", "INVALID_ENTITY")
        row("U04", "\$../..\$", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("X01", "\$Nope For é\$", "INVALID_ENTITY")
        row("X02", "\$Verdict->Nope é\$", "MVK_LEXER_STANDARD_ERROR")
        row("X03", "\$Nope é\$", "MVK_LEXER_STANDARD_ERROR")
        // review.spec
        row("F1a", "\$Stars For Nope/\$", "MVK_INCOMPLETE_INPUT")
        row("F1b", "\$Stars For Nope//x\$", "MVK_UNEXPECTED_TOKEN")
        row("F1c", "\$Stars For Nope/%\$", "MVK_LEXER_STANDARD_ERROR")
        row("F1d", "\$Stars For /Nope/\$", "MVK_INCOMPLETE_INPUT")
        row("F1e", "\$Stars For Nope\$", "INVALID_ENTITY")
        row("F1f", "\$Stars For ../Nope/\$", "INVALID_ENTITY")
        row("F2a", "\$#../Nope/\$", "INVALID_GROUP")
        row("F2b", "\$#../Nope//x\$", "INVALID_GROUP")
        row("F2c", "\$#../Nope/%\$", "INVALID_GROUP")
        row("F2d", "\$#../Nope\$", "INVALID_GROUP")
        row("F3a", "\$..Library.x\$", "INVALID_FIELD")
        row("F3b", "\$..Library.x/Branch\$", "INVALID_FIELD")
        row("F3c", "\$Stars For ..Library.x\$", "MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        row("F3d", "\$#..Library.x\$")
        row("F4a", "\$Verdict->Nope.%\$", "INVALID_CATEGORY")
        row("F4b", "\$Verdict->Tone.%\$", "MVK_UNEXPECTED_TOKEN")
        row("F4c", "\$Verdict->Tone. %\$", "MVK_UNEXPECTED_TOKEN")
        row("F5a", "\$#/Library/Reviews For Nope For \"k\"\$", "INVALID_GROUP")
        row("F5b", "\$#/Library/Reviews For Stars For Nope\$", "INVALID_GROUP")
        row("F5c", "\$#/Library/Reviews For /Nope\$", "INVALID_ROOT_GROUP")
        row("F5d", "\$#/Library/Reviews For /Nope For \"k\"\$", "INVALID_ROOT_GROUP")
        row("F5e", "\$#/Library/Reviews For ../../..\$", "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS")
        row("F5f", "\$#/Library/Reviews For ..Wrong\$", "INVALID_ENTITY")
        row("F5g", "\$#/Library/Reviews For RuleGroup\$", "MVK_INTERNAL_ERROR")
        row("F5h", "\$#/Library/Reviews For Nope\$", "INVALID_GROUP")
        // name-review.spec
        row("N30", "\$Nope->Tone x\$", "INVALID_ENTITY")
        row("N31", "\$Nope->Tone\$", "INVALID_ENTITY")
    }

    @Test
    fun eachParameterIsRefusedAtItsParsersFirstEvent() {
        val rows = rows()
        MessageParameterProbes.replay("decoder", rows.map { Triple(it.id, it.model, it.classes) })
        assertEquals(99, rows.size, "a12-kernel-lean's decoder grid, every row")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable classes")
    }

    @Test
    fun groupParametersShareTheKeyAndParentEventStream() {
        val rows = rows().filter { "\$#" in it.model }
        assertEquals(32, rows.size)
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel group events")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) },
            "portable group events")
    }

    @Test
    fun keyedMembershipAndPresenceStopBeforeLaterTokens() {
        val base = DocumentModelDraft.named("library-keyed-group-boundary").locale("en_US")
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .group("Reviews") { reviews -> reviews.repeatable(10).indexField("Code")
                    .stringField("Code") { }.numberField("Stars") { } } }
            .build().toJson()
        val rows = listOf("chosen" to listOf("INVALID_USE_OF_SEMANTIC_INDEX"), "unmatched" to listOf("INVALID_GROUP")).flatMap { (key, codes) ->
            listOf("", " x").map { tail ->
                val model = rule(base, "/Library", "Branch", Dsl.en("Check \$#/Library/Reviews For \"$key\"$tail\$.")) { edit ->
                    val library = edit.group("/Library")
                    library.str("Branch").filled().and(library.num("Reviews/Stars").forIndex("chosen").gt(0))
                }
                Row("$key$tail", model, codes)
            }
        }
        MessageParameterProbes.replay("keyed-decline", rows.map { Triple(it.id, it.model, it.classes) })
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel keyed completion boundary")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) },
            "membership and presence precede later token refusals")
    }

    @Test
    fun keyNamesCompleteBeforeTrailingTokens() {
        val rows = listOf(false, true).flatMap { group ->
            listOf("Stars", "Nope").flatMap { key ->
                listOf("", " x").map { tail ->
                    val addressed = if (group) "#/Library/Reviews" else "Stars"
                    val code = if (group) "INVALID_GROUP" else if (key == "Nope") "INVALID_ENTITY"
                        else "MVK_INDEX_FOR_ERROR_TEXT_INVALID"
                    Row("$group-$key-$tail", model("\$$addressed For $key$tail\$"), listOf(code))
                }
            }
        }
        MessageParameterProbes.replay("key-follower", rows.map { Triple(it.id, it.model, it.classes) })
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel key follower events")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable key follower events")
    }

    @Test
    fun familiesRetainParameterOrderAndMultiplicity() {
        val rows = listOf(
            Row("base-field", model("\$BaseYear\$ \$Nope\$"), listOf("NO_BASE_YEAR", "INVALID_ENTITY")),
            Row("field-base", model("\$Nope\$ \$BaseYear\$"), listOf("INVALID_ENTITY", "NO_BASE_YEAR")),
            Row("base-twice", model("\$BaseYear\$ \$BaseYear\$"), listOf("NO_BASE_YEAR")),
            Row("base-offset", model("\$BaseYear\$ \$BaseYear+1\$"), listOf("NO_BASE_YEAR")),
        )
        MessageParameterProbes.replay("family-order", rows.map { Triple(it.id, it.model, it.classes) })
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel cross-family order")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) }, "portable cross-family order")
    }

    private companion object {
        const val REVIEWS = "/Library/Reviews"
    }

    @Test
    fun messageLineBreakRefusalPrecedesLiteralPairing() {
        val base = DocumentModelDraft.named("library-message-literal-keys").locale("en_US")
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .group("Reviews") { reviews -> reviews.repeatable(10).indexField("Code")
                    .stringField("Code") { }.numberField("Stars") { it.fractionDigits(0) } } }
            .build().toJson()
        val keys = listOf("lf" to "A\nB", "crlf" to "A\r\nB", "repeated-cr" to "A\r\r\nB")
        val rows = keys.flatMap { (conditionId, conditionKey) ->
            keys.map { (messageId, messageKey) ->
                val messageLiteral = "\"${messageKey.replace("\"", "\"\"")}\""
                val model = rule(base, "/Library", "Branch", Dsl.en("Check \$Reviews/Stars For $messageLiteral\$.")) { edit ->
                    val library = edit.group("/Library")
                    library.str("Branch").filled().and(library.num("Reviews/Stars").forIndex(conditionKey).gt(0))
                }
                "$conditionId-$messageId" to model
            }
        }
        assertEquals(9, rows.size)
        assertEquals(rows.size, rows.map { it.first }.toSet().size)
        for ((id, model) in rows) {
            val errors = io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle()
                .checkConsistency(model, io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained())
                .filter { it.severity() == io.github.mbackschat.a12.dm.core.Severity.ERROR }
            assertEquals(listOf("Rule with path '/Library/CheckMessageParameter': Only values without line breaks are allowed.",
                "Rule with path '/Library/CheckMessageParameter': The rule '/Library/CheckMessageParameter' specifies an incorrect error text for the language English. The error text contains a line break which is not valid. [INVALID_LINEBREAK]"),
                errors.map { it.message() }, id)
        }
    }

    @Test
    fun aLegalQuoteBearingEnumTokenIsDecodedBeforeBothListGates() {
        val host = DocumentModelDraft.named("library-quoted-enum-token").locale("en_US")
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .enumField("Verdict") { it.value("A\"B").value("OTHER") }
                .numberField("Stars") { it.fractionDigits(0) } }
            .build().toJson()
        assertEquals(emptySet<String>(), kernelModelClasses(host), "declaration admission precedes decoder claims")
        val edit = EditSession.read(host)
        val library = edit.group("/Library")
        val branch = library.str("Branch")
        val verdict = library.field("Verdict")
        val stars = library.field("Stars")
        val conditions = listOf(
            "value-list" to (Dsl.atLeastOneFieldValueIncludedInValueList(listOf("A\"B"), verdict) to emptyList<String>()),
            "constant-kind" to (Dsl.numberOfValueInFields("A\"B", verdict, stars).gt(0) to listOf("MVK_NO_STRING_OR_ENUM")),
            "predefined-name-quote" to (Dsl.validType(branch.field(), "\"") to emptyList<String>()),
        )
        val validator = io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator()
        val rows = conditions.map { (id, probe) ->
            val draft = library.rule { it.name("LiteralProbe").errorField(branch)
                .`when`(branch.filled().and(probe.first)).code("LITERAL_PROBE")
                .message(Dsl.en("Check the branch verdict.")).build() }.draft()
            Row(id, validator.inject(host, draft), probe.second)
        } + run {
            val draft = library.rule { it.name("LiteralProbe").errorField(branch)
                .`when`(branch.filled().and(Dsl.validType(branch.field(), "Probe"))).code("LITERAL_PROBE")
                .message(Dsl.en("Check the branch verdict.")).build() }.draft()
            // CustomTypeValidity refuses an empty name before rendering; this kernel-refusal control bypasses
            // that measured builder gate (ValidityConditionOperandLawsTest), keeping the rest typed.
            val raw = draft.withErrorCondition(draft.errorCondition().replace("\"Probe\"", "\"\""))
            Row("predefined-name-empty", validator.inject(host, raw), listOf("MVK_NO_EMPTY"))
        }
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel quoted token controls")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to MessageParameterProbes.portable(it.model) },
            "portable quoted token controls")
    }
}
