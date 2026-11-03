package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of A12's basic sentences and the build failures beside them ([KERNEL-FINDINGS KF626]), so
 * the JS checker is held to them as well as the JVM one. A12's answer to every row was measured by
 * `adapter.laws.CheckerBasicSentenceAgreementTest`; this class only replays it. The non-scalar and absent names
 * follow [KERNEL-FINDINGS KF644] and, on kernel 31.1.1, KF652.
 */
class BasicSentenceTest {

    @Test
    fun eachDefectDrawsWhatA12Draws() {
        val rows: Map<String, Set<String>> = linkedMapOf(
            "the control" to model(),
            "a repeated value" to model(values = REPEATED_VALUE),
            "a repeated category" to model(categories = REPEATED_CATEGORY),
            "a repeated hint locale" to model(hints = REPEATED_HINT),
            "an absent type reference" to model(codeType = ABSENT_REFERENCE),
            "a field with no name" to model(codeName = ""),
            "an empty field id" to model(codeId = ""),
            "a field id of one space" to model(codeId = " "),
            "an empty computed field" to model(computed = ""),
            "a type definition with an empty id" to model(typeDefinition = typeDefinition(id = "")),
            "a type definition with an id of one space" to model(typeDefinition = typeDefinition(id = " ")),
            "a type definition with an empty name" to model(typeDefinition = typeDefinition(name = "\"\"")),
            "a type definition with no name" to model(typeDefinition = typeDefinition(name = null)),
        ).mapValues { (_, source) -> codes(source) }
        val expected: Map<String, Set<String>> = linkedMapOf(
            "the control" to emptySet(),
            "a repeated value" to setOf("MVK_CATEGORY_VALUE_DUPLICATE", "MVK_INTERNAL_VALUES_DUPLICATE", RK_ENUM_VALUE_DUPLICATE),
            "a repeated category" to setOf(CK_MODEL_NOT_BUILDABLE, RK_ENUM_CATEGORY_DUPLICATE),
            "a repeated hint locale" to setOf(CK_MODEL_NOT_BUILDABLE),
            "an absent type reference" to setOf(RK_TYPE_DEFINITION_NOT_FOUND, RK_TYPE_REFERENCE_EMPTY),
            // Kernel 31.1.1 reads an absent name as no name, which its converter states (KF652).
            "a field with no name" to setOf(RK_ELEMENT_NAME_MISSING, RK_NAME_EMPTY),
            "an empty field id" to setOf(RK_ID_EMPTY),
            "a field id of one space" to emptySet(),
            "an empty computed field" to setOf(RK_COMPUTED_FIELD_NOT_SPECIFIED, RK_COMPUTED_FIELD_EMPTY),
            "a type definition with an empty id" to setOf(RK_ID_EMPTY),
            "a type definition with an id of one space" to emptySet(),
            "a type definition with an empty name" to setOf(RK_NAME_EMPTY),
            "a type definition with no name" to setOf(RK_NAME_EMPTY),
        )
        assertEquals(expected, rows, "the rejecting codes of each row")
    }

    /**
     * What A12's reader refuses before any check runs, so nothing else is judged: a declaration's `name` written as
     * an object — which the checker also reports unread, since it cannot place it (KF652: kernel 31.1.1's reader binds
     * no such name) — and a type definition's `name` written as `null` or an array.
     */
    @Test
    fun theReaderRefusesANonTextName() {
        val rows = linkedMapOf(
            "a field name that is an object" to model(codeName = """"name":{},"""),
            "a type definition whose name is null" to model(typeDefinition = typeDefinition(name = "null")),
            "a type definition whose name is an array" to model(typeDefinition = typeDefinition(name = "[]")),
        ).mapValues { (_, source) ->
            refusedByTheReader(source).let { report ->
                report.findings.map { it.code }.toSet() to report.diagnostics.map { it.code }
            }
        }
        val unplaced = listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED)
        assertEquals(linkedMapOf(
            "a field name that is an object" to (setOf(CK_MODEL_NOT_BUILDABLE) to unplaced),
            "a type definition whose name is null" to (setOf(CK_MODEL_NOT_BUILDABLE) to emptyList()),
            "a type definition whose name is an array" to (setOf(CK_MODEL_NOT_BUILDABLE) to emptyList()),
        ), rows)
    }

    /** [check] itself, because a source A12's reader refuses completes no check family. */
    private fun refusedByTheReader(source: String) = check(ModelSource(source))

    private fun codes(source: String) =
        checkRead(ModelSource(source)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()

    private fun model(
        values: String = """{"value":"OPEN"},{"value":"CLOSED"}""",
        categories: String = """{"name":"Band","values":["A","B"]}""",
        hints: String = """{"locale":"en_US","values":["AB"]}""",
        codeType: String = """{"type":"StringType","StringType":{"hintList":[$hints]}}""",
        codeName: String = """"name":"Code",""",
        codeId: String = "/Policy/Code",
        condition: String = "FieldFilled(Ref)",
        target: String = "../Ref",
        operation: String = "1",
        computed: String = "../Total",
        typeDefinition: String = "",
    ) = """
        {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},${typeDefinition}"modelRoot":{"rootGroups":[
          {"type":"Group","id":"/Policy","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"/Policy/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType","StringType":{}}}},
            {"type":"Field","id":"$codeId",$codeName"Field":{"fieldType":$codeType}},
            {"type":"Field","id":"/Policy/Status","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[$values],"categories":[$categories]}}}},
            {"type":"Field","id":"/Policy/Total","name":"Total","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":0}}}},
            {"type":"Rule","id":"/Policy/RefCheck","name":"RefCheck","Rule":{"errorCondition":"$condition",
              "errorEntityRelPath":"$target","errorCode":"REF","errorMessage":[{"locale":"en_US","text":"x"}]}},
            {"type":"Computation","id":"/Policy/Calc","name":"Calc","Computation":{"computedFieldRelPath":"$computed",
              "computationAlternatives":[{"operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    /** [name] is the member's JSON value as written, or `null` to leave the member out. */
    private fun typeDefinition(id: String = "CodeType", name: String? = "\"CodeType\"") =
        """"typeDefinitions":[{"id":"$id",${name?.let { "\"name\":$it," }.orEmpty()}"revision":"1","fieldType":{"type":"StringType","StringType":{}}}],"""

    private companion object {
        const val REPEATED_VALUE = """{"value":"OPEN"},{"value":"OPEN"}"""
        const val REPEATED_CATEGORY = """{"name":"Band","values":["A","B"]},{"name":"Band","values":["B","A"]}"""
        const val REPEATED_HINT = """{"locale":"en_US","values":["AB"]},{"locale":"en_US","values":["CD"]}"""
        const val EMPTY_REFERENCE = """{"type":"TypeDefType","TypeDefType":{"typeDefinitionId":""}}"""
        const val ABSENT_REFERENCE = """{"type":"TypeDefType","TypeDefType":{}}"""
    }
}
