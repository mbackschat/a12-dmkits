package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a rule that reaches its error field only from above, while reading a repetition".
 *
 * Mirrors `rulekit.validate.laws.RootOnlyErrorTargetIterationLawsTest` on the same reconciliation shape. The
 * admitted rows carry the family's whole risk: naming the rule's OWN group and naming the error field both
 * PIN the repetition, and a checker that treated every reaching reference alike would refuse them.
 */
class ErrorTargetIterationTest {

    private val ownGroup = "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"
    private val parallel = "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID"

    @Test
    fun theCarrierOfTheUnpinnedReadPicksTheClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["root reference, no iterating read"] = codes("GroupFilled(/Shipment)")
        actual["root reference, read in the error field's OWN group"] =
            codes("GroupFilled(/Shipment) And [Quantity] > 0")
        actual["root reference, read of the OTHER field in that group"] =
            codes("GroupFilled(/Shipment) And FieldFilled(LineNo)")
        actual["root reference, read in a PARALLEL repeatable group"] =
            codes("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0")
        actual["root reference, BOTH iterating reads | own first"] =
            codes("GroupFilled(/Shipment) And [Quantity] > 0 And [/Shipment/Expected/Quantity] > 0")
        actual["root reference, BOTH iterating reads | PARALLEL first"] =
            codes("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0 And [Quantity] > 0")
        actual["the RULE'S OWN group instead of the root"] =
            codes("GroupFilled(/Shipment/Received) And [Quantity] > 0")
        actual["a direct reference to the error field"] =
            codes("FieldFilled(Note) And GroupFilled(/Shipment) And [Quantity] > 0")
        actual["NOTHING reaches the error field, with an iterating read"] = codes("[Quantity] > 0")
        actual["fixed root | read in the error field's OWN group"] =
            codes("GroupFilled(/Shipment) And [Quantity] > 0", repeatableRoot = false)
        actual["fixed root | read in a PARALLEL repeatable group"] =
            codes("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0", repeatableRoot = false)

        val expected = mutableMapOf<String, List<String>>()
        expected["root reference, no iterating read"] = emptyList()
        expected["root reference, read in the error field's OWN group"] = listOf(ownGroup)
        expected["root reference, read of the OTHER field in that group"] = listOf(ownGroup)
        expected["root reference, read in a PARALLEL repeatable group"] = listOf(parallel)
        // The parallel class wins on its own account, not by position — both orders measured.
        expected["root reference, BOTH iterating reads | own first"] = listOf(parallel)
        expected["root reference, BOTH iterating reads | PARALLEL first"] = listOf(parallel)
        // The two PINNING shapes. A checker treating every reaching reference alike refuses both.
        expected["the RULE'S OWN group instead of the root"] = emptyList()
        expected["a direct reference to the error field"] = emptyList()
        // …and the root's own repeatability is not what the classes key on.
        // Measured: A12 draws MVK_ERROR_FIELD_NOT_REFERENCED alone, and that family already reports it.
        // Every other row carries a reaching reference, so without this cell the two silences — reached and
        // pinned, versus never reached — are one, which is how this family first over-reported.
        expected["NOTHING reaches the error field, with an iterating read"] = emptyList()
        expected["fixed root | read in the error field's OWN group"] = listOf(ownGroup)
        expected["fixed root | read in a PARALLEL repeatable group"] = listOf(parallel)
        assertEquals(expected, actual, "root-only error-target iteration")
    }

    /**
     * A target OUTSIDE the declaring group, where A12's containment gate answers ahead of both classes.
     *
     * The rule moves to the PARALLEL group and nothing else changes. Every row of the grid above declares
     * it in the error field's own group, so the containment demand was a constant of that whole set rather
     * than something any of its rows tested — measured 2026-09-14 in the laws test, after this family named
     * the parallel class on exactly this shape. `Rule.build()` refuses the pair, so it can only arrive as
     * stored DM-JSON, which is what this checker reads.
     *
     * The third row is the POSITIVE CONTROL, and it is not optional: two rows expecting silence pass just as
     * well on a model that never parsed, which is how a mirror goes vacuous rather than red.
     */
    @Test
    fun aTargetOutsideTheDeclaringGroupIsNotThisFamilys() {
        val actual = mutableMapOf<String, List<String>>()
        actual["read in the error field's own group"] =
            outsideGroup("GroupFilled(/Shipment) And [/Shipment/Received/Quantity] > 0")
        actual["read in a PARALLEL repeatable group"] =
            outsideGroup("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0")
        actual["an unresolvable name in the same model — the positive control"] =
            allCodes(outsideGroupModel("FieldFilled(NoSuchField)"))

        val expected = mutableMapOf<String, List<String>>()
        expected["read in the error field's own group"] = emptyList()
        expected["read in a PARALLEL repeatable group"] = emptyList()
        expected["an unresolvable name in the same model — the positive control"] =
            listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "containment answers ahead of both iteration classes")
    }

    private fun outsideGroup(condition: String): List<String> =
        checkRead(ModelSource(outsideGroupModel(condition))).findings
            .filter { it.code == ownGroup || it.code == parallel }
            .map { it.code }

    private fun allCodes(source: String): List<String> =
        checkRead(ModelSource(source)).findings.map { it.code }

    /** The reconciliation shape with the rule declared in `Expected`, reporting on `Received/Note`. */
    private fun outsideGroupModel(condition: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"shipment","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Shipment","Group":{"repeatability":5,"elements":[
            {"type":"Group","id":"g1","name":"Received","Group":{"repeatability":10,
              "indexFieldName":"LineNo","elements":[
                {"type":"Field","id":"f0","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
              ]}},
            {"type":"Group","id":"g2","name":"Expected","Group":{"repeatability":10,
              "indexFieldName":"LineNo","elements":[
                {"type":"Field","id":"f3","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f4","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"Reconcile","Rule":{
                  "errorCondition":"$condition","errorEntityRelPath":"../../Received/Note",
                  "errorCode":"NOTE","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
          ]}}
         ]}}}
    """.trimIndent()

    /** …and with nothing repeating there is no repetition to be unable to choose between. */
    @Test
    fun aFlatModelIsNotThisFamilysBusiness() {
        val flat = """
            {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Tier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"AutoRenew","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Inner","Group":{"elements":[
                  {"type":"Rule","id":"r0","name":"PathRule","Rule":{
                    "errorCondition":"FieldNotFilled(../AutoRenew) And FieldNotFilled(../Tier)",
                    "errorEntityRelPath":"../../AutoRenew",
                    "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
        assertEquals(
            emptyList(),
            checkRead(ModelSource(flat)).findings.filter { it.code == ownGroup || it.code == parallel }
                .map { it.code },
            "a flat model has no repetition to disambiguate — kernel-measured ADMITTED",
        )
    }

    /**
     * Only the rule's own iterating reads count: a starred, a keyed and an unmarked filter-body read are
     * admitted, a marked body read is not, and the error field read only in a body is unreferenced. Mirrors
     * `adapter.laws.CheckerRuleReferenceSetAgreementTest`, which measures every row on the kernel.
     */
    @Test
    fun onlyTheRulesOwnIteratingReadsCount() {
        val rows = linkedMapOf(
            "GroupFilled(/Shipment) And Sum(/Shipment/Received*/Quantity) > 0" to emptyList(),
            "GroupFilled(/Shipment) And Sum(/Shipment/Expected*/Quantity) > 0" to emptyList(),
            "GroupFilled(/Shipment) And Sum(/Shipment/Returns*/Weight) > 0" to emptyList(),
            "GroupFilled(/Shipment) And [/Shipment/Received/Quantity For \"L1\"] > 0" to emptyList(),
            "GroupFilled(/Shipment) And [/Shipment/Expected/Quantity For \"L1\"] > 0" to emptyList(),
            "GroupFilled(/Shipment) And Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Quantity] > 1) > 0" to
                emptyList(),
            "GroupFilled(/Shipment) And Sum(/Shipment/Returns*/Weight Having [/Shipment/Returns/Weight] > [\$Quantity]) > 0" to
                listOf(ownGroup),
            "Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Note] == \"x\") > 0" to
                listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
            "Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Quantity] > 1 And [\$Note] == \"x\") > 0" to
                emptyList(),
            "[Quantity] > 0 And Sum(/Shipment/Received*/Quantity Having GroupFilled(/Shipment) And [/Shipment/Received/Quantity] > 1) > 0" to
                listOf("MVK_ERROR_FIELD_NOT_REFERENCED"),
        )
        val actual = rows.keys.associateWith { condition ->
            checkRead(ModelSource(model(condition, repeatableRoot = false))).findings
                .filter { it.justifiesRejection }.map { it.code }
        }
        assertEquals(rows, actual, "which reads the post-parse gates count")
    }

    private fun codes(condition: String, repeatableRoot: Boolean = true): List<String> =
        checkRead(ModelSource(model(condition, repeatableRoot))).findings
            .filter { it.code == ownGroup || it.code == parallel }
            .map { it.code }

    private fun model(condition: String, repeatableRoot: Boolean): String {
        val rootRepeats = if (repeatableRoot) """"repeatability":5,""" else ""
        return """
            {"header":{"modelVersion":"29.4.0","id":"shipment","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{$rootRepeats"elements":[
                {"type":"Group","id":"g1","name":"Received","Group":{"repeatability":10,
                  "indexFieldName":"LineNo","elements":[
                    {"type":"Field","id":"f0","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"Reconcile","Rule":{
                      "errorCondition":"${condition.replace("\\", "\\\\").replace("\"", "\\\"")}","errorEntityRelPath":"../Note",
                      "errorCode":"NOTE","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}},
                {"type":"Group","id":"g2","name":"Expected","Group":{"repeatability":10,
                  "indexFieldName":"LineNo","elements":[
                    {"type":"Field","id":"f3","name":"LineNo","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f4","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}},
                {"type":"Group","id":"g3","name":"Returns","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f5","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
