package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the WIDER rule-message parameter forms ([KF198]) — the category suffix,
 * the Enumeration value label, and the bilingual Base Year terminal. The kernel truth is pinned by
 * `adapter.laws.RuleMessageParameterRenderingDiffTest`; this proves the kernel-free build reproduces it on
 * both targets, and additionally covers the `de_DE` condition language, which the JVM differential's kernel
 * fixture does not carry.
 *
 * The two condition languages are the discriminator that matters: the parameter terminals follow the model's
 * declared CONDITION language, so `$Referenzjahr$` is the Base Year on a German model and an ordinary field
 * name on an English one — and rendering the other language's spelling as the terminal would be a wrong
 * VALUE rather than a refusal.
 */
class RuleMessageParameterRenderTest {

    private fun policy(conditionLanguage: String, message: String) = policy(
        conditionLanguage, message,
        if (conditionLanguage == "de_DE") "FeldAngegeben(Reference)" else "FieldFilled(Reference)",
    )

    private fun policy(conditionLanguage: String, message: String, condition: String) = """
        {
          "header": { "id": "claims-policy", "locales": [ { "code": "en_US" } ] },
          "content": {
            "modelInfo": { "name": "claims-policy", "baseYear": "2020" },
            "modelConfig": { "conditionLanguage": { "code": "$conditionLanguage" } },
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Policy", "name": "Policy", "Group": { "elements": [
                { "type": "Field", "id": "/Policy/Reference", "name": "Reference",
                  "Field": { "fieldType": { "type": "StringType" } } },
                { "type": "Field", "id": "/Policy/Tier", "name": "Tier", "Field": { "fieldType": {
                    "type": "EnumerationType", "EnumerationType": {
                      "values": [
                        { "value": "BASIC", "label": [ { "locale": "en_US", "text": "Basic cover" } ] },
                        { "value": "PREMIUM" }
                      ],
                      "categories": [ { "name": "Band", "values": [ "STANDARD", "PRIORITY" ] } ] } } } },
                { "type": "Rule", "id": "/Policy/Probe", "name": "Probe", "Rule": {
                    "errorEntityRelPath": "../Reference", "errorCode": "PROBE", "severity": "ERROR",
                    "errorCondition": "$condition",
                    "errorMessage": [ { "locale": "en_US", "text": "$message" } ] } }
              ] } } ] } }
        }
    """.trimIndent()

    private fun render(conditionLanguage: String, message: String, tier: String?): String {
        val model = ModelLoader.load(policy(conditionLanguage, message))
        val doc = model.newDocument().group("/Policy", 1).field("/Policy/Reference", "P-1", 1, 1)
        if (tier != null) doc.field("/Policy/Tier", tier, 1, 1)
        return DmInterpreter(model).validateFull(doc, "en_US").single { it.code == "PROBE" }.text
    }

    @Test
    fun aCategoryAccessRendersTheMappedToken() =
        assertEquals("[STANDARD]", render("en_US", "[\$Tier->Band\$]", "BASIC"))

    @Test
    fun aLabelledEnumerationValueRendersItsLabelAndAnUnlabelledOneItsToken() {
        assertEquals("[Basic cover]", render("en_US", "[\$Tier.value\$]", "BASIC"))
        assertEquals("[PREMIUM]", render("en_US", "[\$Tier.value\$]", "PREMIUM"))
    }

    @Test
    fun anEmptyEnumerationRendersEmptyUnderBothSuffixes() {
        assertEquals("[]", render("en_US", "[\$Tier->Band\$]", null))
        assertEquals("[]", render("en_US", "[\$Tier.value\$]", null))
    }

    @Test
    fun theBaseYearTerminalAppliesItsSignedOffset() =
        assertEquals("[2020] [2023] [2019] [\$]",
            render("en_US", "[\$BaseYear\$] [\$BaseYear+3\$] [\$BaseYear-1\$] [\$\$]", "BASIC"))

    @Test
    fun theGermanConditionLanguageSelectsTheGermanTerminals() {
        assertEquals("[2023]", render("de_DE", "[\$Referenzjahr+3\$]", "BASIC"))
        assertEquals("[Basic cover]", render("de_DE", "[\$Tier.Wert\$]", "BASIC"))
    }

    /**
     * …and the other language's spelling is NOT the terminal: it takes the ordinary NAME route and, resolving
     * to no declared field, ends at the identifier's indexed debug form — the last stage of the `$field$`
     * cascade. The kernel refuses such a template outright (`INVALID_ENTITY`), so no legal model reaches this
     * rendering; what it pins is that the renderer never silently substitutes the year, which would turn the
     * bilingual split into a wrong VALUE rather than a refusal.
     */
    @Test
    fun theOtherLanguagesBaseYearSpellingIsNotTheTerminal() {
        assertEquals("[/Policy/BaseYear/1.1]", render("de_DE", "[\$BaseYear\$]", "BASIC"))
        assertEquals("[/Policy/Referenzjahr/1.1]", render("en_US", "[\$Referenzjahr\$]", "BASIC"))
    }

    /** The single-quote escape is erased before the lookup, so an unnecessary quote is transparent. */
    @Test
    fun anUnnecessaryQuoteIsTransparent() =
        assertEquals("[Tier]", render("en_US", "[\$'Tier'\$]", "BASIC"))
}
