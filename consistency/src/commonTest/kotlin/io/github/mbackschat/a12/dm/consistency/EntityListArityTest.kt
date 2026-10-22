package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the entity-list arity gates.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.EntityListArityLawsTest`.
 * The two group rows carry the risk: `AllFieldsFilled` takes one GROUP and `AllGroupsFilled` does not, so a
 * family reading every gate as "at least two" would refuse a model A12 admits.
 */
class EntityListArityTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Packing","Group":{"elements":[
                  {"type":"Field","id":"f3","name":"Courier","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Handover","Group":{"elements":[
                  {"type":"Field","id":"f4","name":"Recipient","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g3","name":"Lines","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f5","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(call: String): List<String> = codes("FieldFilled(Reference) And $call")

    @Test
    fun theOperatorsThatWantAFixedNumberOfEntities() {
        val actual = mutableMapOf<String, List<String>>()
        actual["FieldFilled over one field"] = subject("FieldFilled(GiftNote)")
        actual["FieldFilled over two fields"] = subject("FieldFilled(Reference, GiftNote)")
        actual["GroupFilled over one group"] = subject("GroupFilled(Packing)")
        actual["GroupFilled over two groups"] = subject("GroupFilled(Packing, Handover)")
        actual["SumOfProducts over two fields"] = subject("SumOfProducts(Lines*/Amount, Lines*/Amount) > 0")
        // The same starred read twice is exempt from A12's duplicate gate, so the arity gate is what
        // answers — the row that fails if this family pre-empts on a starred pair as it once did.
        actual["FieldFilled over the same starred read twice"] =
            subject("FieldFilled(Lines*/Amount, Lines*/Amount)")
        actual["SumOfProducts over one field"] = subject("SumOfProducts(Lines*/Amount) > 0")
        actual["SumOfProducts over two GROUPS"] = subject("SumOfProducts(Packing, Handover) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["FieldFilled over one field"] = emptyList()
        expected["FieldFilled over two fields"] = listOf("MVK_PARAMSIZE_INVALID1")
        expected["GroupFilled over one group"] = emptyList()
        expected["GroupFilled over two groups"] = listOf("MVK_PARAMSIZE_INVALID1")
        expected["SumOfProducts over two fields"] = emptyList()
        expected["FieldFilled over the same starred read twice"] = listOf("MVK_PARAMSIZE_INVALID1")
        expected["SumOfProducts over one field"] = listOf("MVK_PARAMSIZE_INVALID_2")
        // The kind gate answers first, so this family stays silent even though the count is also wrong, and
        // the SumOfProducts family names the group (`adapter.laws.CheckerGroupAndStarOperandAgreementTest`).
        expected["SumOfProducts over two GROUPS"] = listOf("MVK_NO_GROUPS_ALLOWED")
        assertEquals(expected, actual, "the operators that want a fixed number of entities")
    }

    @Test
    fun theGateThatRefusesALoneUnstarredField() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AllFieldsFilled over two fields"] = subject("AllFieldsFilled(Reference, GiftNote)")
        actual["AllFieldsFilled over one field"] = subject("AllFieldsFilled(GiftNote)")
        actual["AllFieldsFilled over one GROUP"] = subject("AllFieldsFilled(Packing)")
        actual["AllFieldsFilled over one starred field"] = subject("AllFieldsFilled(Lines*/Sku)")
        actual["Sum over one field"] = subject("Sum(Quantity) > 0")
        actual["NumberOfDifferentValues over one field"] = subject("NumberOfDifferentValues(GiftNote) > 0")
        actual["DateRangesOverlap over one field"] = subject("DateRangesOverlap(GiftNote)")
        actual["Sum over one UNSTARRED repeatable field"] = subject("Sum(Lines/Amount) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["AllFieldsFilled over two fields"] = emptyList()
        expected["AllFieldsFilled over one field"] = listOf("MVK_PARAMSIZE_INVALIDN")
        expected["AllFieldsFilled over one GROUP"] = emptyList()
        expected["AllFieldsFilled over one starred field"] = emptyList()
        expected["Sum over one field"] = listOf("MVK_PARAMSIZE_INVALIDN")
        expected["NumberOfDifferentValues over one field"] = listOf("MVK_PARAMSIZE_INVALIDN")
        expected["DateRangesOverlap over one field"] = listOf("MVK_PARAMSIZE_INVALIDFN")
        expected["Sum over one UNSTARRED repeatable field"] = listOf("MVK_NO_WILDCARD")
        assertEquals(expected, actual, "the gate that refuses a lone unstarred field")
    }

    @Test
    fun theGateThatRefusesALoneEntityOfAnyKind() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AllGroupsFilled over two groups"] = subject("AllGroupsFilled(Packing, Handover)")
        actual["AllGroupsFilled over one group"] = subject("AllGroupsFilled(Packing)")
        actual["NumberOfFilledGroups over one group"] = subject("NumberOfFilledGroups(Packing) > 0")
        actual["NumberOfFilledGroups over one starred group"] = subject("NumberOfFilledGroups(Lines*) > 0")
        actual["NumberOfFilledGroups over one FIELD"] = subject("NumberOfFilledGroups(GiftNote) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["AllGroupsFilled over two groups"] = emptyList()
        expected["AllGroupsFilled over one group"] = listOf("MVK_PARAMSIZE_INVALID2")
        expected["NumberOfFilledGroups over one group"] = listOf("MVK_PARAMSIZE_INVALIDGN")
        expected["NumberOfFilledGroups over one starred group"] = emptyList()
        expected["NumberOfFilledGroups over one FIELD"] = listOf("MVK_NO_FIELDS_ALLOWED")
        assertEquals(expected, actual, "the gate that refuses a lone entity of any kind")
    }

    /**
     * The shapes it leaves alone because a gate ahead of the arity one answers them.
     *
     * Both rows read the WHOLE checker, so a code here is whichever family owns that earlier gate — which is
     * what makes the row meaningful: the arity class staying away is the claim, and the earlier class
     * arriving is the evidence that the shape is genuinely pre-empted rather than merely unread.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a repeated entity under an exactly-one operator"] =
            subject("FieldFilled(GiftNote, GiftNote)")
        actual["an entity no reader can resolve"] = subject("AllFieldsFilled(Missing)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a repeated entity under an exactly-one operator"] = listOf("MVK_DUPLICATE_PARAM1")
        expected["an entity no reader can resolve"] = listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "the shapes it deliberately does not report")
    }

    /** The finding names the operator, what it wants, and what the call listed. */
    @Test
    fun theFindingNamesTheOperatorAndWhatItListed() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And AllFieldsFilled(GiftNote)",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_PARAMSIZE_INVALIDN" }
        assertEquals("/Order/Probe", finding.at)
        assertEquals(true, finding.explain.contains("AllFieldsFilled"), finding.explain)
        assertEquals(true, finding.explain.contains("GiftNote"), finding.explain)
    }
}
