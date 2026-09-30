package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the rule-message `$#…$` GROUP parameter's two keyword shorthands in BOTH
 * condition languages. What the position renders on an English model is kernel-pinned by
 * `adapter.laws.RuleMessageGroupParameterDiffTest`, including its German runtime fixture and the relative
 * group-address controls. Admission alone would not establish what a message renders.
 *
 * The shorthands are two of the five bilingual terminals, so rendering the other language's spelling as the
 * terminal would be a wrong VALUE rather than a refusal. The fixture fires on row 2 of a repeatable group so
 * the two endpoints of the ancestor chain carry DIFFERENT integers; on row 1 they would agree and the test
 * would pass against an implementation that resolved neither.
 */
class RuleMessageGroupParameterRenderTest {

    private fun order(conditionLanguage: String, message: String) = """
        {
          "header": { "id": "order-lines", "locales": [ { "code": "en_US" } ] },
          "content": {
            "modelInfo": { "name": "order-lines" },
            "modelConfig": { "conditionLanguage": { "code": "$conditionLanguage" } },
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [
                { "type": "Group", "id": "/Order/Lines", "name": "Lines",
                  "Group": { "repeatability": 10, "elements": [
                    { "type": "Field", "id": "/Order/Lines/Marker", "name": "Marker",
                      "Field": { "fieldType": { "type": "StringType" } } },
                    { "type": "Rule", "id": "/Order/Lines/Probe", "name": "Probe", "Rule": {
                        "errorEntityRelPath": "../Marker", "errorCode": "PROBE", "severity": "ERROR",
                        "errorCondition": "${if (conditionLanguage == "de_DE") "FeldAngegeben" else "FieldFilled"}(Marker)",
                        "errorMessage": [ { "locale": "en_US", "text": "$message" } ] } }
                  ] } }
              ] } } ] } }
        }
    """.trimIndent()

    /** Marker is filled on row 2 only, so the rule fires once and the chain's two endpoints read 2 and 1. */
    private fun render(conditionLanguage: String, message: String): String {
        val model = ModelLoader.load(order(conditionLanguage, message))
        val doc = model.newDocument()
            .group("/Order", 1)
            .group("/Order/Lines", 1, 1)
            .group("/Order/Lines", 1, 2)
            .field("/Order/Lines/Marker", "M-2", 1, 2, 1)
        return DmInterpreter(model).validateFull(doc, "en_US").single { it.code == "PROBE" }.text
    }

    @Test
    fun theEnglishShorthandsNameTheTwoEndpointsOfTheRulesAncestorChain() =
        assertEquals("[2] [1]", render("en_US", "[\$#RuleGroup\$] [\$#RootGroup\$]"))

    @Test
    fun theGermanConditionLanguageSelectsTheGermanShorthands() =
        assertEquals("[2] [1]", render("de_DE", "[\$#RegelKontext\$] [\$#LfdNr\$]"))

    /**
     * …and the other language's spelling is NOT the terminal. The kernel refuses such a template outright
     * (`INVALID_GROUP` — it looks the word up as a group name), so no legal model reaches this rendering;
     * what it pins is that the renderer never silently substitutes an index, which would turn the bilingual
     * split into a wrong VALUE rather than a refusal.
     */
    @Test
    fun theOtherLanguagesShorthandIsNotTheTerminal() {
        assertEquals("[\$#RegelKontext\$] [\$#LfdNr\$]",
            render("en_US", "[\$#RegelKontext\$] [\$#LfdNr\$]"))
        assertEquals("[\$#RuleGroup\$] [\$#RootGroup\$]",
            render("de_DE", "[\$#RuleGroup\$] [\$#RootGroup\$]"))
    }

    /** The absolute-path spellings the shorthands abbreviate, and the per-segment quote escape ([KF214]). */
    @Test
    fun anAbsolutePathReadsItsOwnLevelAndTheQuoteEscapeIsPerSegment() =
        assertEquals("[2] [1] [2]",
            render("en_US", "[\$#/Order/Lines\$] [\$#/Order\$] [\$#/'Order'/'Lines'\$]"))
    @Test
    fun relativeGroupArgumentsReadTheSameCoordinatesAsAbsoluteArguments() {
        for (atRoot in listOf(true, false)) {
            val group = if (atRoot) "/Order" else "/Order/Lines"
            val argument = if (atRoot) "Lines" else "../Lines"
            val original = ModelLoader.load(order("en_US", "relative=[\$#$argument\$] absolute=[\$#/Order/Lines\$]"))
            val rule = original.rules.single()
            val model = original.copy(rules = listOf(rule.copy(
                path = "$group/Probe", group = group,
                conditionText = if (atRoot) "FieldFilled(Lines/Marker)" else rule.conditionText,
                errorEntityRelPath = if (atRoot) "../Lines/Marker" else rule.errorEntityRelPath,
            )))
            val doc = model.newDocument().group("/Order", 1)
                .group("/Order/Lines", 1, 1).group("/Order/Lines", 1, 2).group("/Order/Lines", 1, 3)
                .field("/Order/Lines/Marker", "M-2", 1, 2, 1)
                .field("/Order/Lines/Marker", "M-3", 1, 3, 1)
            assertEquals(listOf("relative=[2] absolute=[2]", "relative=[3] absolute=[3]"),
                DmInterpreter(model).validateFull(doc, "en_US").filter { it.code == "PROBE" }.map { it.text }, argument)
        }
    }

    @Test
    fun unresolvedRelativeGroupArgumentsPreserveTheirTokens() {
        val message = "[\$#../Missing\$] [\$#../../../Missing\$] [\$#../../Order/Lines\$]"
        assertEquals(message, render("en_US", message))
    }

}
