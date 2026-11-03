package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `RepetitionNotUnique`-under-parallel-iteration gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ParallelIterationGateLawsTest`. The two SILENT rows carry the risk and they are the
 * reason this family exists in this shape: a STARRED read is not a joint iteration, and index fields of
 * different declared types draw a class of their own, so a family reading names alone would refuse a legal
 * model in the first case and name the wrong class in the second.
 */
class ParallelIterationUniquenessTest {

    private val notAllowed = "MVK_PARALLEL_ITERATION_NOT_ALLOWED"

    /** A depot model whose `Lines` and `Picks` both key on `LineRef`, of [pickKeyType] on the Picks side. */
    private fun codes(condition: String, pickKeyType: String = "StringType"): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"depot","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                {"type":"Field","id":"f0","name":"DepotRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":50,
                  "indexFieldName":"LineRef","elements":[
                  {"type":"Field","id":"f1","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"OrderedQty","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Picks","Group":{"repeatability":50,
                  "indexFieldName":"LineRef","elements":[
                  {"type":"Field","id":"f3","name":"LineRef","Field":{"fieldType":{"type":"PICK_KEY"}}},
                  {"type":"Field","id":"f4","name":"PickedQty","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g4","name":"Checks","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f7","name":"CheckQty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                {"type":"Group","id":"g3","name":"Bays","Group":{"repeatability":9,
                  "indexFieldName":"BayRef","elements":[
                  {"type":"Field","id":"f5","name":"BayRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"BayQty","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Lines/LineRef",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition).replace("PICK_KEY", pickKeyType),
        ),
    ).findings.map { it.code }

    @Test
    fun whenAUniquenessCheckSitsInAJointIteration() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a uniqueness check alone — the control"] = codes("RepetitionNotUnique(Lines/LineRef)")
        actual["a uniqueness check beside a read of the JOINT group"] =
            codes("RepetitionNotUnique(Lines/LineRef) And FieldFilled(Picks/PickedQty)")
        actual["a uniqueness check beside a read of a DIFFERENTLY keyed group"] =
            codes("RepetitionNotUnique(Lines/LineRef) And FieldFilled(Bays/BayQty)")
        actual["a uniqueness check beside a read starred BELOW the joint group"] =
            codes("RepetitionNotUnique(Lines/LineRef) And Sum(Picks/Checks*/CheckQty) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a uniqueness check alone — the control"] = emptyList()
        expected["a uniqueness check beside a read of the JOINT group"] = listOf(notAllowed)
        // `Bays` keys on a different field, so nothing pairs it with `Lines` and NO joint iteration exists —
        // this family is silent. A12 refuses the read for the opposite reason and
        // [operandScopeContributions] names that class, which is why the row is not empty.
        expected["a uniqueness check beside a read of a DIFFERENTLY keyed group"] =
            listOf("MVK_ERROR_FIELD_INVALID_INDEX")
        // A star marks the level it sits on and nothing above it, so `Picks` still iterates jointly here —
        // the row that separates "the reference carries a star" from "the JOINT level is starred"
        // (`rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`).
        expected["a uniqueness check beside a read starred BELOW the joint group"] = listOf(notAllowed)
        assertEquals(expected, actual, "when a uniqueness check sits in a joint iteration")
    }

    /**
     * The two shapes it stays SILENT on, each because A12 answers them with something else.
     *
     * A STARRED read is admitted outright — the star puts a wildcard at that level rather than the joint
     * iteration marker — so reporting there would refuse a legal model. Index fields of different declared
     * types draw `MVK_ERROR_FIELD_INVALID_INDEX_TYPE`, ahead of this gate, so reporting THIS class there
     * would be the wrong class on a correct rejection; [operandScopeContributions] owns that one and
     * names it, which is what the second row now shows.
     */
    @Test
    fun theShapesItDeliberatelyDoesNotReport() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the joint group read with a STAR"] =
            codes("RepetitionNotUnique(Lines/LineRef) And Sum(Picks*/PickedQty) > 0")
        actual["index fields of different declared TYPES"] =
            codes("RepetitionNotUnique(Lines/LineRef) And FieldFilled(Picks/PickedQty)", "NumberType")

        val expected = mutableMapOf<String, List<String>>()
        expected["the joint group read with a STAR"] = emptyList()
        expected["index fields of different declared TYPES"] = listOf("MVK_ERROR_FIELD_INVALID_INDEX_TYPE")
        assertEquals(expected, actual, "the joint-iteration shapes deliberately not reported")
    }

    /** The finding names both groups and the shared key, since the fix is to break the pairing. */
    @Test
    fun theFindingNamesBothGroupsAndTheirSharedKey() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"depot","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                    {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":50,
                      "indexFieldName":"LineRef","elements":[
                      {"type":"Field","id":"f1","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}}
                    ]}},
                    {"type":"Group","id":"g2","name":"Picks","Group":{"repeatability":50,
                      "indexFieldName":"LineRef","elements":[
                      {"type":"Field","id":"f3","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f4","name":"PickedQty","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"RepetitionNotUnique(Lines/LineRef) And FieldFilled(Picks/PickedQty)",
                      "errorEntityRelPath":"../Lines/LineRef",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == notAllowed }
        assertEquals("/Depot/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Depot/Lines"), finding.explain)
        assertEquals(true, finding.explain.contains("/Depot/Picks"), finding.explain)
        assertEquals(true, finding.explain.contains("LineRef"), finding.explain)
    }
}
