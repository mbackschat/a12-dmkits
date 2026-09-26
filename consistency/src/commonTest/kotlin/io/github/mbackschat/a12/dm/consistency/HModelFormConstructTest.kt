package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an A12 model DOES reach the H-model form checks, and is refused by name there".
 *
 * Mirrors `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`. The row that carries the whole
 * family is the LANGUAGE one: the identical condition text is this class in a German model and a parser
 * refusal in an English one, where the spelling is not a keyword at all — so an implementation keyed on the
 * spelling alone would name this class on a model A12 refuses for an entirely different reason.
 *
 * The other discriminating row is the ROW construct: it shares the German bundle, the H-model heritage and
 * the legacy feel, and the grammar predicate-gates it, so A12 refuses it at the parser even in German.
 */
class HModelFormConstructTest {

    private val refused = "MVK_INVALID_ROOT_GROUP_CONSTR"

    private val forms = listOf(
        "VordruckAngegeben", "VordruckNichtAngegeben", "KeinVordruckAngegeben",
        "MindestensEinVordruckAngegeben", "VordruckNichtFortlaufend", "UsbNichtFortlaufend",
    )

    @Test
    fun allSixFormConditionsAreReportedInAGermanModel() {
        assertEquals(forms.associateWith { listOf(refused) },
            forms.associateWith { codes(germanRule("$it(Bestellung)")) },
            "every measured form condition draws the A12-facing rejection")
    }

    /**
     * A construct's argument is a bare NAME, never an entity, so it is not a reference whatever it spells:
     * the COMPLETE class list is the construct's — `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest
     * .theArgumentIsNeverReadAsAnEntity`, whose conjunct naming the error field keeps the unreferenced-target
     * class out of the row. `Bestellung` does not resolve from inside its own group, so a reader taking it for a
     * reference names `MVK_INVALID_ENTITY` too, a class A12 never draws there.
     */
    @Test
    fun theConstructsArgumentIsNotAReference() {
        val actual = listOf("Bestellung", "NoSuchForm").associateWith { argument ->
            checkRead(ModelSource(germanRule("VordruckAngegeben($argument) Oder FeldAngegeben(Kennzeichen)"))).findings
                .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }
        }
        assertEquals(mapOf("Bestellung" to listOf(refused), "NoSuchForm" to listOf(refused)), actual,
            "the complete class list of a form construct in a German A12 model")
    }

    /**
     * The count operation in a rule CONDITION draws this class ALONE, as in A12
     * (`rulekit.validate.laws.HModelFormConditionReachabilityLawsTest`).
     *
     * `conditionPart` consumes an UNCATALOGED call as a whole condition, so the `> 0` after it is a token the
     * shape reader cannot use and draws the grammar class; the call is read to its end before that, and its
     * class is raised there, so the grammar class stands behind it. A count operation whose own call the parse
     * cuts short is not read, and is disclosed; a form CONDITION needs no call, and a condition naming no
     * construct discloses nothing.
     */
    @Test
    fun theCountOperationInAConditionIsReportedAndOneCutShortIsDisclosed() {
        val counted = checkRead(ModelSource(germanRule("AnzahlAngegebenerVordrucke(Bestellung) > 0")))
        assertEquals(listOf(refused),
            counted.findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code })
        assertEquals(emptyList(), counted.gaps.filter { it.reason.contains("H-model form construct") })
        val cutShort = checkRead(ModelSource(germanRule(
            "AnzahlAngegebenerVordrucke(Bestellung Oder FeldAngegeben(Kennzeichen)) > 0")))
        assertEquals(listOf<String?>("/Bestellung/Probe"),
            cutShort.gaps.filter { it.reason.contains("AnzahlAngegebenerVordrucke") }.map { it.at })
        // A call read ahead of the construct is not the construct: the disclosure asks about the construct.
        val behindACall = checkRead(ModelSource(germanRule(
            "FeldAngegeben(Kennzeichen) Und AnzahlAngegebenerVordrucke(Bestellung Oder) > 0")))
        assertEquals(listOf<String?>("/Bestellung/Probe"),
            behindACall.gaps.filter { it.reason.contains("AnzahlAngegebenerVordrucke") }.map { it.at })
        val condition = checkRead(ModelSource(germanRule("VordruckAngegeben(Bestellung Oder FeldAngegeben(Kennzeichen))")))
        assertEquals(emptyList(), condition.gaps.filter { it.reason.contains("H-model form construct") })
        assertEquals(emptyList(), checkRead(ModelSource(germanRule("FeldAngegeben(Kennzeichen)")))
            .gaps.filter { it.reason.contains("H-model form construct") }.map { it.at })
    }

    /**
     * Where the construct's class stands against the rest of the text, row for row
     * `rulekit.validate.laws.HModelFormConditionReachabilityLawsTest` in this sheet's own names: its
     * `theConstructsClassOrdersLikeTheOtherParseTimeClasses` and
     * `aFormConditionIsRaisedOnItsNameBeforeItsArgumentsAreRead`.
     */
    @Test
    fun theConstructsClassOrdersLikeTheOtherParseTimeClasses() {
        val rows = listOf(
            "VordruckAngegeben(Bestellung) Oder FeldAngegeben(NoSuchField)",
            "FeldAngegeben(NoSuchField) Oder VordruckAngegeben(Bestellung)",
            "VordruckAngegeben(Bestellung Oder FeldAngegeben(Kennzeichen))",
            "VordruckAngegeben(Bestellung) Oder Oder FeldAngegeben(Kennzeichen)",
            "AnzahlAngegebenerVordrucke(Bestellung) > 0",
            "AnzahlAngegebenerVordrucke(Bestellung) > 0 Oder FeldAngegeben(NoSuchField)",
            "FeldAngegeben(Kennzeichen) Und AnzahlAngegebenerVordrucke(Bestellung) > 0",
            "AktuelleVordruckNummer(Kennzeichen) > 0",
            "MaximalAngegebenerZeilenindex(Kennzeichen) > 0",
            "VordruckAngegeben Oder FeldAngegeben(Kennzeichen)",
            "VordruckAngegeben(",
            "VordruckAngegeben(Bestellung",
            "VordruckAngegeben(Oder)",
            "FeldAngegeben(Kennzeichen) Und VordruckAngegeben(Bestellung Oder)",
            "Oder VordruckAngegeben(Bestellung)",
            "FeldAngegeben(Kennzeichen) VordruckAngegeben(Bestellung)",
            "FeldAngegeben(VordruckAngegeben(Bestellung))",
            "(VordruckAngegeben(Bestellung))",
            "((VordruckAngegeben(Bestellung)))",
            "FeldAngegeben(Kennzeichen) Oder (VordruckAngegeben(Bestellung))",
            "FeldAngegeben(Kennzeichen) Und (VordruckAngegeben(Bestellung) Oder FeldAngegeben(Kennzeichen))",
            "FeldAngegeben(Kennzeichen) Oder (VordruckAngegeben(Bestellung Oder))",
        )
        val actual = rows.associateWith { condition ->
            checkRead(ModelSource(germanRule(condition))).findings
                .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }
        }
        val expected = rows.associateWith { listOf(refused) }.toMutableMap()
        expected["FeldAngegeben(NoSuchField) Oder VordruckAngegeben(Bestellung)"] = listOf("MVK_INVALID_ENTITY")
        // The parse fails ahead of the construct's name, so the grammar's class answers.
        expected["Oder VordruckAngegeben(Bestellung)"] = listOf("MVK_UNEXPECTED_TOKEN")
        // Where no condition opens, the parser refuses the keyword itself.
        expected["FeldAngegeben(Kennzeichen) VordruckAngegeben(Bestellung)"] = listOf("MVK_UNEXPECTED_TOKEN")
        // Divergence, asserted rather than hidden: this checker's grammar reads a nested call as the field
        // operand, where A12's refuses the keyword there (KERNEL-FINDINGS KF467, the operator's own argument
        // shape left to the Kernel). Silent, rather than naming the construct's class A12 does not draw.
        expected["FeldAngegeben(VordruckAngegeben(Bestellung))"] = emptyList()
        expected["AktuelleVordruckNummer(Kennzeichen) > 0"] = listOf("MVK_UNEXPECTED_TOKEN")
        expected["MaximalAngegebenerZeilenindex(Kennzeichen) > 0"] = listOf("MVK_UNEXPECTED_TOKEN")
        assertEquals(expected, actual, "where a form construct's class stands in a German A12 model")
    }

    @Test
    fun theSameTextDrawsNothingHereOutsideAGermanModel() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the count operation in a German COMPUTATION"] =
            codes(germanComputation("{ AnzahlAngegebenerVordrucke(Bestellung) }"))
        actual["the same condition in an ENGLISH model"] = codes(englishRule("VordruckAngegeben(Order)"))
        actual["a German ROW construct, which the grammar predicate-gates"] =
            codes(germanRule("ZeilenLueckenlos(Kennzeichen)"))
        actual["an ordinary German condition — the liveness control"] =
            codes(germanRule("FeldAngegeben(Kennzeichen)"))

        val expected = mutableMapOf<String, List<String>>()
        // The second carrier agrees, measured: the operation entry rule reaches the count op's own copy of
        // the same `isA12Model()` throw.
        expected["the count operation in a German COMPUTATION"] = listOf(refused)
        // `LexerTerminals_en` carries none of these spellings, so A12 answers MVK_UNEXPECTED_TOKEN from the
        // parser — the grammar family's class, and naming this one here would be the wrong class on a
        // correct rejection.
        expected["the same condition in an ENGLISH model"] = emptyList()
        // Same bundle, same heritage, different layer: `einfacheBedingung` predicate-gates `zeilenBedingung`
        // on `isHModel()` while leaving `formularBedingung` un-predicated, so this one never reaches a check.
        expected["a German ROW construct, which the grammar predicate-gates"] = emptyList()
        expected["an ordinary German condition — the liveness control"] = emptyList()
        assertEquals(expected, actual, "where the form-construct class may and may not be named")
    }

    private fun codes(model: String): List<String> =
        checkRead(ModelSource(model)).findings.filter { it.code == refused }.map { it.code }

    private fun germanRule(condition: String): String = german(
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../Kennzeichen",
              "errorCode":"D0","errorMessage":[{"locale":"de_DE","text":"x"}]}}""",
    )

    private fun germanComputation(operation: String): String = german(
        """{"type":"Computation","id":"c0","name":"Zaehler","Computation":{
              "computedFieldRelPath":"../Menge",
              "computationAlternatives":[{"operation":"${operation.replace("\"", "\\\"")}"}],
              "errorMessage":[{"locale":"de_DE","text":"x"}]}}""",
    )

    /** A German-language order sheet with a repeatable root group, so a level specifier is meaningful. */
    private fun german(declaration: String): String = """
        {"header":{"id":"bestellung","locales":[{"code":"de_DE"}]},
         "content":{"modelConfig":{"conditionLanguage":{"code":"de_DE"}},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Bestellung","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"f0","name":"Kennzeichen","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Menge","Field":{"fieldType":{"type":"NumberType"}}},
            $declaration
          ]}}
         ]}}}
    """.trimIndent()

    /** The same sheet in English, which is where the spellings stop being keywords. */
    private fun englishRule(condition: String): String = """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"repeatability":9,"elements":[
            {"type":"Field","id":"f0","name":"Marker","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../Marker",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
