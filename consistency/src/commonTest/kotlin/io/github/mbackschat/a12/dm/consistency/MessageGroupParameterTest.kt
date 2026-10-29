package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the `$#…$` GROUP position.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RuleMessageGroupParameterLawsTest`, on the same policy shape. The admitted rows
 * carry the risk: the two shorthands and a quoted SEGMENT are legal, so reporting any of them would be an
 * over-rejection, while a quoted KEYWORD stops being one and must be reported.
 */
class MessageGroupParameterTest {

    private val group = "INVALID_GROUP"
    private val rootGroup = "INVALID_ROOT_GROUP"

    private val starredIteration = "ITER_PARAM_WITH_STARREFERENCE"

    @Test
    fun whichGroupTheParameterMayName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the rule's own group"] = reporting("/Policy/Cover/Sections")
        actual["its parent"] = reporting("/Policy/Cover")
        actual["the chain's root"] = reporting("/Policy")
        actual["RuleGroup — the near shorthand"] = reporting("RuleGroup")
        actual["RootGroup — the far shorthand"] = reporting("RootGroup")
        actual["every segment quoted"] = reporting("/'Policy'/'Cover'")
        actual["a descendant of the rule's group"] = reporting("/Policy/Cover/Sections/Details")
        actual["a sibling of the rule's group"] = reporting("/Policy/Cover/Exclusions")
        actual["a relative spelling of a real ancestor"] = reporting("Cover")
        actual["a declared root, UNDECLARED child"] = reporting("/Policy/NoSuchGroup")
        actual["an undeclared root, alone"] = reporting("/Nope")
        actual["an undeclared root, with a child"] = reporting("/Nope/Sections")
        actual["the right root in the wrong case"] = reporting("/policy")
        actual["RootGroup quoted"] = reporting("'RootGroup'")
        actual["a retired terminal"] = reporting("Zeile")
        actual["that same terminal QUOTED"] = reporting("'Zeile'")
        actual["a malformed spelling — an unbalanced quote"] = reporting("/'Policy")
        actual["the GERMAN near shorthand, on an en_US model"] = reporting("RegelKontext")
        actual["the GERMAN far shorthand, on an en_US model"] = reporting("LfdNr")

        val expected = mutableMapOf<String, List<String>>()
        expected["the rule's own group"] = emptyList()
        expected["its parent"] = emptyList()
        expected["the chain's root"] = emptyList()
        expected["RuleGroup — the near shorthand"] = emptyList()
        expected["RootGroup — the far shorthand"] = emptyList()
        // Quoting is transparent for a SEGMENT, so this reaches the same group its bare spelling does.
        expected["every segment quoted"] = emptyList()
        // Containment runs the opposite way from most reach laws here: a DESCENDANT does not contain the
        // rule, so it is refused exactly as a sibling is.
        expected["a descendant of the rule's group"] = listOf(group)
        expected["a sibling of the rule's group"] = listOf(group)
        // The position takes an ABSOLUTE path, so a relative spelling of a genuine ancestor is refused —
        // and on the containment class, never the root one, which is what keeps the root gate absolute-only.
        expected["a relative spelling of a real ancestor"] = listOf(group)
        // Existence is NOT part of the law: this address resolves to nothing and still draws the containment
        // class, which one measurement settled against a source read predicting `INVALID_ENTITY`.
        expected["a declared root, UNDECLARED child"] = listOf(group)
        expected["an undeclared root, alone"] = listOf(rootGroup)
        expected["an undeclared root, with a child"] = listOf(rootGroup)
        expected["the right root in the wrong case"] = listOf(rootGroup)
        // …and quoting a KEYWORD is not transparent: it stops being a terminal and is looked up as a group
        // name of that spelling. The pair with the bare row is the separator between the two accounts.
        expected["RootGroup quoted"] = listOf(group)
        expected["a retired terminal"] = listOf("PARAM_INVALID_IN_NEW_WORLD")
        expected["that same terminal QUOTED"] = listOf(group)
        // A12 answers a malformed spelling LEXICALLY, on three classes this family does not reproduce, so
        // silence here is a disclosed under-report rather than a verdict.
        expected["a malformed spelling — an unbalanced quote"] = emptyList()
        // Each language admits ONLY ITS OWN shorthand — the other language's word is looked up as a group
        // NAME and draws the containment class. Measured in both directions in
        // `rulekit.validate.laws.RuleMessageParameterLanguageLawsTest`; these two are the English direction.
        expected["the GERMAN near shorthand, on an en_US model"] = listOf(group)
        expected["the GERMAN far shorthand, on an en_US model"] = listOf(group)
        assertEquals(expected, actual, "which group the parameter may name")
    }

    /** The shorthand refused at the ROOT locus, where the same group stays nameable two other ways. */
    @Test
    fun theRuleGroupShorthandIsRefusedWhereTheRulesGroupIsItselfARoot() {
        val actual = mutableMapOf<String, List<String>>()
        actual["RuleGroup, rule IN the root group"] = atRoot("RuleGroup")
        actual["RootGroup, same rule"] = atRoot("RootGroup")
        actual["the root by absolute path, same rule"] = atRoot("/Policy")

        val expected = mutableMapOf<String, List<String>>()
        expected["RuleGroup, rule IN the root group"] = listOf("RULEGROUP_IMMEDIATELY_BELOW_ROOT")
        // The refusal is of the WORD at that locus and not of the group it names, which only these two
        // control rows can show: both name the very same group and both are admitted.
        expected["RootGroup, same rule"] = emptyList()
        expected["the root by absolute path, same rule"] = emptyList()
        assertEquals(expected, actual, "the refusal is of the word at that locus")
    }

    /**
     * The gate BEHIND containment: the condition has to ride the named group without a wildcard.
     *
     * Every row holds the argument's containment fixed and moves only the CONDITION's form, so a refusal
     * here cannot be the gate before it. The root shorthand is the row that keeps the gate from reading as
     * "a starred condition refuses every group parameter": a reference into the rule's own group rides the
     * ROOT unstarred, whatever the star does below it.
     */
    @Test
    fun theConditionHasToRideTheNamedGroupWithoutAWildcard() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the near shorthand, its group read per row — the control"] = reporting("RuleGroup")
        actual["the near shorthand, its group read through a star alone"] = starred("RuleGroup")
        actual["the same group by absolute path, read through a star alone"] =
            starred("/Policy/Cover/Sections")
        actual["the ROOT shorthand on that same condition"] = starred("RootGroup")
        actual["an ANCESTOR by absolute path, with the star BELOW it"] = starred("/Policy/Cover")

        val refused = listOf(starredIteration)
        val expected = mutableMapOf<String, List<String>>()
        expected["the near shorthand, its group read per row — the control"] = emptyList()
        expected["the near shorthand, its group read through a star alone"] = refused
        // The gate is the POSITION's, not the shorthand's — the same group spelled out draws the same class.
        expected["the same group by absolute path, read through a star alone"] = refused
        expected["the ROOT shorthand on that same condition"] = emptyList()
        // A star BELOW the named group leaves that group ridden per row, so this must stay silent — the
        // branch that keeps the reducer from reporting on any condition that carries a star anywhere.
        expected["an ANCESTOR by absolute path, with the star BELOW it"] = emptyList()
        assertEquals(expected, actual, "the condition has to ride the named group without a wildcard")
    }

    private val owned = setOf(
        group, rootGroup, "PARAM_INVALID_IN_NEW_WORLD", "RULEGROUP_IMMEDIATELY_BELOW_ROOT",
        starredIteration,
    )

    private fun reporting(argument: String): List<String> =
        checkRead(ModelSource(model(argument, deep = true))).findings.filter { it.code in owned }.map { it.code }

    /** The same deep rule, with its own group read through a repetition wildcard and nothing else. */
    private fun starred(argument: String): List<String> = checkRead(
        ModelSource(model(argument, deep = true, condition = "AtLeastOneFieldFilled(/Policy/Cover/Sections*/Amount)")),
    ).findings.filter { it.code in owned }.map { it.code }

    /**
     * The root-locus rule reads the root's own field, as the kernel row does: the default condition reads
     * `Sections` unstarred from the root, which A12 refuses with `MVK_NO_WILDCARD` and withholds the message
     * behind ([KERNEL-FINDINGS KF338]) — a fixture that measured nothing about the parameter.
     */
    private fun atRoot(argument: String): List<String> =
        checkRead(ModelSource(model(argument, deep = false, condition = "FieldFilled(/Policy/Reference)")))
            .findings.filter { it.code in owned }.map { it.code }

    /** An insurance policy whose rule sits three levels down, or in the root group when [deep] is false. */
    private fun model(
        argument: String,
        deep: Boolean,
        condition: String = "FieldFilled(/Policy/Cover/Sections/Amount)",
    ): String {
        val rule = """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition",
              "errorEntityRelPath":"${if (deep) "../Amount" else "../Reference"}","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"row ${'$'}#$argument${'$'} needs an amount."}]}}"""
        return """
        {"header":{"modelVersion":"28.4.0","id":"policy-group-parameter","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f9","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Cover","Group":{"elements":[
              {"type":"Group","id":"g2","name":"Sections","Group":{"repeatability":20,"elements":[
                {"type":"Field","id":"f0","name":"SectionNo","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g3","name":"Details","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}}${if (deep) ",$rule" else ""}
              ]}},
              {"type":"Group","id":"g4","name":"Exclusions","Group":{"elements":[
                {"type":"Field","id":"f3","name":"ExclusionNote","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
            ]}}${if (deep) "" else ",$rule"}
          ]}}
         ]}}}
        """.trimIndent()
    }
}
