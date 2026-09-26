package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a rule's error target is examined first, and its failure ends the rule".
 *
 * Mirrors `rulekit.validate.laws.RuleMemberVerdictWithholdingLawsTest`. The withholding lives where the
 * report is assembled rather than inside a family, so this is also the only test that can see it: a family's
 * own test builds its findings directly and never meets the gate.
 */
class RuleMemberWithholdingTest {

    private fun codes(condition: String, target: String): List<String> =
        checkRead(ModelSource(model(condition, target))).findings
            .filter { it.at == "/Policy/Probe" }.map { it.code }.sorted()

    @Test
    fun aBrokenErrorTargetWithholdsEveryOtherVerdictOnTheRule() {
        val actual = mutableMapOf<String, List<String>>()
        actual["both members sound — the control"] = codes("FieldFilled(Reference)", "../Reference")
        actual["the CONDITION names nothing"] = codes("FieldFilled(NoSuchField)", "../Reference")
        actual["the error TARGET names nothing"] = codes("FieldFilled(Reference)", "../NoSuchField")
        actual["BOTH name nothing"] = codes("FieldFilled(NoSuchField)", "../NoSuchField")
        actual["the target names nothing, a message parameter beside it"] =
            checkRead(ModelSource(model("FieldFilled(Reference)", "../NoSuchField", "see \$Premium\$")))
                .findings.filter { it.at == "/Policy/Probe" }.map { it.code }.sorted()
        // Both error-text families, because the withholding is applied once for all of them: a gate wired
        // into only one of them passes every other row here.
        actual["the target names nothing, a control character in the text"] =
            checkRead(ModelSource(model("FieldFilled(Reference)", "../NoSuchField", "see" + 0x09.toChar() + "it")))
                .findings.filter { it.at == "/Policy/Probe" }.map { it.code }.sorted()
        actual["a sound target, the same control character — the control"] =
            checkRead(ModelSource(model("FieldFilled(Reference)", "../Reference", "see" + 0x09.toChar() + "it")))
                .findings.filter { it.at == "/Policy/Probe" }.map { it.code }.sorted()

        val expected = mutableMapOf<String, List<String>>()
        expected["both members sound — the control"] = emptyList()
        expected["the CONDITION names nothing"] = listOf("MVK_INVALID_ENTITY")
        // A12 names MVK_MISSING_ERROR_FIELD beside it on each broken-target row: CONSISTENCY-GAPS DG112.
        expected["the error TARGET names nothing"] = listOf("MVK_ERROR_FIELD_MISSING")
        // The condition's own class is GONE, which is the whole family: judging the members independently
        // and reporting both would name a verdict A12 withheld.
        expected["BOTH name nothing"] = listOf("MVK_ERROR_FIELD_MISSING")
        expected["the target names nothing, a message parameter beside it"] =
            listOf("MVK_ERROR_FIELD_MISSING")
        expected["the target names nothing, a control character in the text"] =
            listOf("MVK_ERROR_FIELD_MISSING")
        // The twin that makes the row above mean "withheld" rather than "never fires here": the same text
        // over a sound target does report, so the fixture is not simply incapable of drawing that class.
        expected["a sound target, the same control character — the control"] = listOf("INVALID_CHARACTERS")
        assertEquals(expected, actual, "what a rule with a broken error target reports")
    }

    /**
     * An ABOVE-ROOT error target keeps its own finding, which the withholding can erase if it is careless.
     *
     * That target is unresolved like any other, so it enters the withheld set — and it reports under
     * `CK_MODEL_NOT_BUILDABLE` rather than the code the other broken targets use. A gate whitelisting only
     * those silences the rule entirely, its own trigger included, and no other test in the suite drives an
     * above-root target through the report.
     */
    @Test
    fun anAboveRootErrorTargetStillReportsItsOwnFinding() {
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codes("FieldFilled(Reference)", "../../../Reference"))
    }

    /**
     * A declaration is withheld by PATH, and a path does not identify one — so a sibling keeps its findings.
     *
     * An element's path is its parent plus its NAME, with no kind in it, so a field and a rule of one name
     * in one group share it. A12 refuses that model and this checker reports the collision, so the shape is
     * reachable; withholding on the shared path would delete the FIELD's own digit finding, which A12
     * withheld nothing about.
     */
    @Test
    fun aFieldSharingARulesPathKeepsItsOwnFindings() {
        val codes = checkRead(ModelSource(collidingModel())).findings
            .filter { it.at == "/Policy/Twin" }.map { it.code }.toSet()
        assertEquals(true, "MVK_FRACT_DIGITS_INVALID" in codes, codes.toString())
    }

    private fun collidingModel(): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f9","name":"Twin","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":3,"maxFractionalDigits":2}}}},
            {"type":"Rule","id":"r9","name":"Twin","Rule":{
              "errorCondition":"FieldFilled(Reference)","errorEntityRelPath":"../NoSuchField",
              "errorCode":"C9","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private fun model(condition: String, target: String, message: String = "see it"): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$target",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
