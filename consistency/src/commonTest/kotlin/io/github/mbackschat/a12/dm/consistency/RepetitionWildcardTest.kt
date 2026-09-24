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
    fun aStarOnAFixedGroupIsRefusedInEveryPositionAndOnARepeatableOneNeverByThisFamily() {
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
        // A12 answers the repeatable rows with its slot-specific wildcard classes, and THIS family reports
        // none of them: the two codes below come from [groupConditionWildcardFindings], which owns the
        // operator-picked classes. Kept in the grid rather than filtered out, because a row that fell silent
        // would then mean the sibling family stopped firing — which is exactly what this grid should notice.
        expected["scalar GroupFilled | repeatable"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["quantifier operand | FIXED"] = listOf(invalid)
        expected["quantifier operand | repeatable"] = listOf("MVK_NO_WILDCARDS_G_ALLOWED")
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
     * A starred fixed group with an unresolvable LEAF draws the WILDCARD class in A12 and nothing about the
     * leaf, because A12 walks the path segment by segment and the star's segment comes first
     * ([KERNEL-FINDINGS KF356]). This file used to report both classes, and then the entity class alone,
     * because the withholding gate read the whole path as one failed lookup. The reference walk now sees that
     * an earlier segment answered, so the class left is A12's own
     * (`GroupOperandAndKeyResolverClassTest` carries the grid on every carrier).
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a starred segment whose own prefix does not resolve"] = codes("GroupFilled(Nowhere*)")
        actual["a starred fixed group with an unresolvable LEAF"] = codes("FieldFilled(Guest*/NoSuchField)")
        actual["a star on a MID-PATH up-step"] = codes("GroupFilled(Stays/..*/Guest)")
        actual["a star on a NAMED turning point"] = codes("GroupFilled(Stays/..Stay*/Guest)")
        actual["a starred up-step that leaves the model"] = codes("GroupFilled(..*/Guest)")
        actual["no star at all — the control"] = codes("GroupFilled(Guest)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a starred segment whose own prefix does not resolve"] = listOf("MVK_INVALID_ENTITY")
        expected["a starred fixed group with an unresolvable LEAF"] = listOf(invalid)
        // An up-step outside the LEADING run is not valid A12 at all, starred, named or neither, so there is no
        // wildcard verdict here: the reference family reports the misplaced step (`MisplacedUpStepTest`).
        expected["a star on a MID-PATH up-step"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["a star on a NAMED turning point"] = listOf("MVK_UNEXPECTED_TOKEN")
        // The DEPTH class pre-empts the wildcard gate, so A12 never reaches it and neither does this family;
        // the reference family reports the overflow.
        expected["a starred up-step that leaves the model"] = listOf("MVK_TOO_MANY_PARENT_GROUPS")
        expected["no star at all — the control"] = emptyList()
        assertEquals(expected, actual, "the shapes it leaves alone")
    }

    /**
     * The two segments that are not NAMES, each with the class A12 gives it rather than the one this family
     * knows best.
     *
     * A starred rule-context entity and a starred up-step are both refusals, and both draw a code of their
     * own (`rulekit.validate.laws.StarredNonRepeatableReachLawsTest`). Collapsing them into
     * `MVK_INVALID_WILDCARD` would be a wrong finding on a correct rejection, which is the failure a reader
     * cannot tell from a right one.
     *
     * The up-step rows need a rule one level DOWN, because from a root-level rule the step leaves the model
     * and A12 reports the depth class before any star is considered.
     */
    @Test
    fun aStarOnASegmentThatIsNotANameDrawsItsOwnClass() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the rule-context entity"] = codes("GroupFilled(RuleGroup*)")
        actual["…the same unstarred — the control"] = codes("GroupFilled(RuleGroup)")
        actual["a leading up-step, from a rule one level down"] = nestedCodes("GroupFilled(..*/Guest)")
        actual["…the same step unstarred — the control"] = nestedCodes("GroupFilled(../Guest)")
        actual["a leading up-step with nothing after it"] = nestedCodes("GroupFilled(..*)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the rule-context entity"] = listOf("MVK_INVALID_WILDCARD_FOR_RULEGROUP")
        expected["…the same unstarred — the control"] = emptyList()
        expected["a leading up-step, from a rule one level down"] = listOf("MVK_INVALID_WILDCARD_REL")
        expected["…the same step unstarred — the control"] = emptyList()
        // A run of up-steps alone is a reference too, so the step is judged with nothing after it
        // (`rulekit.validate.laws.StarredNonRepeatableReachLawsTest`).
        expected["a leading up-step with nothing after it"] = listOf("MVK_INVALID_WILDCARD_REL")
        assertEquals(expected, actual, "a star on a segment that is not a name")
    }

    /**
     * The same model with the rule moved into `Stays`, so a leading `..` resolves instead of overflowing.
     *
     * The rule reports on a field of its own group, so nothing but the row under test can draw a finding.
     */
    private fun nestedCodes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"stay","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Stay","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Guest","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Nights","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Stays","Group":{"repeatability":9,"elements":[
                  {"type":"Field","id":"f2","name":"RoomRate","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Rule","id":"r0","name":"Probe","Rule":{
                    "errorCondition":"FieldFilled(RoomRate) And ${'$'}CONDITION",
                    "errorEntityRelPath":"../RoomRate",
                    "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                ]}}
              ]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

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
