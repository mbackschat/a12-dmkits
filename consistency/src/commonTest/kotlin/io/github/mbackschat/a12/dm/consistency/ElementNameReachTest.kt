package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a name A12's converter cannot keep reaches past its own declaration ([KERNEL-FINDINGS KF652]): an empty
 * root name makes every reference beneath it unreadable, and a computed field the converter left out leaves no
 * model. `adapter.laws.CheckerConverterSentenceAgreementTest` measures every row here on the kernel; this class
 * locks the checker's half where no kernel runs.
 */
class ElementNameReachTest {

    /** A cover with a premium and a code, [rootName] spelling the root's `name` member, and [elements] beside them. */
    private fun model(rootName: String, vararg elements: String, premium: String = "Premium") = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"cover","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0",$rootName"Group":{"elements":[
            {"type":"Field","id":"f0","name":"$premium","Field":{"fieldType":{"type":"NumberType","NumberType":{"maxFractionalDigits":0}}}},
            {"type":"Field","id":"f1","name":"Code","Field":{"fieldType":{"type":"StringType"}}}${elements.joinToString("") { ",$it" }}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    private fun rule(errorRelPath: String) = """{"type":"Rule","id":"r0","name":"CodeCheck","Rule":{
        "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"$errorRelPath",
        "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}"""

    /** Through [check] rather than a read that must be admitted, since a refused source is a row here. */
    private fun codes(source: ModelSource) = check(source).findings.filter { it.justifiesRejection }.map { it.code }.toSet()

    /**
     * The reader resolves a reference from its declaration's full name, which starts with two slashes under a root
     * written as `""`. An empty reference is not resolved, and an omitted root name is joined as the text `null`,
     * so neither refuses anything: those rows are the controls that keep the refusal from reading as "any nameless
     * root".
     */
    @Test
    fun aReferenceBeneathARootWrittenEmptyLeavesNoModel() {
        val nameless = setOf(RK_ROOT_GROUP_NAME_MISSING, RK_NAME_EMPTY)
        val rows = linkedMapOf(
            "a rule beneath an empty root" to (model(""""name":"",""", rule("../Code")) to setOf(CK_MODEL_NOT_BUILDABLE)),
            "an empty error path beneath it" to (model(""""name":"",""", rule("")) to nameless + "RK_ERROR_ENTITY_EMPTY"),
            "a rule beneath an omitted root name" to (model("", rule("../Code")) to nameless),
            "a rule beneath a null root name" to (model(""""name":null,""", rule("../Code")) to nameless),
            "no reference beneath an empty root" to (model(""""name":"",""") to nameless),
        )
        assertEquals(rows.mapValues { it.value.second }, rows.mapValues { codes(it.value.first) })
    }

    /**
     * A12's reader finds a computed field the converter then leaves out, so the computation is kept with no target
     * and the calculation check cannot run on it; an error field left out the same way is only a missing one. The
     * converter has stated the blank name by then, so its sentence stands beside the unbuildable model.
     */
    @Test
    fun aComputedFieldTheConverterLeftOutLeavesNoModel() {
        val computation = """{"type":"Computation","id":"c0","name":"Fill","Computation":{
            "computedFieldRelPath":"../ ","computationAlternatives":[{"operation":"1"}],
            "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        fun withBlankPremium(element: String) = model(""""name":"Cover",""", element, premium = " ")
        assertEquals(setOf(RK_ELEMENT_NAME_MISSING, CK_MODEL_NOT_BUILDABLE), codes(withBlankPremium(computation)))
        assertEquals(
            setOf(RK_ELEMENT_NAME_MISSING, "MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
            codes(withBlankPremium(rule("../ "))),
        )
    }
}
