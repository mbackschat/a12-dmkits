package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what an enum-valued computation may compute from.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EnumComputationSourceLawsTest`. The SILENT rows carry the risk and there are four
 * kinds of them: three shapes the comparison walk answers before the calculation module runs, and one this
 * reader declines to reduce at all.
 */
class EnumComputationSourceTest {

    @Test
    fun whatAnEnumValuedComputationMayComputeFrom() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a declared value of the target — the control"] = computing("""\"OPEN\"""")
        actual["a value the target does not declare"] = computing("""\"VOID\"""")
        actual["an enumeration declaring exactly the target's values"] = computing("[Mirror]")
        actual["an enumeration declaring FEWER"] = computing("[Narrow]")
        actual["an enumeration declaring MORE"] = computing("[Wider]")
        actual["a LABELLED enumeration against an unlabelled target"] = computing("[Labelled]")
        actual["a category whose tokens the target covers"] = computing("[Banded -> Band]")
        actual["a category whose tokens it does not"] = computing("[Banded -> Wide]")
        actual["a text field"] = computing("[Note]")
        actual["a number field"] = computing("[Amount]")
        actual["FirstFilledValue over two covered enumerations"] = computing("FirstFilledValue(Mirror, Narrow)")
        actual["FirstFilledValue where one is not covered"] = computing("FirstFilledValue(Mirror, Wider)")
        actual["a string OPERATION rather than a read"] = computing("""[Mirror] + \"\"""")
        actual["the wide source into a TEXT target instead"] = computing("[Wider]", target = "../Note")

        val refused = listOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        val expected = mutableMapOf<String, List<String>>()
        expected["a declared value of the target — the control"] = emptyList()
        // The comparison walk owns this class and answers during the parse, so the calculation gate is
        // never reached — naming it here would be the wrong class on a correct rejection.
        expected["a value the target does not declare"] = emptyList()
        expected["an enumeration declaring exactly the target's values"] = emptyList()
        expected["an enumeration declaring FEWER"] = emptyList()
        expected["an enumeration declaring MORE"] = refused
        // Same pre-emption, by the display half of that walk.
        expected["a LABELLED enumeration against an unlabelled target"] = emptyList()
        expected["a category whose tokens the target covers"] = emptyList()
        expected["a category whose tokens it does not"] = refused
        // The discriminating row: a plain text against a TEXTLESS enumeration is admitted by the comparison
        // walk, so this gate is the only thing that catches it.
        expected["a text field"] = refused
        // …and a NUMBER source is the walk's kind class, not this one's.
        expected["a number field"] = emptyList()
        expected["FirstFilledValue over two covered enumerations"] = emptyList()
        expected["FirstFilledValue where one is not covered"] = refused
        // A12 refuses every operation outside its three kinds; this reader declines to reduce the shape and
        // discloses the under-report rather than guessing which class the walk would answer with.
        expected["a string OPERATION rather than a read"] = emptyList()
        // The control that makes the whole grid a law about the TARGET rather than about the operations.
        expected["the wide source into a TEXT target instead"] = emptyList()
        assertEquals(expected, actual, "what an enum-valued computation may compute from")
    }

    /**
     * A keyed text source is a text source: the key addresses the repetition it keys, and A12 refuses the read
     * as it refuses the root's own text field (`rulekit.validate.laws.KeyedOperandFamilyGateLawsTest`
     * `.anEnumComputationSourceReadThroughAKey`). Unkeyed, the read is the reference family's `MVK_NO_WILDCARD`,
     * and a defective key draws the key's own class, so neither is this gate's.
     */
    @Test
    fun aKeyedTextSourceIsATextSource() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a string key"] = computing("""[Claims/Memo For \"X\"]""")
        actual["a field key"] = computing("[Claims/Memo For Code]")
        actual["no key"] = computing("[Claims/Memo]")
        actual["a number literal key"] = computing("[Claims/Memo For 5]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a string key"] = listOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        expected["a field key"] = listOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        expected["no key"] = emptyList()
        expected["a number literal key"] = emptyList()
        assertEquals(expected, actual, "a keyed text source")
    }

    /**
     * What a `FirstFilledValue` operand owes this gate that a sole read does not, mirroring
     * `EnumComputationSourceLawsTest.aFirstFilledValueOperandOwesThisGateTheDisplayCheck`: inside the call A12
     * asks the display check here rather than in the comparison walk, and a custom-typed source is no
     * enumeration at all.
     */
    @Test
    fun aFirstFilledValueOperandOwesThisGateTheDisplayCheck() {
        val refused = listOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
        assertEquals(mapOf(
            "an unlabelled and a labelled operand into an unlabelled target" to refused,
            // The comparison walk's class, so this gate stays silent on the sole read.
            "the labelled read alone" to emptyList(),
            "two unlabelled operands into a labelled target" to refused,
            "a custom-typed field" to refused,
            "a custom-typed operand among enumerations" to refused,
        ), mapOf(
            "an unlabelled and a labelled operand into an unlabelled target" to
                computing("FirstFilledValue(Mirror, Labelled)"),
            "the labelled read alone" to computing("[Labelled]"),
            "two unlabelled operands into a labelled target" to
                computing("FirstFilledValue(Status, Mirror)", target = "../Labelled"),
            "a custom-typed field" to computing("[Loose]"),
            "a custom-typed operand among enumerations" to computing("FirstFilledValue(Mirror, Loose)"),
        ))
    }

    /** The finding names the uncovered value, since widening the target or projecting the source is the fix. */
    @Test
    fun theFindingNamesTheUncoveredValue() {
        val finding = checkRead(ModelSource(model("[Wider]", "../Status")))
            .findings.single { it.code == "MVK_ERROR_INVALID_ENUM_CALCULATION" }
        assertEquals("/Policy/DerivedStatus", finding.at)
        assertEquals(true, finding.explain.contains("VOID"), finding.explain)
    }

    /**
     * A self-read stands this gate down for its own operation only, mirroring
     * `EnumComputationSourceLawsTest.aSelfReadStandsDownTheGateForItsOwnOperationOnly`: A12 judges an
     * operation's reference to the computed field first and returns on it, judges every other alternative,
     * walks a precondition separately, and never walks an unguarded alternative among several (KF616).
     * Reporting both classes on the first row was an over-rejection (SPEC-2026-10-24-15).
     */
    @Test
    fun aSelfReadStandsDownTheGateForItsOwnOperationOnly() {
        val selfRead = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"
        val refused = "MVK_ERROR_INVALID_ENUM_CALCULATION"
        fun verdict(alternatives: String) = checkRead(ModelSource(model("", "../Status", alternatives))).findings
            .filter { it.code == selfRead || it.code == refused }.map { it.code }

        val actual = mapOf(
            "beside the target itself" to verdict("""[{"operation":"FirstFilledValue(Wider, Status)"}]"""),
            "beside another field — the control" to verdict("""[{"operation":"FirstFilledValue(Wider, Mirror)"}]"""),
            "self-read in one alternative, uncovered source in the other" to verdict("""[
                {"precondition":"FieldFilled(Reference)","operation":"FirstFilledValue(Wider, Status)"},
                {"precondition":"FieldFilled(Note)","operation":"[Wider]"}]"""),
            "self-read in the precondition" to verdict("""[{"precondition":"FieldFilled(Status)","operation":"[Wider]"}]"""),
            "uncovered source in an unguarded alternative among several" to verdict("""[
                {"operation":"[Wider]"},{"precondition":"FieldFilled(Note)","operation":"[Mirror]"}]"""),
        )
        assertEquals(mapOf(
            "beside the target itself" to listOf(selfRead),
            "beside another field — the control" to listOf(refused),
            "self-read in one alternative, uncovered source in the other" to listOf(selfRead, refused),
            "self-read in the precondition" to listOf(refused, selfRead),
            "uncovered source in an unguarded alternative among several" to emptyList(),
        ), actual)
    }

    private fun computing(operation: String, target: String = "../Status"): List<String> =
        checkRead(ModelSource(model(operation, target))).findings
            .filter { it.code in OWNED }
            .map { it.code }

    /** A policy administration desk mirroring the laws test's carrier. */
    private fun model(
        operation: String,
        target: String,
        alternatives: String = """[{"operation":"$operation"}]""",
    ): String = """
        {"header":{"id":"policy-enum-computation","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f3","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"}]}}}},
            {"type":"Field","id":"f4","name":"Mirror","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"}]}}}},
            {"type":"Field","id":"f5","name":"Narrow","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"}]}}}},
            {"type":"Field","id":"f6","name":"Wider","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"},{"value":"VOID"}]}}}},
            {"type":"Field","id":"f7","name":"Labelled","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[
                {"value":"OPEN","label":[{"locale":"en_US","text":"Open case"}]},
                {"value":"CLOSED","label":[{"locale":"en_US","text":"Closed case"}]}]}}}},
            {"type":"Field","id":"f8","name":"Banded","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"OPEN"},{"value":"CLOSED"},{"value":"VOID"}],
              "categories":[
                {"name":"Band","values":[{"value":"OPEN"},{"value":"CLOSED"},{"value":"OPEN"}]},
                {"name":"Wide","values":[{"value":"OPEN"},{"value":"CLOSED"},{"value":"VOID"}]}]}}}},
            {"type":"Field","id":"f9","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f12","name":"Loose","Field":{"fieldType":{"type":"CustomFieldType",
              "CustomFieldType":{"name":"Loose"}}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":20,"indexFieldName":"ClaimRef",
              "elements":[
              {"type":"Field","id":"f10","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f11","name":"Memo","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Computation","id":"c0","name":"DerivedStatus","Computation":{
              "computedFieldRelPath":"$target",
              "computationAlternatives":$alternatives,
              "errorMessage":[{"locale":"en_US","text":"the derived status"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf("MVK_ERROR_INVALID_ENUM_CALCULATION")
    }
}
