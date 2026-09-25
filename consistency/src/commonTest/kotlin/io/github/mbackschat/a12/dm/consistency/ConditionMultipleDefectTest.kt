package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checker's half of "A12 reports the first defect in the condition text and nothing after it".
 *
 * Mirrors `rulekit.validate.laws.ConditionMultipleDefectLawsTest` and `…ConditionClassWithholdingLawsTest`.
 * Eleven families read one condition text, so without the withholding a condition carrying two defects — the
 * normal state of one being written — draws a class A12 withheld.
 *
 * **The gate is position-blind for a class that carries no span**, so the rows for those classes all put the
 * bad name first, which is the case the two agree on. Where such a class comes first A12 names it and the
 * checker names the bad name instead; that is the disclosed substitution in `CONDITION_TEXT_CODES`, not a
 * row to lock as agreement. A class that carries its span is placed, and
 * [aClassCarryingItsSpanIsPlacedAgainstTheBadName] holds it to A12 in both orders.
 */
class ConditionMultipleDefectTest {

    private fun codes(condition: String, errorTarget: String = "../Reference"): List<String> =
        check(ModelSource(model(condition, errorTarget))).findings
            .filter { it.at == "/Policy/Probe" }.map { it.code }.distinct().sorted()

    @Test
    fun aSyntaxClassIsWithheldBehindAnUnresolvableName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["sound — the control"] = codes("FieldFilled(Reference)")
        actual["one unresolvable reference"] = codes("FieldFilled(NoSuchField)")
        actual["a trailing operator alone"] = codes("FieldFilled(Reference) And")
        actual["an unresolvable reference AND a trailing operator"] = codes("FieldFilled(NoSuchField) And")
        actual["an unexpected token alone"] = codes("FieldFilled(Reference) And And FieldFilled(Reference)")
        actual["an unresolvable reference AND an unexpected token"] =
            codes("FieldFilled(NoSuchField) And And FieldFilled(Reference)")

        val expected = mutableMapOf<String, List<String>>()
        expected["sound — the control"] = emptyList()
        expected["one unresolvable reference"] = listOf("MVK_INVALID_ENTITY")
        expected["a trailing operator alone"] = listOf("MVK_INCOMPLETE_INPUT")
        // The pair of rows with teeth: each syntax class fires alone and is gone beside an unresolvable
        // name. Reporting both would name a verdict A12 withheld on the likeliest real input of all.
        expected["an unresolvable reference AND a trailing operator"] = listOf("MVK_INVALID_ENTITY")
        expected["an unexpected token alone"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["an unresolvable reference AND an unexpected token"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "which class a condition with two defects reports")
    }

    /**
     * Every other class read out of the condition text, each firing alone and gone behind a bad name.
     *
     * The three classes the first test holds are syntax ones, and a gate naming only those left eight more
     * live — every one of them an over-rejection on a condition that also carries a bad name.
     */
    @Test
    fun everyConditionTextClassIsWithheldBehindAnUnresolvableName() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        fun pair(row: String, condition: String, alone: String) {
            actual[row] = codes(condition)
            expected[row] = listOf(alone)
            actual["$row, behind a bad name"] = codes("FieldFilled(NoSuchField) And $condition")
            expected["$row, behind a bad name"] = listOf("MVK_INVALID_ENTITY")
        }
        // The quote is escaped for the JSON fixture, so the condition A12 and the checker both read is
        // `FieldFilled("Reference)` — an opening literal that is never closed.
        pair("an unlexable literal", "FieldFilled(\\\"Reference)", "MVK_LEXER_STANDARD_ERROR")
        pair("mixed connectives",
            "FieldFilled(Reference) And FieldFilled(Reference) Or FieldFilled(Reference)",
            "MVK_BRACKET_MISSING")
        pair("an empty group named", "GroupFilled(/Policy/Archive)", "MVK_GROUP_IS_EMPTY")
        pair("a group named twice", "AllGroupsFilled(/Policy/Assessment, /Policy/Assessment)",
            "MVK_DUPLICATE_PARAM1")
        pair("a ROOT group as an operand", "AllGroupsFilled(/Registry, /Policy/Assessment)",
            "MVK_ROOT_GROUP_REFERENCED")
        pair("a field where a group belongs", "GroupFilled(/Policy/Assessment/Notes)",
            "MVK_NO_FIELDS_ALLOWED")
        pair("a star on a fixed group", "GroupFilled(/Policy/Assessment*)", "MVK_INVALID_WILDCARD")

        // The two that do not compete for a position: withheld wherever the bad name sits, so the bad name
        // goes AFTER here — the one arrangement that tells them apart from the seven above.
        actual["an error field the condition does not name"] = codes("FieldFilled(Reference)", "../Score")
        expected["an error field the condition does not name"] = listOf("MVK_ERROR_FIELD_NOT_REFERENCED")
        actual["an unnamed error field, bad name AFTER"] =
            codes("FieldFilled(Reference) And FieldFilled(NoSuchField)", "../Score")
        expected["an unnamed error field, bad name AFTER"] = listOf("MVK_INVALID_ENTITY")

        assertEquals(expected, actual, "which class a condition with two defects reports")
    }

    /**
     * The app-condition class, the second position-independent one, on its computation carrier.
     *
     * The third row is the one that needs the relational gate rather than the unresolvable-name gate: every
     * name in it resolves, and the class goes anyway because the empty group spoke about the same text.
     */
    @Test
    fun anAppConditionIsWithheldBehindAnyOtherDefectInItsText() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an app condition alone"] =
            computing("FieldFilled(Base) And CustomCondition NotReverse", "[Base] + 1")
        actual["an app condition, bad name AFTER"] = computing(
            "FieldFilled(Base) And CustomCondition NotReverse And FieldFilled(NoSuchField)", "[Base] + 1")
        actual["an app condition AHEAD of an empty group"] = computing(
            "CustomCondition NotReverse And GroupFilled(/Depot/Archive)", "[Base] + 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["an app condition alone"] = listOf("MVK_ERROR_INVALID_LANGUAGE_FEATURE")
        expected["an app condition, bad name AFTER"] = listOf("MVK_INVALID_ENTITY")
        expected["an app condition AHEAD of an empty group"] = listOf("MVK_GROUP_IS_EMPTY")
        assertEquals(expected, actual, "an app condition is withheld behind any other defect in its text")
    }

    /**
     * …and the withholding spans a COMPUTATION's two members, which is why the gate is per declaration.
     *
     * The unresolvable name sits in the precondition and the syntax failure in the operation, so a gate
     * applied one text at a time would report the syntax class A12 withheld.
     */
    @Test
    fun anUnresolvableNameInOneMemberWithholdsTheSyntaxClassOfTheOther() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a sound precondition, an operation that does not parse"] =
            computing("FieldFilled(Base)", "[Base] +")
        actual["a precondition naming nothing, an operation that does not parse"] =
            computing("FieldFilled(NoSuchField)", "[Base] +")

        val expected = mutableMapOf<String, List<String>>()
        expected["a sound precondition, an operation that does not parse"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["a precondition naming nothing, an operation that does not parse"] =
            listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "an unresolvable name withholds the other member's syntax class")
    }

    /**
     * The classes that carry their span, placed against the bad name in both orders — and the one kind that
     * cannot be placed, a class with no span, which stands beside a later spanned one where A12 names only
     * the first (`DEV-API-GAPS` DG84). Every row is `rulekit.validate.laws.ConditionClassWithholdingLawsTest`'s.
     */
    @Test
    fun aClassCarryingItsSpanIsPlacedAgainstTheBadName() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        fun ordered(row: String, condition: String, alone: String, errorTarget: String = "../Reference") {
            actual["$row, bad name after"] = codes("$condition And FieldFilled(NoSuchField)", errorTarget)
            expected["$row, bad name after"] = listOf(alone)
            actual["$row, bad name BEFORE"] = codes("FieldFilled(NoSuchField) And $condition", errorTarget)
            expected["$row, bad name BEFORE"] = listOf("MVK_INVALID_ENTITY")
        }
        ordered("a star on a fixed group", "GroupFilled(/Policy/Assessment*)", "MVK_INVALID_WILDCARD")
        ordered("an unstarred repeatable read", "FieldFilled(/Policy/Claims/Amount)", "MVK_NO_WILDCARD")
        ordered("a character the lexer cannot take", "FieldFilled(Reference) And % FieldFilled(Reference)",
            "MVK_LEXER_STANDARD_ERROR")
        ordered("a stray connective", "FieldFilled(Reference) And And FieldFilled(Reference)",
            "MVK_UNEXPECTED_TOKEN")
        ordered("a path climbing above the root", "FieldFilled(../../Reference)", "MVK_TOO_MANY_PARENT_GROUPS")
        ordered("a parallel level with no index on the target's side",
            "FieldFilled(/Policy/Claims/Amount) And FieldFilled(/Policy/Payouts/Total)", "MVK_ERROR_FIELD_NO_INDEX",
            errorTarget = "../Claims/Amount")

        val stray = "FieldFilled(Reference) And And FieldFilled(Reference)"
        actual["a misplaced star, then a stray connective"] = codes("GroupFilled(/Policy/Assessment*) And $stray")
        expected["a misplaced star, then a stray connective"] = listOf("MVK_INVALID_WILDCARD")
        actual["a stray connective, then a misplaced star"] = codes("$stray And GroupFilled(/Policy/Assessment*)")
        expected["a stray connective, then a misplaced star"] = listOf("MVK_UNEXPECTED_TOKEN")

        // A12 names the first of these two, in either order. The empty-group class carries no span, so
        // neither can be placed and both stand: the residual this checker discloses rather than guesses at.
        actual["an empty group, then a misplaced star"] =
            codes("GroupFilled(/Policy/Archive) And GroupFilled(/Policy/Assessment*)")
        expected["an empty group, then a misplaced star"] = listOf("MVK_GROUP_IS_EMPTY", "MVK_INVALID_WILDCARD")
        actual["a misplaced star, then an empty group"] =
            codes("GroupFilled(/Policy/Assessment*) And GroupFilled(/Policy/Archive)")
        expected["a misplaced star, then an empty group"] = listOf("MVK_GROUP_IS_EMPTY", "MVK_INVALID_WILDCARD")
        assertEquals(expected, actual, "which class a condition carrying a spanned class and a bad name reports")
    }

    /**
     * Two rules sharing one path are two texts under one `at`, so an offset in one says nothing about the
     * other. The wildcard class is EARLIER in its own text than the bad name is in its twin's, and a placement
     * comparing the two would keep it; the position-blind withholding applies instead, the same under-report
     * every span-less class takes (`Check.kt`, `pathsSharedByTwoDeclarations`).
     */
    @Test
    fun twoRulesAtOnePathAreNeverPlacedAgainstEachOther() {
        val reported = check(ModelSource(twins(
            "FieldFilled(Reference) And FieldFilled(NoSuchField)", "GroupFilled(/Policy/Assessment*)",
        ))).findings.filter { it.at == "/Policy/Probe" }.map { it.code }.toSet()

        assertTrue("MVK_INVALID_ENTITY" in reported, "the bad name must be reported, or this row is vacuous")
        assertFalse("MVK_INVALID_WILDCARD" in reported, "a twin's offset was compared with the other's: $reported")
    }

    /** [model] with a second rule of the SAME name carrying [second], so the two share one path. */
    private fun twins(first: String, second: String): String = model(
        first, "../Reference",
        twin = """{"type":"Rule","id":"r9","name":"Probe","Rule":{"errorCondition":"$second",
          "errorEntityRelPath":"../Reference","errorCode":"C2",
          "errorMessage":[{"locale":"en_US","text":"see it"}]}},""",
    )

    private fun computing(precondition: String, operation: String): List<String> =
        check(ModelSource(computationModel(precondition, operation))).findings
            .filter { it.at == "/Depot/WeightCalc" }.map { it.code }.distinct().sorted()

    private fun computationModel(precondition: String, operation: String): String = """
        {"header":{"id":"depot","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f1","name":"Base","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Archive","Group":{"elements":[]}},
            {"type":"Computation","id":"c0","name":"WeightCalc","Computation":{
              "computedFieldRelPath":"../Weight",
              "computationAlternatives":[{"precondition":"$precondition","operation":"$operation"}],
              "errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun model(condition: String, errorTarget: String, twin: String = ""): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Score","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Assessment","Group":{"elements":[
              {"type":"Field","id":"f2","name":"Notes","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Archive","Group":{"elements":[]}},
            {"type":"Group","id":"g4","name":"Claims","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g5","name":"Payouts","Group":{"repeatability":5,"indexFieldName":"PayoutNo",
              "elements":[
              {"type":"Field","id":"f5","name":"PayoutNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f6","name":"Total","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            $twin
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}},
          {"type":"Group","id":"g3","name":"Registry","Group":{"elements":[
            {"type":"Field","id":"f3","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
          ]}}
         ]}}}
    """.trimIndent()
}
