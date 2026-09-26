package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A12's codeless refusal of a character outside the Basic Multilingual Plane, on both targets.
 *
 * Every row mirrors one of `rulekit.validate.laws.ModelTextRepertoireLawsTest`, which holds A12's findings by
 * their text ([KERNEL-FINDINGS KF489]); this checker names that refusal `RK_UNSUPPORTED_CHARACTER`. Where A12
 * draws a further class this checker does not, the row says which gap owns it.
 */
class ModelTextRepertoireTest {

    private fun q(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun rule(
        condition: String = READS,
        code: String = "PROBE",
        message: String = "x",
        name: String = "ProbeRule",
        extraMessage: String = "",
        target: String = "../Reference",
        extraMember: String = "",
    ) = """{"type":"Rule","id":"r0","name":${q(name)},"Rule":{"errorCondition":${q(condition)},$extraMember
        "errorEntityRelPath":${q(target)},"errorCode":${q(code)},
        "errorMessage":[{"locale":"en_US","text":${q(message)}}$extraMessage]}}"""

    private fun calc(common: String? = null, precondition: String? = null, operation: String = "[Note]",
            message: String = "x", name: String = "ProbeCalc") =
        """{"type":"Computation","id":"c0","name":${q(name)},"Computation":{"computedFieldRelPath":"../Summary",
        ${common?.let { "\"commonPrecondition\":${q(it)}," } ?: ""}
        "computationAlternatives":[{${precondition?.let { "\"precondition\":${q(it)}," } ?: ""}"operation":${q(operation)}}],
        "errorMessage":[{"locale":"en_US","text":${q(message)}}]}}"""

    private fun status(value: String = "OPEN", label: String = "Open", german: String = "Offen") =
        """{"type":"Field","id":"f3","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
        "EnumerationType":{"values":[{"value":${q(value)},"label":[{"locale":"en_US","text":${q(label)}},
        {"locale":"de_DE","text":${q(german)}}]}]}}}}"""

    private fun model(
        vararg elements: String,
        reference: String = """{"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}}""",
        groupLabel: String = "Claim",
    ) = """
        {"header":{"id":"claims-repertoire","locales":[{"code":"en_US"}],"labels":[{"locale":"en_US","text":"Claims"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Claim","Group":{"label":[{"locale":"en_US","text":${q(groupLabel)}}],
           "elements":[
            $reference,
            {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Summary","Field":{"fieldType":{"type":"StringType"}}}
            ${elements.joinToString("") { ",$it" }}
          ]}}]}}}
    """.trimIndent()

    /** Refuses a source the checker could not read, since an unread model reports nothing and every row passes. */
    private fun reported(json: String): List<String> {
        val report = checkRead(ModelSource(json))
        assertTrue(report.diagnostics.isEmpty(), "the fixture was not read: ${report.diagnostics}")
        return report.findings.filter { it.justifiesRejection }.map { "${it.code} at ${it.at}" }
    }

    private fun annotated(name: String, value: String) =
        """{"type":"Field","id":"f0","name":"Reference","annotations":[{"name":${q(name)},"value":${q(value)}}],
        "Field":{"fieldType":{"type":"StringType"}}}"""

    private fun nested(name: String, annotationName: String = "origin", annotationValue: String = "kept",
            vararg inside: String) =
        """{"type":"Group","id":"g1","name":${q(name)},"annotations":[{"name":${q(annotationName)},
        "value":${q(annotationValue)}}],"Group":{"elements":[
          {"type":"Field","id":"f9","name":"Detail","Field":{"fieldType":{"type":"StringType"}}}
          ${inside.joinToString("") { ",$it" }}]}}"""

    @Test
    fun eachMeasuredCarrierDrawsTheRefusalOncePerElement() {
        val actual = linkedMapOf(
            "message" to reported(model(rule(message = "see $GRIN"))),
            "condition literal" to reported(model(rule(condition = "$READS And [Note] == \"a$GRIN\""))),
            "condition comment" to reported(model(rule(condition = "$READS ;; $GRIN"))),
            "error code" to reported(model(rule(code = "PROBE$GRIN"))),
            "rule name" to reported(model(rule(name = "Probe$GRIN"))),
            "computation literal" to reported(model(calc(operation = "\"a$GRIN\""))),
            "computation comment" to reported(model(calc(operation = "[Note] ;; $GRIN"))),
            "computation common precondition" to reported(model(calc(common = "FieldFilled(Note) ;; $GRIN"))),
            "computation alternative precondition" to reported(model(calc(precondition = "FieldFilled(Note) ;; $GRIN"))),
            "computation message" to reported(model(calc(message = "see $GRIN"))),
            "computation name" to reported(model(calc(name = "Calc$GRIN"))),
            "enum value" to reported(model(status(value = "OPEN$GRIN"))),
            "enum label" to reported(model(status(label = "Open $GRIN"))),
            "annotation value" to reported(model(reference = annotated("origin", "kept $GRIN"))),
            "annotation name" to reported(model(reference = annotated("origin$GRIN", "kept"))),
            "two texts of one rule" to reported(model(rule(condition = "$READS ;; $GRIN", message = "see $GRIN"))),
            "two texts of one field" to reported(model(status(value = "OPEN$GRIN", label = "Open $GRIN"))),
            "group annotation" to reported(model(nested("Part", annotationValue = "kept $GRIN"))),
        )
        val expected = linkedMapOf(
            "message" to listOf(RULE),
            "condition literal" to listOf(RULE),
            "condition comment" to listOf(RULE),
            "error code" to listOf(RULE),
            "rule name" to listOf("$CODE at /Claim/Probe$GRIN"),
            "computation literal" to listOf(CALC),
            "computation comment" to listOf(CALC),
            "computation common precondition" to listOf(CALC),
            "computation alternative precondition" to listOf(CALC),
            "computation message" to listOf(CALC),
            "computation name" to listOf("$CODE at /Claim/Calc$GRIN"),
            "enum value" to listOf(STATUS),
            "enum label" to listOf(STATUS),
            "annotation value" to listOf("$CODE at /Claim/Reference"),
            "annotation name" to listOf("$CODE at /Claim/Reference"),
            "two texts of one rule" to listOf(RULE),
            "two texts of one field" to listOf(STATUS),
            "group annotation" to listOf("$CODE at /Claim/Part"),
        )
        assertEquals(expected, actual)
    }

    /** The texts A12 does not refuse it in, each the over-rejection a reading of "every text" would make. */
    @Test
    fun theLabelsAndTheUndeclaredLocalesAreNotCarriers() {
        assertEquals(
            listOf(emptyList<String>(), emptyList(), emptyList(), emptyList()),
            listOf(
                reported(model(groupLabel = "Claim $GRIN")),
                reported(model(reference = """{"type":"Field","id":"f0","name":"Reference","Field":{
                    "label":[{"locale":"en_US","text":${q("Reference $GRIN")}}],"fieldType":{"type":"StringType"}}}""")),
                reported(model(rule(extraMessage = """,{"locale":"de_DE","text":${q("see $GRIN")}}"""))),
                reported(model(status(german = "Offen $GRIN"))),
            ),
        )
    }

    /**
     * The PATH is a carrier: a group name is reported on the group and on each element beneath it. A12 also
     * names the path class there, which the name family leaves to it for any non-ASCII character.
     */
    @Test
    fun aDefectiveGroupNameIsReportedOnEveryElementBeneathIt() {
        val inner = """{"type":"Rule","id":"r1","name":"InnerRule","Rule":{"errorCondition":"FieldFilled(Detail)",
            "errorEntityRelPath":"../Detail","errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        assertEquals(
            setOf("$CODE at /Claim/Part$GRIN", "$CODE at /Claim/Part$GRIN/Detail", "$CODE at /Claim/Part$GRIN/InnerRule"),
            reported(model(nested("Part$GRIN", "origin", "kept", inner))).toSet(),
        )
    }

    /**
     * It stands beside the classes A12 lists with it. A lone surrogate is refused like a pair, and beside the
     * message's own `INVALID_CHARACTERS`, since a lone surrogate is `\p{C}` to the message gate.
     */
    @Test
    fun itIsReportedBesideTheConditionClassAndForALoneSurrogate() {
        assertEquals(
            listOf(
                setOf(RULE, "MVK_LEXER_STANDARD_ERROR at /Claim/ProbeRule"),
                setOf(RULE, "MVK_UNEXPECTED_TOKEN at /Claim/ProbeRule"),
                setOf(RULE, "INVALID_CHARACTERS at /Claim/ProbeRule"),
                setOf(RULE, "INVALID_CHARACTERS at /Claim/ProbeRule"),
            ),
            listOf(
                reported(model(rule(condition = "$READS And $GRIN FieldFilled(Note)"))).toSet(),
                reported(model(rule(condition = "$READS And And FieldFilled(Note)", message = "see $GRIN"))).toSet(),
                // Built at run time: Kotlin/JS compiles a lone-surrogate LITERAL to `?`, so the JS arm would
                // judge a plain text (CheckerFixtureReadGuardTest.noMultiplatformSourceSpellsALoneSurrogate).
                reported(model(rule(message = "see " + Char(0xD83D)))).toSet(),
                reported(model(rule(message = "see " + Char(0xDE00)))).toSet(),
            ),
        )
    }

    /**
     * An error target naming no field stops A12's rule parser and leaves this refusal standing beside the
     * target's classes; an `ONLY` variant filter takes it away with the rest of the rule, as it does every
     * finding about the rule (`…ModelTextRepertoireLawsTest.anErrorTargetDoesNotStopItAndAVariantFilterDoes`).
     */
    @Test
    fun anErrorTargetDoesNotStopItAndAVariantFilterDoes() {
        val only = """"variantFilter":{"filterType":"ONLY","variants":["premium"]},"""
        assertEquals(
            listOf(
                setOf(RULE, "MVK_ERROR_FIELD_MISSING at /Claim/ProbeRule", "MVK_MISSING_ERROR_FIELD at /Claim/ProbeRule"),
                setOf(RULE, "MVK_ERROR_FIELD_NOT_REFERENCED at /Claim/ProbeRule"),
                emptySet(),
            ),
            listOf(
                reported(model(rule(message = "see $GRIN", target = "../NoSuchField"))).toSet(),
                reported(model(rule(message = "see $GRIN", target = "../Summary"))).toSet(),
                reported(model(rule(message = "see $GRIN", extraMember = only))).toSet(),
            ),
        )
    }

    /**
     * A field type's own message is a carrier on both types that declare one, and like every other message only
     * for a locale the model declares (`…ModelTextRepertoireLawsTest`, the three field-message rows).
     */
    @Test
    fun aFieldTypesOwnMessageIsACarrier() {
        fun patterned(messages: String) = """{"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{
            "type":"StringType","StringType":{"pattern":"[A-Z]+","errorMessage":[$messages]}}}}"""
        val status = """{"type":"Field","id":"f3","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
            "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"}],
            "errorMessage":[{"locale":"en_US","text":${q("see $GRIN")}}]}}}}"""
        assertEquals(
            listOf(listOf("$CODE at /Claim/Reference"), listOf(STATUS), emptyList()),
            listOf(
                reported(model(reference = patterned("""{"locale":"en_US","text":${q("see $GRIN")}}"""))),
                reported(model(status)),
                reported(model(reference = patterned("""{"locale":"en_US","text":"capitals only"},
                    {"locale":"de_DE","text":${q("see $GRIN")}}"""))),
            ),
        )
    }

    private companion object {
        const val GRIN = "\uD83D\uDE00"
        const val READS = "FieldFilled(Reference) And FieldFilled(Note)"
        const val CODE = "RK_UNSUPPORTED_CHARACTER"
        const val RULE = "$CODE at /Claim/ProbeRule"
        const val CALC = "$CODE at /Claim/ProbeCalc"
        const val STATUS = "$CODE at /Claim/Status"
    }
}
