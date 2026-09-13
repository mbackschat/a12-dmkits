package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the repetition-wildcard law.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.StarredNonRepeatableReachLawsTest`. The paired rows are the point: the same star on
 * a REPEATABLE group is answered three different ways depending on the slot, and on a non-repeatable one
 * every slot collapses to `MVK_INVALID_WILDCARD` — so each violation row sits beside the control that would
 * catch a family reporting on the star rather than on what it lands on.
 */
class RepetitionWildcardTest {

    private val invalid = "MVK_INVALID_WILDCARD"

    /**
     * A booking model whose one rule carries [condition].
     *
     * `Guest` is a FIXED group holding a number, `Stays` is repeatable, and `Stays/Rate` is a fixed group
     * nested inside the repeatable one — the shape that makes the law a question about each SEGMENT.
     */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"stay","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Stay","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Guest","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Nights","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Stays","Group":{"repeatability":9,
                  "elements":[
                  {"type":"Field","id":"f2","name":"RoomRate","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g3","name":"Rate","Group":{"elements":[
                    {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And ${'$'}CONDITION",
                  "errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun aStarOnAFixedGroupIsRefusedInEveryPositionAndOnARepeatableOneNowhereHere() {
        val actual = mutableMapOf<String, List<String>>()
        actual["scalar GroupFilled | FIXED"] = codes("GroupFilled(Guest*)")
        actual["scalar GroupFilled | repeatable"] = codes("GroupFilled(Stays*)")
        actual["quantifier operand | FIXED"] = codes("AllGroupsFilled(Guest*, Stays)")
        actual["quantifier operand | repeatable"] = codes("AllGroupsFilled(Stays*, Guest)")
        actual["count operand | FIXED"] = codes("NumberOfFilledGroups(Guest*) > 0")
        actual["count operand | repeatable"] = codes("NumberOfFilledGroups(Stays*) > 0")
        actual["a starred path PREFIX | FIXED"] = codes("FieldFilled(Guest*/Nights)")
        actual["a starred path PREFIX | repeatable"] = codes("FieldFilled(Stays*/RoomRate)")
        actual["a star on a FIELD"] = codes("FieldFilled(Guest/Nights*)")
        actual["a star on the ROOT group"] = codes("GroupFilled(/Stay*)")

        val expected = mutableMapOf<String, List<String>>()
        expected["scalar GroupFilled | FIXED"] = listOf(invalid)
        // A12 answers the repeatable rows with its slot-specific wildcard classes, which this checker does
        // not report — so its silence there is right for a different reason than the ADMITTED rows' is.
        expected["scalar GroupFilled | repeatable"] = emptyList()
        expected["quantifier operand | FIXED"] = listOf(invalid)
        expected["quantifier operand | repeatable"] = emptyList()
        expected["count operand | FIXED"] = listOf(invalid)
        expected["count operand | repeatable"] = emptyList()
        expected["a starred path PREFIX | FIXED"] = listOf(invalid)
        expected["a starred path PREFIX | repeatable"] = emptyList()
        expected["a star on a FIELD"] = listOf(invalid)
        expected["a star on the ROOT group"] = listOf(invalid)
        assertEquals(expected, actual, "a star on a fixed group, in every position")
    }

    /**
     * The law is about each SEGMENT, which the nested rows are the only way to show.
     *
     * A reference can carry a legitimate star and an illegitimate one at once, and a rule reading the
     * reference as a whole answers one of the two rows below wrongly whichever way it is written.
     */
    @Test
    fun eachSegmentIsJudgedOnItsOwn() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a fixed group starred UNDER a legitimately starred one"] =
            codes("Sum(Stays*/Rate*/Amount) > 0")
        actual["…the same with only the legitimate star — the control"] =
            codes("Sum(Stays*/Rate/Amount) > 0")
        actual["the inner star with none on the repeatable one"] = codes("Sum(Stays/Rate*/Amount) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a fixed group starred UNDER a legitimately starred one"] = listOf(invalid)
        expected["…the same with only the legitimate star — the control"] = emptyList()
        expected["the inner star with none on the repeatable one"] = listOf(invalid)
        assertEquals(expected, actual, "each segment is judged on its own")
    }

    /**
     * What it stays silent on, and the one pre-emption that runs the other way.
     *
     * A starred fixed group with an unresolvable LEAF draws this class in A12 and the entity class here too,
     * which is the reverse of every other pair: A12 stops at the wildcard. Both findings are real — fixing
     * the star leaves the leaf missing — so the second is a truth about the model rather than a divergence.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred segment whose own prefix does not resolve"] = codes("GroupFilled(Nowhere*)")
        actual["a starred fixed group with an unresolvable LEAF"] = codes("FieldFilled(Guest*/NoSuchField)")
        actual["no star at all — the control"] = codes("GroupFilled(Guest)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred segment whose own prefix does not resolve"] = listOf("MVK_INVALID_ENTITY")
        expected["a starred fixed group with an unresolvable LEAF"] = listOf("MVK_INVALID_ENTITY", invalid)
        expected["no star at all — the control"] = emptyList()
        assertEquals(expected, actual, "the shapes it leaves alone")
    }

    /** A computation's operation is read with the same reader, and A12 answers it with the same class. */
    @Test
    fun aComputationsOperationIsJudgedToo() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"stay","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Stay","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Group","id":"g1","name":"Guest","Group":{"elements":[
                      {"type":"Field","id":"f1","name":"Nights","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Computation","id":"c0","name":"StayTotal","Computation":{
                      "computedFieldRelPath":"../Total",
                      "computationAlternatives":[{"operation":"Sum(Guest*/Nights)"}],
                      "errorMessage":[{"locale":"en_US","text":"the stay total"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(invalid, finding.code)
        assertEquals("/Stay/StayTotal", finding.at)
        assertEquals(true, finding.explain.contains("computationAlternatives[0].operation"), finding.explain)
    }
}
