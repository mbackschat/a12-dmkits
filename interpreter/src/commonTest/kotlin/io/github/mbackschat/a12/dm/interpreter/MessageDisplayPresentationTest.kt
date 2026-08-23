package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the message display presentation ([KF199]) — a `$field.value$` renders
 * the stored value re-presented for the MESSAGE LOCALE, for the nine locale tags the kernel maps and for no
 * others. The kernel truth is `adapter.laws.MessageDisplayPresentationDiffTest`; this proves the kernel-free
 * build reproduces it on both targets.
 *
 * The `fr_FR` row is the one that carries the contract: the map is keyed on the exact tag, so the ordinary
 * spelling of a French locale MISSES and every value renders as stored. `ch` is here because it is the only
 * entry whose grouping separator is an apostrophe, which no locale library would produce by itself.
 */
class MessageDisplayPresentationTest {

    private val model = """
        {
          "header": { "id": "claims-policy", "locales": [
            { "code": "en_US" }, { "code": "de_DE" }, { "code": "ch" }, { "code": "fr_FR" } ] },
          "content": {
            "modelRoot": { "rootGroups": [ {
              "type": "Group", "id": "/Policy", "name": "Policy", "Group": { "elements": [
                { "type": "Field", "id": "/Policy/Reference", "name": "Reference",
                  "Field": { "fieldType": { "type": "StringType" } } },
                { "type": "Field", "id": "/Policy/IncidentOn", "name": "IncidentOn", "Field": { "fieldType": {
                    "type": "DateType", "DateType": { "format": "yyyy-MM-dd", "datePrecision": "YEAR_OPTIONAL" } } } },
                { "type": "Field", "id": "/Policy/Claimed", "name": "Claimed", "Field": { "fieldType": {
                    "type": "NumberType", "NumberType": { "minFractionalDigits": 2, "maxFractionalDigits": 2 } } } },
                { "type": "Field", "id": "/Policy/CallAt", "name": "CallAt", "Field": { "fieldType": {
                    "type": "TimeType", "TimeType": { "format": "HH:mm:ss" } } } },
                { "type": "Rule", "id": "/Policy/Echo", "name": "Echo", "Rule": {
                    "errorEntityRelPath": "../Reference", "errorCode": "ECHO", "severity": "ERROR",
                    "errorCondition": "FieldFilled(Reference) And FieldFilled(IncidentOn) And FieldFilled(Claimed) And FieldFilled(CallAt)",
                    "errorMessage": [
                      { "locale": "en_US", "text": "$TEMPLATE" },
                      { "locale": "de_DE", "text": "$TEMPLATE" },
                      { "locale": "ch", "text": "$TEMPLATE" },
                      { "locale": "fr_FR", "text": "$TEMPLATE" } ] } }
              ] } } ] } }
        }
    """.trimIndent()

    private val definition = ModelLoader.load(model)

    private fun render(locale: String, incident: String): String {
        val doc = definition.newDocument().group("/Policy", 1)
            .field("/Policy/Reference", "P-1", 1, 1)
            .field("/Policy/IncidentOn", incident, 1, 1)
            .field("/Policy/Claimed", "1234567.50", 1, 1)
            .field("/Policy/CallAt", "13:45:00", 1, 1)
        return DmInterpreter(definition).validateFull(doc, locale).single { it.code == "ECHO" }.text
    }

    @Test fun enUsUsesTheMonthFirstPatternAndACommaGrouping() =
        assertEquals("d=[06/15/2024] n=[1,234,567.50] t=[13:45:00]", render("en_US", "2024-06-15"))

    @Test fun deDeUsesTheDayFirstPatternAndADotGrouping() =
        assertEquals("d=[15.06.2024] n=[1.234.567,50] t=[13:45:00]", render("de_DE", "2024-06-15"))

    @Test fun swissPairsTheGermanDatePatternWithAnApostropheGrouping() =
        assertEquals("d=[15.06.2024] n=[1'234'567.50] t=[13:45:00]", render("ch", "2024-06-15"))

    /** An unmapped tag has no presentation at all — every value renders in its own declared format. */
    @Test fun anUnmappedLocaleRendersEveryValueAsStored() =
        assertEquals("d=[2024-06-15] n=[1234567.50] t=[13:45:00]", render("fr_FR", "2024-06-15"))

    /** A partially known date keeps its markers on the COMPONENT, which is why they move with the pattern. */
    @Test fun aPartiallyKnownDateKeepsItsMarkersAndIsNeverCompleted() {
        assertEquals("d=[06/00/2024] n=[1,234,567.50] t=[13:45:00]", render("en_US", "2024-06-00"))
        assertEquals("d=[00.06.2024] n=[1.234.567,50] t=[13:45:00]", render("de_DE", "2024-06-00"))
        assertEquals("d=[00/00/0000] n=[1,234,567.50] t=[13:45:00]", render("en_US", "0000-00-00"))
    }
}

private const val TEMPLATE = "d=[\$IncidentOn.value\$] n=[\$Claimed.value\$] t=[\$CallAt.value\$]"
