package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a computation computes into a FIELD".
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.ComputedFieldTargetKindLawsTest`. The rule rows are not extra coverage: the two
 * slots hold the same kind of path and resolve through the same reader, so the only thing keeping a checker
 * from judging them alike is a row saying A12 does not.
 */
class ComputedTargetKindTest {

    private val notBuildable = "CK_MODEL_NOT_BUILDABLE"

    /**
     * A rental model whose one computation writes into [target].
     *
     * `Station` is a fixed group and `Hires` a repeatable one, so both group kinds are reachable without a
     * second fixture — the axis A12 turns out not to distinguish, which is worth showing rather than assuming.
     */
    private fun computingInto(target: String): List<String> = checkRead(ModelSource(model(computedField = target)))
        .findings.map { it.code }

    /** The same model with a RULE reporting on [target] instead, under [condition]. */
    private fun reportingOn(target: String, condition: String): List<String> =
        checkRead(ModelSource(model(errorField = target, condition = condition))).findings.map { it.code }

    @Test
    fun aComputationMayNotComputeIntoAGroupAndARuleMayReportOnOne() {
        val actual = mutableMapOf<String, List<String>>()
        actual["computed field | a sibling FIELD — the control"] = computingInto("../Total")
        actual["computed field | a FIXED group"] = computingInto("../Station")
        actual["computed field | a REPEATABLE group"] = computingInto("../Hires")
        actual["computed field | a field inside a fixed group"] = computingInto("../Station/Deposit")
        actual["computed field | a path that resolves to nothing"] = computingInto("../NoSuchField")
        actual["error target | a sibling FIELD — the control"] = reportingOn("../Total", "FieldFilled(Total)")
        actual["error target | a FIXED group"] = reportingOn("../Station", "GroupFilled(Station)")
        actual["error target | a REPEATABLE group"] =
            reportingOn("../Hires", "AtLeastOneGroupFilled(Hires)")

        val expected = mutableMapOf<String, List<String>>()
        expected["computed field | a sibling FIELD — the control"] = emptyList()
        expected["computed field | a FIXED group"] = listOf(notBuildable)
        expected["computed field | a REPEATABLE group"] = listOf(notBuildable)
        expected["computed field | a field inside a fixed group"] = emptyList()
        // ONE finding, not two: the target A12 also refuses on the build tier, already named by the
        // reference family. This family adds nothing there, which is what keeps a single cause single.
        expected["computed field | a path that resolves to nothing"] = listOf(notBuildable)
        expected["error target | a sibling FIELD — the control"] = emptyList()
        // The rows that keep the two slots apart. A12 admits a group error target, so a checker applying the
        // computation's law to both would refuse a legal model — twice, once per group kind.
        expected["error target | a FIXED group"] = emptyList()
        expected["error target | a REPEATABLE group"] = emptyList()
        assertEquals(expected, actual, "a computation computes into a field; a rule may report on a group")
    }

    /** The finding names the resolved path, since the fix is to point the computation at a field. */
    @Test
    fun theFindingNamesTheGroupItResolvedTo() {
        val finding = checkRead(ModelSource(model(computedField = "../Station"))).findings.single()
        assertEquals("/Rental/HireTotal", finding.at)
        assertEquals(true, finding.explain.contains("/Rental/Station"), finding.explain)
        assertEquals(true, finding.explain.contains("computedFieldRelPath"), finding.explain)
    }

    /**
     * …and the index field, which A12 answers with a key of its own rather than refusing to build.
     *
     * The SORT row is the control that keeps this about the index: both are direct children named by a group
     * member, and only one is refused. The outside-the-group row measures that the law follows the TARGET,
     * so keying on the resolved field alone is correct rather than a transfer.
     */
    @Test
    fun aComputationMayNotComputeIntoTheFieldARepeatableGroupIsIndexedBy() {
        val indexed = "MVK_ERROR_INDEX_AS_CALCULATED_FIELD"
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary field in the indexed group — the control"] = computingInto("../Hires/Mileage")
        actual["the group's INDEX field"] = computingInto("../Hires/Slot")
        actual["the group's SORT field"] = computingInto("../Hires/ReturnHour")
        actual["a rule reporting on the index field — the control"] =
            reportingOn("../Hires/Slot", "AtLeastOneGroupFilled(Hires)")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary field in the indexed group — the control"] = emptyList()
        expected["the group's INDEX field"] = listOf(indexed)
        expected["the group's SORT field"] = emptyList()
        expected["a rule reporting on the index field — the control"] = emptyList()
        assertEquals(expected, actual, "a computation may not compute into a group's index field")
    }

    /**
     * The bicycle-rental model, carrying either a computation or a rule.
     *
     * One builder for both because the two slots are the comparison: a second fixture could differ in
     * something neither row names, which is exactly what a paired measurement must not allow.
     */
    private fun model(
        computedField: String? = null,
        errorField: String? = null,
        condition: String? = null,
    ): String {
        val declaration = if (computedField != null) {
            """{"type":"Computation","id":"c0","name":"HireTotal","Computation":{
                 "computedFieldRelPath":"$computedField",
                 "computationAlternatives":[{"operation":"Sum(Hires*/Fee)"}],
                 "errorMessage":[{"locale":"en_US","text":"the hire total"}]}}"""
        } else {
            """{"type":"Rule","id":"r0","name":"HirePresent","Rule":{
                 "errorCondition":"$condition",
                 "errorEntityRelPath":"$errorField",
                 "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        }
        return """
            {"header":{"id":"rental","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Rental","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Station","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Deposit","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Hires","Group":{"repeatability":12,
                  "indexFieldName":"Slot","sortFields":[{"name":"ReturnHour","order":"ASC"}],"elements":[
                  {"type":"Field","id":"f2","name":"Fee","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f3","name":"Slot","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f4","name":"ReturnHour","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f5","name":"Mileage","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                $declaration
              ]}}
             ]}}}
        """.trimIndent()
    }
}
