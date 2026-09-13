package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the empty-group law — declaring a group with no fields is legal, NAMING one is not.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EmptyGroupOperandLawsTest`. The SHELL rows carry the over-rejection risk: a group
 * with no direct field but a populated descendant is admitted everywhere an empty one is refused, so a
 * checker testing direct children would refuse a model A12 takes.
 */
class EmptyGroupOperandTest {

    private val groupIsEmpty = "MVK_GROUP_IS_EMPTY"
    private val rootIsEmpty = "MVK_ROOT_GROUP_IS_EMPTY"

    /**
     * A store model whose one rule carries [condition].
     *
     * `Empty` has nothing, `Shell` has a populated subgroup and no direct field, `Deep` reaches its field
     * three levels down, `Filled` carries one directly, and `Vacant` is a second root with nothing at all.
     */
    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"store","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Store","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Empty","Group":{"elements":[]}},
                {"type":"Group","id":"g2","name":"Shell","Group":{"elements":[
                  {"type":"Group","id":"g3","name":"Inner","Group":{"elements":[
                    {"type":"Field","id":"f1","name":"InnerNote","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                ]}},
                {"type":"Group","id":"g4","name":"Deep","Group":{"elements":[
                  {"type":"Group","id":"g5","name":"Mid","Group":{"elements":[
                    {"type":"Group","id":"g6","name":"Leaf","Group":{"elements":[
                      {"type":"Field","id":"f2","name":"LeafNote","Field":{"fieldType":{"type":"StringType"}}}
                    ]}}
                  ]}}
                ]}},
                {"type":"Group","id":"g7","name":"Filled","Group":{"elements":[
                  {"type":"Field","id":"f3","name":"FilledNote","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"${'$'}CONDITION","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}},
              {"type":"Group","id":"g8","name":"Vacant","Group":{"elements":[]}}
             ]}}}
            """.trimIndent().replace("\$CONDITION", condition),
        ),
    ).findings.map { it.code }

    @Test
    fun namingAnEmptyGroupIsRefusedAndNamingAShellIsNot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a populated group — the control"] = codes("GroupFilled(/Store/Filled)")
        actual["an EMPTY group"] = codes("GroupFilled(/Store/Empty)")
        actual["a SHELL — no direct field, a populated descendant"] = codes("GroupFilled(/Store/Shell)")
        actual["a group whose field is three levels down"] = codes("GroupFilled(/Store/Deep)")
        actual["an empty group beside a populated one"] =
            codes("AllGroupsFilled(/Store/Empty, /Store/Filled)")
        actual["an empty ROOT group"] = codes("AtLeastOneGroupFilled(/Vacant)")
        actual["the populated root — the control"] = codes("AtLeastOneGroupFilled(/Store)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a populated group — the control"] = emptyList()
        expected["an EMPTY group"] = listOf(groupIsEmpty)
        // The two rows that make emptiness a question about the SUBTREE. Testing direct children would
        // refuse both, and A12 admits both.
        expected["a SHELL — no direct field, a populated descendant"] = emptyList()
        expected["a group whose field is three levels down"] = emptyList()
        expected["an empty group beside a populated one"] = listOf(groupIsEmpty)
        // A root group answers under its own class, as it does for its name laws.
        expected["an empty ROOT group"] = listOf(rootIsEmpty)
        expected["the populated root — the control"] = emptyList()
        assertEquals(expected, actual, "naming an empty group, and naming a shell")
    }

    /** The finding names the group, since a condition can name several. */
    @Test
    fun theFindingNamesTheGroupAndWhereItWasWritten() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"store","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Store","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Empty","Group":{"elements":[]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"GroupFilled(/Store/Empty)","errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(groupIsEmpty, finding.code)
        assertEquals("/Store/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Store/Empty"), finding.explain)
    }
}
