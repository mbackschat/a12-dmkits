package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "the written field must lie under the declaring group once the construct iterates".
 *
 * Mirrors `rulekit.validate.laws.ErrorTargetContainmentLawsTest` on the same returns-handling shape. Two of
 * its cells are the whole family's risk and neither is obvious:
 *
 * - **A rule's per-row ERROR FIELD derives no iteration by itself; a computation's per-row COMPUTED FIELD
 *   does.** The gate is one gate, but the two written fields are not alike, so a family that keyed on
 *   "the written field repeats" would refuse rules A12 admits — measured across the 2×2 of construct against
 *   the declaring group's own repeatability, which is what separates the construct from the placement.
 * - **A STAR aggregate that stars every level it crosses reads no row**, so it derives no iteration either.
 *   Without that cell every aggregate rule reporting on a field outside its group is refused; a partial star
 *   is the other side, mirrored in [StarBindingContainmentTest].
 *
 * The admitted rows are therefore not politeness: each is a shape this family would refuse if it read the
 * law one step more coarsely.
 */
class ErrorTargetContainmentTest {

    private val notInRuleGroup = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP"

    @Test
    fun onlyTheIteratingConstructOwesContainment() {
        val actual = mutableMapOf<String, List<String>>()
        actual["rule | per-row error field is NOT itself an iteration | fixed declaring group"] =
            codes(rule("/Returns/Policy", "../../Claims/Memo", "GroupFilled(/Returns)"))
        actual["rule | per-row error field is NOT itself an iteration | repeatable declaring group"] =
            codes(rule("/Returns/Inspections", "../../Claims/Memo", "GroupFilled(/Returns)"))
        actual["computation | per-row computed field IS an iteration | fixed declaring group"] =
            codes(computation("/Returns/Policy", "../../Claims/Amount", "-1"))
        actual["computation | per-row computed field IS an iteration | repeatable declaring group"] =
            codes(computation("/Returns/Inspections", "../../Claims/Amount", "-1"))
        actual["rule | contained control"] =
            codes(rule("/Returns/Claims", "../Memo", "GroupFilled(/Returns)"))
        actual["computation | contained control"] =
            codes(computation("/Returns/Claims", "../Amount", "-1"))

        val expected = mutableMapOf<String, List<String>>()
        expected["rule | per-row error field is NOT itself an iteration | fixed declaring group"] =
            emptyList()
        expected["rule | per-row error field is NOT itself an iteration | repeatable declaring group"] =
            emptyList()
        expected["computation | per-row computed field IS an iteration | fixed declaring group"] =
            listOf(notInRuleGroup)
        expected["computation | per-row computed field IS an iteration | repeatable declaring group"] =
            listOf(notInRuleGroup)
        expected["rule | contained control"] = emptyList()
        expected["computation | contained control"] = emptyList()
        assertEquals(expected, actual, "the written field's own scope as the only iteration source")
    }

    @Test
    fun aPerRowREADIteratesWhereAStarAggregateDoesNot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["read of the declaring group's own extent"] =
            codes(rule("/Returns/Claims", "../../TotalRefund", "FieldFilled(../TotalRefund) And [Amount] > 0"))
        actual["reach only, no per-row read"] =
            codes(rule("/Returns/Claims", "../../TotalRefund", "FieldFilled(../TotalRefund)"))
        actual["per-row read of an INDEXED extent from a fixed declaring group"] =
            codes(rule("/Returns/Policy", "../../TotalRefund",
                "FieldFilled(../TotalRefund) And [../Claims/Amount] > 0"))
        actual["per-row read of a NON-indexed extent from a fixed declaring group"] =
            codes(rule("/Returns/Policy", "../../TotalRefund",
                "FieldFilled(../TotalRefund) And [../Inspections/Amount] > 0"))
        actual["STAR aggregate over an extent, no per-row read"] =
            codes(rule("/Returns/Policy", "../../TotalRefund",
                "FieldFilled(../TotalRefund) And Sum(/Returns/Claims*/Amount) > 0"))
        val filtered = "FieldFilled(../TotalRefund) And Sum(/Returns/Inspections*/Amount " +
            "Having [/Returns/Inspections/Amount] > 0) > 0"
        actual["FILTERED star aggregate, its body the only unstarred read"] =
            codes(rule("/Returns/Policy", "../../TotalRefund", filtered))
        actual["the same, then its body's text outside the filter"] =
            codes(rule("/Returns/Policy", "../../TotalRefund", "$filtered And [/Returns/Inspections/Amount] > 0"))

        val expected = mutableMapOf<String, List<String>>()
        expected["read of the declaring group's own extent"] = listOf(notInRuleGroup)
        expected["reach only, no per-row read"] = emptyList()
        // Neither wildcard class pre-empts once containment fails, so the extent's index declaration — which
        // decides between them where the written field IS contained — is not this family's variable.
        expected["per-row read of an INDEXED extent from a fixed declaring group"] = listOf(notInRuleGroup)
        expected["per-row read of a NON-indexed extent from a fixed declaring group"] = listOf(notInRuleGroup)
        expected["STAR aggregate over an extent, no per-row read"] = emptyList()
        // A body's read is not a per-row read of the rule's; the same characters outside the filter are one.
        expected["FILTERED star aggregate, its body the only unstarred read"] = emptyList()
        expected["the same, then its body's text outside the filter"] = listOf(notInRuleGroup)
        assertEquals(expected, actual, "a fixed written field with the read as the only iteration source")
    }

    /**
     * Containment answers ahead of referenced-ness, so this family owes no reaching-reference precondition —
     * the opposite of [ErrorTargetIterationTest]'s two classes, which are silent without one.
     *
     * The second row is the positive control: without it a pair of silence expectations would pass on a
     * model that never parsed.
     */
    @Test
    fun anUnreachedTargetIsJudgedAndPlacedAgainstAnUnresolvableName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no reach, per-row read, uncontained target"] =
            codes(rule("/Returns/Claims", "../../TotalRefund", "[Amount] > 0"))
        actual["an unresolvable name after the iterating read"] =
            allCodes(rule("/Returns/Claims", "../../TotalRefund", "[Amount] > 0 And FieldFilled(NoSuchField)"))
        actual["an unresolvable name before the iterating read"] =
            allCodes(rule("/Returns/Claims", "../../TotalRefund", "FieldFilled(NoSuchField) And [Amount] > 0"))

        val expected = mutableMapOf<String, List<String>>()
        expected["no reach, per-row read, uncontained target"] = listOf(notInRuleGroup)
        // A12 raises the class at the per-row read, so whichever of the two is written first answers
        // (`rulekit.validate.laws.ConditionClassWithholdingLawsTest.aGateIsRaisedWhereItsOwnProductionEnds`).
        expected["an unresolvable name after the iterating read"] = listOf(notInRuleGroup)
        expected["an unresolvable name before the iterating read"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "containment against referenced-ness")
    }

    /** Every A12 class the checker reports, so a co-firing class — or a fixture that never parsed — shows. */
    private fun codes(source: String): List<String> = checkRead(ModelSource(source)).findings
        .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.distinct()

    private fun allCodes(source: String): List<String> =
        checkRead(ModelSource(source)).findings.map { it.code }

    /** The returns shape with one rule declared in [group], reporting on [errorField]. */
    private fun rule(group: String, errorField: String, condition: String): String = returns(
        declaration = """
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorField",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent(),
        into = group,
    )

    /** …and with one computation writing [computedField] from [operation]. */
    private fun computation(group: String, computedField: String, operation: String): String = returns(
        declaration = """
            {"type":"Computation","id":"c0","name":"Probe","Computation":{
              "computedFieldRelPath":"$computedField",
              "computationAlternatives":[{"operation":"$operation"}],
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent(),
        into = group,
    )

    /**
     * The returns-handling model with [declaration] placed in the group named by [into].
     *
     * `Claims` declares an index field and `Inspections` does not, which is the pair the laws grid uses to
     * hold the extent's keying as a variable rather than a constant.
     */
    private fun returns(declaration: String, into: String): String {
        fun body(path: String) = if (path == into) ",$declaration" else ""
        return """
            {"header":{"id":"returns","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Returns","Group":{"elements":[
                {"type":"Field","id":"f0","name":"TotalRefund","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Policy","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Excess","Field":{"fieldType":{"type":"NumberType"}}}
                  ${body("/Returns/Policy")}
                ]}},
                {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":20,
                  "indexFieldName":"ClaimNo","elements":[
                    {"type":"Field","id":"f2","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Field","id":"f4","name":"Memo","Field":{"fieldType":{"type":"StringType"}}}
                    ${body("/Returns/Claims")}
                ]}},
                {"type":"Group","id":"g3","name":"Inspections","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f5","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                  ${body("/Returns/Inspections")}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
