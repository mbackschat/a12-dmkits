package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of which group-LIST guards read the computed field: mirrors
 * `rulekit.validate.laws.GroupListSelfReadLawsTest` on the same fleet shape. A12 carves `AtLeastOneGroupFilled`
 * over a sole ROOT group out of the containment walk, whatever lies below the root, and nothing else in the list
 * family: a sole non-root group, every multi-group list, `NoGroupFilled` over the root and the starred forms all read
 * the field. The targets are strings, because the checker leaves a Number target to the scale gate that answers first.
 */
class GroupListSelfReadTest {

    private val selfRead = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"

    @Test
    fun onlyThePositiveRootFormIsCarvedOutOfTheListQuantifiers() {
        val actual = linkedMapOf(
            "GroupFilled over its own group — the control" to inHub("GroupFilled(/Fleet/Hub)"),
            "AtLeastOneGroupFilled over its own group alone" to inHub("AtLeastOneGroupFilled(/Fleet/Hub)"),
            "AllGroupsFilled over its own group and a sibling" to inHub("AllGroupsFilled(/Fleet/Hub, /Fleet/Yard)"),
            "AtLeastOneGroupFilled over the root, target in the root" to inFleet("AtLeastOneGroupFilled(/Fleet)"),
            "AtLeastOneGroupFilled over the root, target below it" to inHub("AtLeastOneGroupFilled(/Fleet)"),
            "NoGroupFilled over the root" to inFleet("NoGroupFilled(/Fleet)"),
            "AtLeastOneGroupFilled over its starred group" to inBays("AtLeastOneGroupFilled(/Fleet/Bays*)"),
            "AtLeastOneGroupFilled over a sibling alone — the control" to inHub("AtLeastOneGroupFilled(/Fleet/Yard)"),
        )
        val expected = linkedMapOf(
            "GroupFilled over its own group — the control" to emptyList(),
            "AtLeastOneGroupFilled over its own group alone" to listOf(selfRead),
            "AllGroupsFilled over its own group and a sibling" to listOf(selfRead),
            "AtLeastOneGroupFilled over the root, target in the root" to emptyList(),
            "AtLeastOneGroupFilled over the root, target below it" to emptyList(),
            "NoGroupFilled over the root" to listOf(selfRead),
            "AtLeastOneGroupFilled over its starred group" to listOf(selfRead),
            "AtLeastOneGroupFilled over a sibling alone — the control" to emptyList<String>(),
        )
        assertEquals(expected, actual, "which group-list guards read the computed field")
    }

    private fun codes(source: String): List<String> =
        checkRead(ModelSource(source)).findings.filter { it.code == selfRead }.map { it.code }

    private fun inFleet(precondition: String) = codes(fleet(inFleet = computation("../Label", precondition)))

    private fun inHub(precondition: String) = codes(fleet(inHub = computation("../Tag", precondition)))

    private fun inBays(precondition: String) = codes(fleet(inBays = computation("../Code", precondition)))

    /** A computation writing [target] from a field outside every group the guards name. */
    private fun computation(target: String, precondition: String) = """
        ,{"type":"Computation","id":"c0","name":"YardComp","Computation":{
          "computedFieldRelPath":"$target",
          "errorMessage":[{"locale":"en_US","text":"x"}],
          "computationAlternatives":[{"precondition":"$precondition","operation":"[/Fleet/Source]"}]}}
    """.trimIndent()

    private fun fleet(inFleet: String = "", inHub: String = "", inBays: String = ""): String = """
        {"header":{"id":"fleet","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Fleet","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Source","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Hub","Group":{"elements":[
                {"type":"Field","id":"f2","name":"Tag","Field":{"fieldType":{"type":"StringType"}}}
                $inHub
            ]}},
            {"type":"Group","id":"g2","name":"Yard","Group":{"elements":[
                {"type":"Field","id":"f3","name":"Mark","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g3","name":"Bays","Group":{"repeatability":3,"elements":[
                {"type":"Field","id":"f4","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
                $inBays
            ]}}
            $inFleet
          ]}}
         ]}}}
    """.trimIndent()
}
